package com.dialingassistant.agent;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mirrors all Android notifications to RTDB and handles bi-directional
 * actions (dismiss, inline reply, action buttons) sent from the web app.
 *
 * RTDB paths:
 *   agents/{uid}/notifications/{postTimeMs}  – notification data
 *   agents/{uid}/state/notification_actions  – action command from web
 *
 * Usage: the service must be enabled by the user in
 *   Settings → Apps → Special app access → Notification access
 */
public class NotificationListener extends NotificationListenerService {

    private static final String TAG = "NotificationListener";

    // Package names to skip entirely
    private static final String[] SKIP_PACKAGES = {
        "android", "com.android.systemui", "com.android.phone",
        "com.google.android.gms", "com.google.android.gsf",
    };

    private FirestoreManager firestoreManager;
    private ListenerRegistration actionListener;
    private ListenerRegistration actionQueueListener;
    private volatile boolean processingAction = false;
    private final Set<String> processingActionIds = ConcurrentHashMap.newKeySet();

    /** keyed by StatusBarNotification.getKey() for reply/dismiss */
    private final Map<String, StatusBarNotification> activeSBNs = new ConcurrentHashMap<>();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "Listener connected");
        initFirestore();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.d(TAG, "Listener disconnected");
        if (actionListener != null) { actionListener.remove(); actionListener = null; }
        if (actionQueueListener != null) { actionQueueListener.remove(); actionQueueListener = null; }
        activeSBNs.clear();
        processingActionIds.clear();
    }

    private void initFirestore() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.w(TAG, "No authenticated user — RTDB listener skipped");
            return;
        }
        firestoreManager = new FirestoreManager(user.getUid());
        startActionListener();
    }

    // ── Action command listener (web → Android) ───────────────────────────────

    private void startActionListener() {
        if (firestoreManager == null) return;
        if (actionListener != null) {
            actionListener.remove();
            actionListener = null;
        }
        actionListener = firestoreManager.listenToNotificationActions((snapshot, error) -> {
            if (error != null || snapshot == null || !snapshot.exists()) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) snapshot.getValue();
            if (data == null) return;
            String requestId = str(data, "requestId");
            if (requestId.isEmpty()) requestId = "legacy-" + snapshot.getKey();
            processNotificationAction(requestId, data, () -> firestoreManager.clearNotificationAction());
        });

        if (actionQueueListener != null) {
            actionQueueListener.remove();
            actionQueueListener = null;
        }
        actionQueueListener = firestoreManager.listenToNotificationActionQueue((snapshot, error) -> {
            if (error != null || snapshot == null || !snapshot.exists()) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) snapshot.getValue();
            if (data == null) {
                if (snapshot.getKey() != null) firestoreManager.notificationActionQueueRef.child(snapshot.getKey()).removeValue();
                return;
            }
            String requestId = snapshot.getKey();
            if (requestId == null || requestId.isEmpty()) return;
            processNotificationAction(requestId, data, () -> firestoreManager.notificationActionQueueRef.child(requestId).removeValue());
        });
    }

    private void processNotificationAction(String requestId, Map<String, Object> data, Runnable cleanup) {
        if (processingActionIds.contains(requestId)) return;

        String action = str(data, "action");
        String sbnKey = str(data, "sbnKey");
        if (action.isEmpty() || sbnKey.isEmpty()) {
            cleanup.run();
            return;
        }

        processingActionIds.add(requestId);
        processingAction = true;
        Log.d(TAG, "Received action: " + action + " for key: " + sbnKey);

        try {
            switch (action) {
                case "dismiss":
                    handleDismiss(sbnKey);
                    break;
                case "reply":
                    handleReply(sbnKey, str(data, "replyText"));
                    break;
                case "action":
                    Long idx = asLong(data.get("actionIndex"));
                    handleActionButton(sbnKey, idx != null ? idx.intValue() : 0);
                    break;
            }
        } finally {
            cleanup.run();
            processingActionIds.remove(requestId);
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> processingAction = false, 250);
        }
    }

    // ── onNotificationPosted ──────────────────────────────────────────────────

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();

        // Skip own package
        if (pkg.equals(getPackageName())) return;

        // Skip system/infrastructure packages
        for (String skip : SKIP_PACKAGES) {
            if (pkg.equals(skip) || pkg.startsWith(skip + ".")) return;
        }

        Notification notif = sbn.getNotification();
        if (notif == null) return;

        // ── Extract content ──────────────────────────────────────────────────
        Bundle extras = notif.extras;
        if (extras == null) extras = new Bundle();
        String title    = csStr(extras, Notification.EXTRA_TITLE);
        String text     = csStr(extras, Notification.EXTRA_TEXT);
        String bigText  = csStr(extras, Notification.EXTRA_BIG_TEXT);
        String subtext  = csStr(extras, Notification.EXTRA_SUB_TEXT);
        String inboxSummary = csStr(extras, Notification.EXTRA_SUMMARY_TEXT);

        // Prefer big text (more complete), fall back through alternatives
        String content = !bigText.isEmpty() ? bigText
                       : !text.isEmpty()    ? text
                       : !subtext.isEmpty() ? subtext
                       : inboxSummary;

        if (title.isEmpty() && content.isEmpty()) return; // nothing useful

        boolean isMediaNotification = isMediaNotification(pkg, notif, extras, title, content);

        // Skip ongoing / foreground-service notifications unless they are media controls.
        if (!isMediaNotification) {
            if ((notif.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
            if ((notif.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) return;
            if ((notif.flags & Notification.FLAG_NO_CLEAR) != 0) return;
        }

        // Track SBN so we can reply/dismiss later
        if (activeSBNs.size() > 200) {
            activeSBNs.remove(activeSBNs.keySet().iterator().next());
        }
        activeSBNs.put(sbn.getKey(), sbn);

        // ── Extract actions ──────────────────────────────────────────────────
        List<String> actionLabels = new ArrayList<>();
        boolean hasReply = false;

        if (notif.actions != null) {
            for (Notification.Action act : notif.actions) {
                if (act.title != null) {
                    actionLabels.add(act.title.toString());
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                    RemoteInput[] ri = act.getRemoteInputs();
                    if (ri != null && ri.length > 0) hasReply = true;
                }
            }
        }

        // ── Build Firestore document ─────────────────────────────────────────
        String key = buildNotificationKey(sbn);
        Map<String, Object> data = new HashMap<>();
        data.put("id",        sbn.getId());
        data.put("appname",   pkg);
        data.put("notificationKey", key);
        data.put("headline",  title);
        data.put("content",   content);
        data.put("subtext",   subtext);
        data.put("actions",   actionLabels);
        data.put("hasReply",  hasReply);
        data.put("sbnKey",    sbn.getKey());
        data.put("timestamp", sbn.getPostTime());
        data.put("isMusic",   isMediaNotification);
        data.put("category",  notif.category);

        if (firestoreManager != null) {
            firestoreManager.writeNotification(key, data);
            if (isMediaNotification) {
                Map<String, Object> musicUpdate = new HashMap<>();
                String musicTitle = !title.isEmpty() ? title : content;
                if (!musicTitle.isEmpty()) {
                    musicUpdate.put("music_title", musicTitle);
                }
                if (!pkg.isEmpty()) {
                    musicUpdate.put("music_package", pkg);
                }
                if (!musicUpdate.isEmpty()) {
                    firestoreManager.updateDeviceControls(musicUpdate);
                }
            }
        }
        LocalNetworkServer.broadcast("notification_add", data);
        Log.d(TAG, "Notification posted: " + pkg + " | " + title);
    }

    // ── onNotificationRemoved ─────────────────────────────────────────────────

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        activeSBNs.remove(sbn.getKey());
        String key = buildNotificationKey(sbn);
        if (firestoreManager != null) {
            firestoreManager.removeNotification(key);
        }
        Map<String, Object> removeData = new HashMap<>();
        removeData.put("id", key);
        LocalNetworkServer.broadcast("notification_remove", removeData);
        Log.d(TAG, "Notification removed: " + sbn.getPackageName());
    }

    // ── Action handlers ───────────────────────────────────────────────────────

    private void handleDismiss(String sbnKey) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cancelNotification(sbnKey);
            Log.d(TAG, "Dismissed: " + sbnKey);
        }
    }

    private void handleReply(String sbnKey, String replyText) {
        if (replyText.isEmpty()) return;
        StatusBarNotification sbn = activeSBNs.get(sbnKey);
        if (sbn == null) { Log.w(TAG, "SBN not found for reply: " + sbnKey); return; }

        Notification.Action replyAction = findReplyAction(sbn.getNotification());
        if (replyAction == null) { Log.w(TAG, "No reply action found"); return; }

        RemoteInput[] remoteInputs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH
                ? replyAction.getRemoteInputs() : null;
        if (remoteInputs == null || remoteInputs.length == 0) return;

        Intent intent = new Intent();
        Bundle bundle = new Bundle();
        for (RemoteInput ri : remoteInputs) {
            bundle.putCharSequence(ri.getResultKey(), replyText);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            RemoteInput.addResultsToIntent(remoteInputs, intent, bundle);
        }

        try {
            replyAction.actionIntent.send(this, 0, intent);
            Log.d(TAG, "Reply sent: " + replyText);
        } catch (PendingIntent.CanceledException e) {
            Log.e(TAG, "Reply PendingIntent cancelled", e);
        }
    }

    private void handleActionButton(String sbnKey, int index) {
        StatusBarNotification sbn = activeSBNs.get(sbnKey);
        if (sbn == null) { Log.w(TAG, "SBN not found for action: " + sbnKey); return; }

        Notification.Action[] actions = sbn.getNotification().actions;
        if (actions == null || index >= actions.length) {
            Log.w(TAG, "Action index out of range: " + index);
            return;
        }
        try {
            actions[index].actionIntent.send();
            Log.d(TAG, "Action button tapped: index=" + index);
        } catch (PendingIntent.CanceledException e) {
            Log.e(TAG, "Action PendingIntent cancelled", e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Notification.Action findReplyAction(Notification notification) {
        if (notification.actions == null) return null;
        for (Notification.Action act : notification.actions) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                RemoteInput[] ri = act.getRemoteInputs();
                if (ri != null && ri.length > 0) return act;
            }
        }
        return null;
    }

    private static String str(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "";
    }

    private static String csStr(Bundle extras, String key) {
        CharSequence cs = extras.getCharSequence(key);
        return cs != null ? cs.toString() : "";
    }

    private boolean isMediaNotification(String pkg, Notification notif, Bundle extras, String title, String content) {
        if (notif == null) return false;
        if (Notification.CATEGORY_TRANSPORT.equals(notif.category)) return true;
        if (extras != null && extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) != null) return true;

        String haystack = ((pkg != null ? pkg : "") + " " + (title != null ? title : "") + " " + (content != null ? content : ""))
                .toLowerCase(Locale.US);
        String[] keywords = {
                "spotify", "youtube music", "youtube", "pandora", "tidal",
                "deezer", "soundcloud", "apple music", "music", "media"
        };
        for (String keyword : keywords) {
            if (haystack.contains(keyword)) return true;
        }
        return false;
    }

    private static Long asLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String buildNotificationKey(StatusBarNotification sbn) {
        String rawKey = sbn.getKey() != null ? sbn.getKey() : "";
        String safeKey = rawKey.replaceAll("[.#$\\[\\]/]", "_");
        String safePackage = sbn.getPackageName() != null
                ? sbn.getPackageName().replaceAll("[.#$\\[\\]/]", "_")
                : "";
        return safePackage + "_" + sbn.getId() + "_" + sbn.getPostTime() + "_" + safeKey;
    }
}
