package com.dialingassistant.agent;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.messaging.FirebaseMessaging;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lite foreground service: handles only Call and SMS actions.
 *
 * Responsibilities:
 *   • RTDB listeners for web→device commands: state/info (dial/hangup) and
 *     state/call_status (answer/decline).
 *   • Phone-state tracking → device→web state/call_state mirror + call-log fallback
 *     for resolving the outgoing number on Android 10+.
 *   • SMS history sync + send (SmsSyncManager) and call-history sync (CallHistorySync).
 *   • In-call volume + remote music control via state/device_controls.
 *
 * WiFi/BT/screen control and the LAN server from the full app are intentionally absent.
 */
public class CallBackgroundService extends Service {

    private static final String TAG = "CallBgService";
    private static final String CHANNEL_ID = "ForegroundServiceChannel";
    private static final String CALL_CHANNEL_ID = "CallTriggerChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final int CALL_NOTIFICATION_ID = 99;

    private static final String PREFS_NAME = "call_bg_prefs";
    private static final String PREF_LAST_DIAL_CMD_ID = "last_dial_cmd_id";
    private static final String PREF_LAST_ROUTE_CMD_ID = "last_route_cmd_id";
    private static final String PREF_LAST_MUSIC_PACKAGE = "last_music_package";
    private static final int MUSIC_INIT_RETRY_DELAY_MS = 1500;
    private static final int MUSIC_INIT_MAX_ATTEMPTS = 10;
    private static final int MUSIC_INIT_INITIAL_DELAY_MS = 2500;

    private FirestoreManager firestoreManager;

    private ListenerRegistration infoListener;
    private ListenerRegistration callStatusListener;
    private ListenerRegistration deviceControlsListener;

    private SmsSyncManager smsSyncManager;
    private CallHistorySync callHistorySync;
    private AudioManager audioManager;
    private PowerManager.WakeLock wakeLock;

    private String lastHandledDialCmdId = "";
    private String lastHandledRouteCmdId = "";
    private String lastPhoneState = TelephonyManager.EXTRA_STATE_IDLE;
    private boolean callWasInProgress = false;
    private String lastDialedNumber = "";
    private String lastIncomingNumber = "";
    // Call waiting: a second call ringing while one is already offhook.
    // TelephonyManager only exposes the aggregate state, so this is the one
    // extra piece of context we can track without being the default dialer.
    private String waitingCallNumber = "";
    private boolean acceptWaitingIssued = false;
    private boolean callStatusInitialized = false;
    private int pendingDialSimSlot = -1;
    private boolean initialized = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingCallLogRunnable;
    private int pendingCallLogRetries = 0;
    private ContentObserver volumeObserver;

    private MediaSessionManager mediaSessionManager;
    private ComponentName notificationListenerComponent;
    private final List<MediaController> trackedMediaControllers = new ArrayList<>();
    private final List<MediaController.Callback> trackedMediaCallbacks = new ArrayList<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener activeSessionsChangedListener = controllers ->
            refreshMediaControllers(controllers);
    private boolean activeSessionsListenerRegistered = false;
    private String lastKnownMusicPackage = "";
    private String pendingMusicLaunchPackage = "";
    private int pendingMusicLaunchAttempts = 0;
    private String lastPublishedMusicTitle = "";
    private String lastPublishedMusicSignature = "";
    private final Runnable musicInitRetryRunnable = new Runnable() {
        @Override
        public void run() {
            runMusicInitializationAttempt();
        }
    };
    private final Runnable mediaStatePollRunnable = new Runnable() {
        @Override
        public void run() {
            publishMusicState();
            scheduleMusicStatePoll();
        }
    };

    private final BroadcastReceiver phoneStateSyncReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handlePhoneStateChanged(intent);
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundWithNotification();

        if (intent == null) {
            Log.d(TAG, "onStartCommand: null intent (service restarted by system)");
        }

        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            Log.e(TAG, "No authenticated user — stopping service");
            stopSelf();
            return START_NOT_STICKY;
        }

        firestoreManager = new FirestoreManager(currentUser.getUid());
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        lastHandledDialCmdId = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_LAST_DIAL_CMD_ID, "");
        lastHandledRouteCmdId = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_LAST_ROUTE_CMD_ID, "");
        lastKnownMusicPackage = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_LAST_MUSIC_PACKAGE, "");

        if (!initialized) {
            initialized = true;
            // Reset stale call state on service (re)start
            callWasInProgress = false;
            lastPhoneState = TelephonyManager.EXTRA_STATE_IDLE;
            lastDialedNumber = "";
            lastIncomingNumber = "";
            waitingCallNumber = "";
            acceptWaitingIssued = false;
            callStatusInitialized = false;

            startFirestoreListeners();

            // CallReceiver is manifest-registered only. Registering the same receiver
            // class here too made every RINGING broadcast run onReceive twice —
            // duplicate incoming_call_status writes and duplicate web-push POSTs.
            IntentFilter phoneFilter = new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
            registerReceiver(phoneStateSyncReceiver, phoneFilter);

            smsSyncManager = new SmsSyncManager(this, firestoreManager);
            smsSyncManager.register();

            callHistorySync = new CallHistorySync(this, firestoreManager);
            callHistorySync.register();

            // No perpetual PARTIAL_WAKE_LOCK here: Doze ignores wakelocks, so it never kept
            // the websocket alive while locked — it only drained battery and auto-expired
            // after an hour. Prompt wake-from-Doze is now handled by high-priority FCM
            // (FcmService) instead. A scoped screen wakelock is still used per call elsewhere.

            // Register this device's FCM token so the web app / extension can wake it on dial.
            registerFcmTokenForWake();

            // Seed call-volume state so the web has valid data before the first adjustment.
            publishCallVolumeState();

            // Mirror any system volume changes (hardware buttons, other apps) in real time.
            registerVolumeObserver();

            // Initialize media session tracking for music control
            initMediaSessionTracking();
            primeMusicPlaybackSession();

            Log.d(TAG, "Service listeners initialized");
        } else {
            Log.d(TAG, "onStartCommand: service already initialized");
            // If the service started before Notification Access was granted, the earlier
            // MediaSessionManager call only had the AudioManager fallback. MainActivity and
            // NotificationListener both start the service again after the user returns/grants
            // access, so re-check active sessions here without requiring a full stop/start.
            refreshMediaSessionAccess();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (pendingCallLogRunnable != null) mainHandler.removeCallbacks(pendingCallLogRunnable);
        cancelMusicInitialization();
        removeFirestoreListeners();
        if (smsSyncManager != null) smsSyncManager.unregister();
        if (callHistorySync != null) callHistorySync.unregister();
        clearMediaSessionTracking();
        if (volumeObserver != null) {
            try { getContentResolver().unregisterContentObserver(volumeObserver); } catch (Exception ignored) {}
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        try { unregisterReceiver(phoneStateSyncReceiver); } catch (Exception ignored) {}
        initialized = false;
        Log.d(TAG, "Service destroyed");
    }

    // ── Foreground notification ──────────────────────────────────────────────

    private void startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Dialing Assistant Service", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Dialing Assistant")
                .setContentText("Service running")
                .setSmallIcon(R.drawable.ic_launcher_background)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    // ── RTDB listeners ────────────────────────────────────────────────────────

    private void startFirestoreListeners() {
        // info listener → dial/hang-up commands
        infoListener = firestoreManager.listenToInfo((snapshot, error) -> {
            if (error != null) { Log.e(TAG, "info listener error: " + error); return; }
            if (snapshot != null && snapshot.exists()) {
                readInfoData(snapshot);
            }
        });

        // call_status listener → answer (1) or disconnect (0) from web app
        callStatusListener = firestoreManager.listenToCallStatus((snapshot, error) -> {
            if (error != null || snapshot == null || !snapshot.exists()) return;
            Long answer = asLong(snapshot.child("answer").getValue());
            if (answer == null) return;
            if (!callStatusInitialized) {
                // A value listener replays the persisted node on startup. That replay is
                // not a new command and must never answer a future incoming call.
                callStatusInitialized = true;
                if (answer == 1 || answer == 0) firestoreManager.updateCallStatus(2);
                return;
            }
            Log.d(TAG, "call_status answer=" + answer);
            if (answer == 1) {
                if (isSomethingRinging()) answerCall();
                else Log.d(TAG, "answer=1 ignored — no ringing or waiting call");
            } else if (answer == 0) {
                // answer=0 means DECLINE THE RINGING CALL — it is not the hang-up
                // channel (that's info.status='0'). If nothing is ringing the command
                // is stale (a client that dismissed its ring UI late, e.g. iOS CallKit
                // timing out its mimic), and endCall() would drop the connected call
                // instead. Ignore it, but still reset the field below.
                if (isSomethingRinging()) {
                    disconnectCall();
                } else {
                    Log.d(TAG, "call_status answer=0 ignored — no ringing call to decline");
                }
            } else {
                // answer==2 is the idle reset — ignore.
                return;
            }
            // Clear the command after acting on it. The browsers also schedule a
            // delayed conditional reset, but if they close before it fires a stale
            // 1/0 would auto-answer or auto-reject the NEXT incoming call (see
            // CallReceiver's ring-time read). The device is the reliable resetter.
            firestoreManager.updateCallStatus(2);
        });

        // device_controls listener → in-call volume command (call_volume_cmd)
        deviceControlsListener = firestoreManager.listenToDeviceControls((snapshot, error) -> {
            if (error != null || snapshot == null || !snapshot.exists()) return;
            readDeviceControls(snapshot);
        });
    }

    private void removeFirestoreListeners() {
        if (infoListener != null) infoListener.remove();
        if (callStatusListener != null) callStatusListener.remove();
        if (deviceControlsListener != null) deviceControlsListener.remove();
    }

    /**
     * Fetches the current FCM registration token and stores it under the agent so the web
     * app / chrome-extension can send a high-priority wake message alongside each dial.
     */
    private void registerFcmTokenForWake() {
        try {
            FirebaseMessaging.getInstance().getToken()
                    .addOnSuccessListener(token -> {
                        if (firestoreManager != null && token != null && !token.isEmpty()) {
                            firestoreManager.registerFcmToken(token);
                            Log.d(TAG, "FCM token registered for wake");
                        }
                    })
                    .addOnFailureListener(e -> Log.w(TAG, "getToken failed: " + e.getMessage()));
        } catch (Exception e) {
            Log.w(TAG, "registerFcmTokenForWake error: " + e.getMessage());
        }
    }

    // ── Device controls (in-call volume + music) ────────────────────────────────

    private void readDeviceControls(DataSnapshot snapshot) {
        Map<String, Object> map = toMap(snapshot);
        if (map == null) return;

        // call_volume_cmd: 1 = raise, 0 = lower. Self-clearing command field.
        Object callVolumeCmdObj = map.get("call_volume_cmd");
        if (callVolumeCmdObj instanceof Number) {
            int val = ((Number) callVolumeCmdObj).intValue();
            handleCallVolumeCommand(val);
            firestoreManager.clearDeviceControlField("call_volume_cmd");
        }

        Object musicPackageObj = map.get("music_package");
        if (musicPackageObj != null) {
            String packageName = musicPackageObj.toString().trim();
            if (!packageName.isEmpty()) {
                lastKnownMusicPackage = packageName;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(PREF_LAST_MUSIC_PACKAGE, packageName)
                        .apply();
            }
        }

        // music_cmd: 0=prev, 1=play/pause toggle, 2=next, 3=vol down, 4=vol up, 5=play, 6=pause
        Object musicCmdObj = map.get("music_cmd");
        if (musicCmdObj instanceof Number) {
            int val = ((Number) musicCmdObj).intValue();
            handleMusicCommand(val);
            firestoreManager.clearDeviceControlField("music_cmd");
        }

        // mirror_req: web requests this device's current LAN IP so the operator's
        // desktop helper can `adb connect <ip>:5555` and launch scrcpy. Self-clearing.
        // We publish a fresh IP on every request because DHCP leases change.
        Object mirrorReqObj = map.get("mirror_req");
        if (mirrorReqObj instanceof Number) {
            handleMirrorRequest();
            firestoreManager.clearDeviceControlField("mirror_req");
        }

        // route_cmd_id + route_number: web "Route" command → dial a second call and,
        // once the routed party answers, auto-merge the two into a conference. Unlike
        // the other device_controls commands, this places a real call, so it is guarded
        // by a cmdId (persisted, like the dial path) — the keepSynced value listener
        // re-delivers this node on every reconnect and we must not re-dial. Both fields
        // self-clear afterwards so a redelivery finds nothing to act on.
        Object routeCmdIdObj = map.get("route_cmd_id");
        if (routeCmdIdObj != null) {
            String routeCmdId = routeCmdIdObj.toString().trim();
            String routeNumber = asString(map.get("route_number")).trim();
            if (!routeCmdId.isEmpty() && !routeCmdId.equals(lastHandledRouteCmdId)
                    && !routeNumber.isEmpty()) {
                lastHandledRouteCmdId = routeCmdId;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(PREF_LAST_ROUTE_CMD_ID, routeCmdId).apply();
                // route_wait_answer (iOS house-first): defer the second leg until the
                // first call connects. Absent/false for web Route (call already active).
                Object waitObj = map.get("route_wait_answer");
                boolean waitForAnswer = (waitObj instanceof Boolean && (Boolean) waitObj)
                        || (waitObj != null && "true".equalsIgnoreCase(waitObj.toString()));
                handleRouteCommand(routeNumber, waitForAnswer);
            }
            firestoreManager.clearDeviceControlField("route_cmd_id");
            firestoreManager.clearDeviceControlField("route_number");
            firestoreManager.clearDeviceControlField("route_wait_answer");
        }
    }

    /**
     * Call routing. Places a second call to {@code number} — the OS automatically puts
     * the current active call on hold — and arms {@link MergeCallAccessibilityService},
     * which taps the system "Merge" button once the routed party answers, joining both
     * calls into a conference.
     *
     * The merge itself must be an accessibility tap: this app is not the default dialer,
     * so it cannot call {@code android.telecom.Call.conference()} through TelecomManager.
     * If the user has not enabled the accessibility service we still place the call (so
     * the operator can merge by hand on the phone) and report that back to the web.
     */
    private void handleRouteCommand(String number, boolean waitForAnswer) {
        final String normalized = number != null ? number.trim() : "";
        if (normalized.isEmpty()) {
            Log.w(TAG, "handleRouteCommand: empty number");
            return;
        }

        if (waitForAnswer) {
            // House-first (iOS): the active call (the house number) may still be ringing.
            // Defer placing the target as the second leg until the accessibility watcher
            // confirms the first call connected, then merge. If a11y is off we can't detect
            // connect, so place immediately (best-effort, operator merges by hand).
            Log.d(TAG, "route(house-first): waiting for first leg to connect before dialing " + normalized);
            boolean armed = MergeCallAccessibilityService.requestMergeAfterConnect(() -> {
                pendingDialSimSlot = -1;
                makePhoneCall(normalized);
            });
            if (!armed) {
                if (firestoreManager != null) {
                    firestoreManager.updateDeviceControlField("route_status", "no_accessibility");
                }
                Log.w(TAG, "route(house-first): accessibility off — placing immediately without connect-gate");
                pendingDialSimSlot = -1;
                makePhoneCall(normalized);
            }
            return;
        }

        Log.d(TAG, "route: dialing second leg " + normalized);

        // Arm the merge watcher first so it is already listening when the second call
        // connects and the in-call UI exposes the merge control.
        boolean armed = MergeCallAccessibilityService.requestMerge();
        if (!armed && firestoreManager != null) {
            Log.w(TAG, "route: merge accessibility service not enabled — placing call without auto-merge");
            firestoreManager.updateDeviceControlField("route_status", "no_accessibility");
        }

        // Route on the SIM already carrying the active call (system default); a dedicated
        // SIM selector can be added later if needed.
        pendingDialSimSlot = -1;
        makePhoneCall(normalized);
    }

    /**
     * Resolves the device's current LAN IPv4 and publishes it under
     * device_controls/{mirror_ip, mirror_ip_ts} for the web app to hand to the
     * desktop scrcpy helper. Note: scrcpy/adb run on the operator's computer — the
     * phone only reports where it can be reached. ADB-over-TCP must have been enabled
     * once via USB (`adb tcpip 5555`); the app cannot enable it without root.
     */
    private void handleMirrorRequest() {
        if (firestoreManager == null) return;
        String ip = getLocalIpAddress();
        Map<String, Object> update = new HashMap<>();
        update.put("mirror_ip", ip != null ? ip : "");
        update.put("mirror_ip_ts", System.currentTimeMillis());
        firestoreManager.updateDeviceControls(update);
        Log.d(TAG, "mirror_req → published mirror_ip=" + ip);
    }

    /** Best LAN IPv4 (site-local, non-loopback) without needing ACCESS_WIFI_STATE. */
    private static String getLocalIpAddress() {
        try {
            String fallback = null;
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface nif = ifaces.nextElement();
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr.isLoopbackAddress() || !(addr instanceof Inet4Address)) continue;
                    String host = addr.getHostAddress();
                    if (addr.isSiteLocalAddress()) return host; // e.g. 192.168.x / 10.x — the WiFi/LAN IP
                    if (fallback == null) fallback = host;
                }
            }
            return fallback;
        } catch (Exception e) {
            Log.e(TAG, "getLocalIpAddress failed: " + e.getMessage());
            return null;
        }
    }

    private void handleCallVolumeCommand(int value) {
        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        if (audioManager == null) {
            Log.w(TAG, "handleCallVolumeCommand: audioManager unavailable");
            return;
        }
        int direction = value == 1 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
        audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, direction, AudioManager.FLAG_SHOW_UI);
        publishCallVolumeState();
        Log.d(TAG, "call_volume_cmd: " + value + " direction=" + direction);
    }

    private void publishCallVolumeState() {
        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        if (audioManager == null || firestoreManager == null) return;
        int maxVoice = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
        int curVoice = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
        int maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int curMusic = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        Map<String, Object> update = new HashMap<>();
        update.put("volume-level", (maxVoice > 0) ? (int) Math.round((curVoice / (double) maxVoice) * 10) : 0);
        update.put("call_volume", (maxVoice > 0) ? (int) Math.round((curVoice / (double) maxVoice) * 100) : 0);
        update.put("music_volume", (maxMusic > 0) ? (int) Math.round((curMusic / (double) maxMusic) * 100) : 0);
        update.put("speaker", audioManager.isSpeakerphoneOn() ? 1 : 0);
        firestoreManager.updateDeviceControls(update);
    }

    // ── Volume observer ───────────────────────────────────────────────────────

    private void registerVolumeObserver() {
        volumeObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                publishCallVolumeState();
                publishMusicState();
            }
        };
        getContentResolver().registerContentObserver(
                android.provider.Settings.System.CONTENT_URI, true, volumeObserver);
    }

    // ── Music controls ────────────────────────────────────────────────────────

    private void handleMusicCommand(int value) {
        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        if (audioManager == null) {
            Log.w(TAG, "handleMusicCommand: audioManager unavailable");
            return;
        }

        switch (value) {
            case 0:
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                Log.d(TAG, "music_cmd: previous");
                break;
            case 2:
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
                Log.d(TAG, "music_cmd: next");
                break;
            case 3:
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                Log.d(TAG, "music_cmd: volume down");
                publishMusicState();
                return;
            case 4:
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                Log.d(TAG, "music_cmd: volume up");
                publishMusicState();
                return;
            case 5:
                handleMediaPlayRequest();
                break;
            case 6:
                handleMediaPauseRequest();
                break;
            case 1:
            default:
                handleMediaToggleRequest();
                break;
        }
        publishMusicState();
        mainHandler.removeCallbacks(mediaStatePollRunnable);
        mainHandler.postDelayed(mediaStatePollRunnable, 500);
    }

    private void dispatchMediaKey(int keyCode) {
        long eventTime = android.os.SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0);
        KeyEvent up = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0);
        audioManager.dispatchMediaKeyEvent(down);
        audioManager.dispatchMediaKeyEvent(up);
    }

    private void handleMediaPlayRequest() {
        MediaController controller = getPreferredMusicController();
        int state = PlaybackState.STATE_NONE;
        String packageName = resolveMusicLaunchPackage(lastKnownMusicPackage);
        if (controller != null) {
            PlaybackState playbackState = controller.getPlaybackState();
            state = playbackState != null ? playbackState.getState() : PlaybackState.STATE_NONE;
            if (controller.getPackageName() != null && !controller.getPackageName().isEmpty()) {
                packageName = resolveMusicLaunchPackage(controller.getPackageName());
            }
        }

        if (controller != null && (state == PlaybackState.STATE_PLAYING
                || state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_FAST_FORWARDING
                || state == PlaybackState.STATE_REWINDING
                || state == PlaybackState.STATE_PAUSED)) {
            try {
                controller.getTransportControls().play();
                Log.d(TAG, "music_cmd: play via active session");
                return;
            } catch (Exception e) {
                Log.w(TAG, "play transport controls failed: " + e.getMessage());
            }
        }

        beginMusicInitialization(packageName);
    }

    private void handleMediaPauseRequest() {
        cancelMusicInitialization();
        MediaController controller = getPreferredMusicController();
        if (controller != null) {
            try {
                controller.getTransportControls().pause();
                Log.d(TAG, "music_cmd: pause via active session");
                return;
            } catch (Exception e) {
                Log.w(TAG, "pause transport controls failed: " + e.getMessage());
            }
        }
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
        Log.d(TAG, "music_cmd: pause via media key");
    }

    private void handleMediaToggleRequest() {
        MediaController controller = getPreferredMusicController();
        if (controller != null) {
            PlaybackState playbackState = controller.getPlaybackState();
            int state = playbackState != null ? playbackState.getState() : PlaybackState.STATE_NONE;
            if (state == PlaybackState.STATE_PLAYING
                    || state == PlaybackState.STATE_BUFFERING
                    || state == PlaybackState.STATE_FAST_FORWARDING
                    || state == PlaybackState.STATE_REWINDING) {
                handleMediaPauseRequest();
                return;
            }
            handleMediaPlayRequest();
            return;
        }
        handleMediaPlayRequest();
    }

    private void initMediaSessionTracking() {
        mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (mediaSessionManager == null) {
            Log.w(TAG, "initMediaSessionTracking: mediaSessionManager unavailable");
            publishMusicState();
            return;
        }
        if (notificationListenerComponent == null) {
            notificationListenerComponent = new ComponentName(this, NotificationListener.class);
        }
        if (!activeSessionsListenerRegistered) {
            try {
                mediaSessionManager.addOnActiveSessionsChangedListener(
                        activeSessionsChangedListener, notificationListenerComponent, mainHandler);
                activeSessionsListenerRegistered = true;
            } catch (Exception e) {
                Log.w(TAG, "Failed to register active sessions listener: " + e.getMessage());
            }
        }
        refreshMediaSessionAccess();
    }

    private void refreshMediaSessionAccess() {
        if (mediaSessionManager == null || notificationListenerComponent == null) {
            initMediaSessionTracking();
            return;
        }
        if (!activeSessionsListenerRegistered) {
            try {
                mediaSessionManager.addOnActiveSessionsChangedListener(
                        activeSessionsChangedListener, notificationListenerComponent, mainHandler);
                activeSessionsListenerRegistered = true;
            } catch (Exception e) {
                Log.w(TAG, "Failed to register active sessions listener: " + e.getMessage());
            }
        }
        try {
            refreshMediaControllers(mediaSessionManager.getActiveSessions(notificationListenerComponent));
        } catch (SecurityException e) {
            Log.w(TAG, "getActiveSessions denied — grant Notification Access in Settings: " + e.getMessage());
            primeMusicPlaybackSession();
        } catch (Exception e) {
            Log.w(TAG, "refreshMediaSessionAccess failed: " + e.getMessage());
            primeMusicPlaybackSession();
        }
    }

    private void primeMusicPlaybackSession() {
        publishMusicState();
        scheduleMusicStatePoll();
    }

    private void cancelMusicInitialization() {
        mainHandler.removeCallbacks(musicInitRetryRunnable);
        pendingMusicLaunchPackage = "";
        pendingMusicLaunchAttempts = 0;
    }

    private void beginMusicInitialization(String packageName) {
        cancelMusicInitialization();
        pendingMusicLaunchPackage = resolveMusicLaunchPackage(packageName);
        pendingMusicLaunchAttempts = 0;
        if (!pendingMusicLaunchPackage.isEmpty()) {
            launchMusicApp(pendingMusicLaunchPackage);
        }
        mainHandler.postDelayed(musicInitRetryRunnable, MUSIC_INIT_INITIAL_DELAY_MS);
    }

    private void runMusicInitializationAttempt() {
        if (!initialized) {
            cancelMusicInitialization();
            return;
        }

        MediaController controller = getPreferredMusicController();
        if (controller != null) {
            try {
                PlaybackState playbackState = controller.getPlaybackState();
                int state = playbackState != null ? playbackState.getState() : PlaybackState.STATE_NONE;
                if (state == PlaybackState.STATE_PLAYING
                        || state == PlaybackState.STATE_BUFFERING
                        || state == PlaybackState.STATE_FAST_FORWARDING
                        || state == PlaybackState.STATE_REWINDING
                        || state == PlaybackState.STATE_PAUSED) {
                    controller.getTransportControls().play();
                    Log.d(TAG, "music_cmd: play via warm controller");
                    cancelMusicInitialization();
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "warm controller play failed: " + e.getMessage());
            }
        }

        if (!pendingMusicLaunchPackage.isEmpty()
                && (pendingMusicLaunchAttempts == 0 || pendingMusicLaunchAttempts % 3 == 0)) {
            launchMusicApp(pendingMusicLaunchPackage);
        }

        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
        pendingMusicLaunchAttempts++;

        if (pendingMusicLaunchAttempts < MUSIC_INIT_MAX_ATTEMPTS) {
            mainHandler.postDelayed(musicInitRetryRunnable, MUSIC_INIT_RETRY_DELAY_MS);
        } else {
            Log.w(TAG, "music_cmd: play warm-up exhausted after " + pendingMusicLaunchAttempts + " attempts");
            cancelMusicInitialization();
        }
    }

    private String resolveMusicLaunchPackage(String packageName) {
        if (packageName != null && !packageName.isEmpty()) {
            return packageName;
        }
        if ("com.google.android.apps.youtube.music".equals(lastKnownMusicPackage)) {
            return lastKnownMusicPackage;
        }
        return "com.google.android.apps.youtube.music";
    }

    private List<String> buildMusicLaunchCandidates(String requestedPackage) {
        List<String> candidates = new ArrayList<>();
        candidates.add("com.google.android.apps.youtube.music");
        if (requestedPackage != null && !requestedPackage.isEmpty()
                && !candidates.contains(requestedPackage)) {
            candidates.add(requestedPackage);
        }
        if (lastKnownMusicPackage != null && !lastKnownMusicPackage.isEmpty()
                && !candidates.contains(lastKnownMusicPackage)) {
            candidates.add(lastKnownMusicPackage);
        }
        String[] defaults = new String[]{
                "com.spotify.music",
                "com.apple.android.music",
                "deezer.android.app",
                "com.soundcloud.android",
                "com.tidal.music",
                "com.amazon.mp3"
        };
        for (String candidate : defaults) {
            if (!candidates.contains(candidate)) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    private void clearMediaSessionTracking() {
        mainHandler.removeCallbacks(mediaStatePollRunnable);
        if (mediaSessionManager != null && notificationListenerComponent != null && activeSessionsListenerRegistered) {
            try {
                mediaSessionManager.removeOnActiveSessionsChangedListener(activeSessionsChangedListener);
            } catch (Exception ignored) {}
        }
        activeSessionsListenerRegistered = false;
        for (int i = 0; i < trackedMediaControllers.size(); i++) {
            try {
                trackedMediaControllers.get(i).unregisterCallback(trackedMediaCallbacks.get(i));
            } catch (Exception ignored) {}
        }
        trackedMediaControllers.clear();
        trackedMediaCallbacks.clear();
    }

    private void refreshMediaControllers(List<MediaController> controllers) {
        for (int i = 0; i < trackedMediaControllers.size(); i++) {
            try {
                trackedMediaControllers.get(i).unregisterCallback(trackedMediaCallbacks.get(i));
            } catch (Exception ignored) {}
        }
        trackedMediaControllers.clear();
        trackedMediaCallbacks.clear();

        if (controllers != null) {
            for (MediaController controller : controllers) {
                if (controller == null) continue;
                MediaController.Callback callback = new MediaController.Callback() {
                    @Override
                    public void onPlaybackStateChanged(PlaybackState state) {
                        publishMusicState();
                    }

                    @Override
                    public void onMetadataChanged(MediaMetadata metadata) {
                        publishMusicState();
                    }

                    @Override
                    public void onSessionDestroyed() {
                        publishMusicState();
                    }
                };
                try {
                    controller.registerCallback(callback, mainHandler);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to register media callback: " + e.getMessage());
                }
                trackedMediaControllers.add(controller);
                trackedMediaCallbacks.add(callback);
            }
        }
        publishMusicState();
        scheduleMusicStatePoll();
    }

    private MediaController getPreferredMusicController() {
        MediaController pausedCandidate = null;
        MediaController anyCandidate = null;
        for (MediaController controller : trackedMediaControllers) {
            if (controller == null) continue;
            if (anyCandidate == null) anyCandidate = controller;
            PlaybackState playbackState = controller.getPlaybackState();
            int state = playbackState != null ? playbackState.getState() : PlaybackState.STATE_NONE;
            if (state == PlaybackState.STATE_PLAYING
                    || state == PlaybackState.STATE_BUFFERING
                    || state == PlaybackState.STATE_FAST_FORWARDING
                    || state == PlaybackState.STATE_REWINDING) {
                return controller;
            }
            if (state == PlaybackState.STATE_PAUSED) {
                pausedCandidate = controller;
            }
        }
        if (pausedCandidate != null) return pausedCandidate;
        return anyCandidate;
    }

    private void publishMusicState() {
        String state = "idle";
        String title = "";
        String artist = "";
        String pkg = "";

        MediaController controller = getPreferredMusicController();
        if (controller != null) {
            pkg = controller.getPackageName() != null ? controller.getPackageName() : "";
            PlaybackState playbackState = controller.getPlaybackState();
            if (playbackState != null) {
                state = mapPlaybackState(playbackState.getState());
            }
            MediaMetadata metadata = controller.getMetadata();
            if (metadata != null) {
                CharSequence titleCs = metadata.getText(MediaMetadata.METADATA_KEY_TITLE);
                if (titleCs == null || titleCs.length() == 0) {
                    titleCs = metadata.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
                }
                CharSequence artistCs = metadata.getText(MediaMetadata.METADATA_KEY_ARTIST);
                if (titleCs != null) title = titleCs.toString();
                if (artistCs != null) artist = artistCs.toString();
            }
        } else if (audioManager != null) {
            // No MediaController (Notification Access not granted or no active session).
            // AudioManager.isMusicActive() works without any special permission and correctly
            // reflects play/pause for most media apps. Title/artist cannot be read without a
            // controller — user should grant Notification Access for full metadata sync.
            state = audioManager.isMusicActive() ? "playing" : "idle";
        }
        if (!pkg.isEmpty()) {
            lastKnownMusicPackage = pkg;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(PREF_LAST_MUSIC_PACKAGE, pkg)
                    .apply();
        } else if (lastKnownMusicPackage != null && !lastKnownMusicPackage.isEmpty()) {
            pkg = lastKnownMusicPackage;
        }

        int musicMaxVol = audioManager != null ? audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) : 0;
        int musicCurVol = audioManager != null ? audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) : 0;
        int musicVolume = (musicMaxVol > 0) ? (int) Math.round((musicCurVol / (double) musicMaxVol) * 100) : 0;

        if (!title.equals(lastPublishedMusicTitle)) {
            Log.d(TAG, "music_title changed: '" + lastPublishedMusicTitle + "' -> '" + title + "'");
            lastPublishedMusicTitle = title;
        }

        String signature = state + "|" + title + "|" + artist + "|" + pkg + "|" + musicVolume;
        if (signature.equals(lastPublishedMusicSignature)) {
            return;
        }
        lastPublishedMusicSignature = signature;

        Map<String, Object> update = new HashMap<>();
        update.put("music_state", state);
        update.put("music_title", title);
        update.put("music_artist", artist);
        update.put("music_package", pkg);
        update.put("music", "playing".equals(state) ? 1 : 0);
        update.put("music_volume", musicVolume);

        if (firestoreManager != null) {
            firestoreManager.updateDeviceControls(update);
        }
    }

    private boolean launchMusicApp(String packageName) {
        for (String candidate : buildMusicLaunchCandidates(packageName)) {
            if (candidate == null || candidate.isEmpty()) continue;
            try {
                Intent launchIntent = getPackageManager().getLaunchIntentForPackage(candidate);
                if (launchIntent == null) {
                    Intent queryIntent = new Intent(Intent.ACTION_MAIN);
                    queryIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                    queryIntent.setPackage(candidate);
                    ResolveInfo info = getPackageManager().resolveActivity(queryIntent, 0);
                    if (info != null && info.activityInfo != null) {
                        launchIntent = new Intent(Intent.ACTION_MAIN);
                        launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                        launchIntent.setClassName(info.activityInfo.packageName, info.activityInfo.name);
                    }
                }
                if (launchIntent == null) {
                    continue;
                }
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(launchIntent);
                Log.d(TAG, "music_cmd: launched package " + candidate);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "launchMusicApp failed for " + candidate + ": " + e.getMessage());
            }
        }
        return false;
    }

    private void scheduleMusicStatePoll() {
        mainHandler.removeCallbacks(mediaStatePollRunnable);
        // When a MediaController is tracked, its registered callbacks deliver state
        // changes — no polling needed. A continuous poll only runs in the fallback
        // case (no Notification Access → no controllers), where play/pause can only
        // be observed via AudioManager.isMusicActive(). The old behaviour of waking
        // the main thread every 500 ms whenever any session existed (even paused,
        // indefinitely) was a steady battery drain.
        if (trackedMediaControllers.isEmpty()) {
            mainHandler.postDelayed(mediaStatePollRunnable, 5000);
        }
    }

    private boolean hasActiveMusicController() {
        for (MediaController controller : trackedMediaControllers) {
            if (controller == null) continue;
            PlaybackState playbackState = controller.getPlaybackState();
            int state = playbackState != null ? playbackState.getState() : PlaybackState.STATE_NONE;
            if (state == PlaybackState.STATE_PLAYING
                    || state == PlaybackState.STATE_BUFFERING
                    || state == PlaybackState.STATE_FAST_FORWARDING
                    || state == PlaybackState.STATE_REWINDING
                    || state == PlaybackState.STATE_PAUSED) {
                return true;
            }
        }
        return false;
    }

    private String mapPlaybackState(int playbackState) {
        switch (playbackState) {
            case PlaybackState.STATE_PLAYING:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING:
                return "playing";
            case PlaybackState.STATE_PAUSED:
                return "paused";
            case PlaybackState.STATE_STOPPED:
                return "stopped";
            case PlaybackState.STATE_NONE:
            case PlaybackState.STATE_ERROR:
            default:
                return "idle";
        }
    }

    // ── Info data handler ──────────────────────────────────────────────────────

    private void readInfoData(DataSnapshot snapshot) {
        Map<String, Object> map = toMap(snapshot);
        if (map == null) return;

        String num = asString(map.get("number"));
        String status = asString(map.get("status"));
        Object simSlotObj = map.get("simSlot");
        int simSlot = (simSlotObj instanceof Number) ? ((Number) simSlotObj).intValue() : -1;

        Log.d(TAG, "info: number=" + num + " status=" + status + " simSlot=" + simSlot);
        // No syncCallHistory() here: this listener also fires for status resets
        // ('0'→'3', answer clears), and each sync is a full call-log query plus
        // ~10 RTDB writes. The call-log ContentObserver and the call-ended hook
        // already keep history fresh.

        if ("1".equals(status)) {
            // Idempotency guard. info/status is overloaded: it is both the web→device
            // dial command AND the web's "call ended" signal (web treats status!='1' as
            // ended), so we can't reset it here. Instead the web stamps each dial with a
            // unique cmdId; we execute a given cmdId only once. Without this, the RTDB
            // value-listener re-delivers the last command on every service (re)start or
            // reconnect — re-dialing the same number and producing duplicate/ghost calls.
            String cmdId = asString(map.get("cmdId"));
            if (!cmdId.isEmpty() && cmdId.equals(lastHandledDialCmdId)) {
                Log.d(TAG, "info: dial cmd " + cmdId + " already handled — skipping re-dial");
                return;
            }
            if (!cmdId.isEmpty()) {
                lastHandledDialCmdId = cmdId;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(PREF_LAST_DIAL_CMD_ID, cmdId).apply();
            }
            lastDialedNumber = num;
            pendingDialSimSlot = simSlot;
            makePhoneCall(num);
        } else if ("0".equals(status)) {
            disconnectCall();
        }
    }

    // ── Phone state sync ──────────────────────────────────────────────────────

    /**
     * Queries the call log for the most recent outgoing call number.
     * Used as a fallback when ACTION_NEW_OUTGOING_CALL is not delivered (Android 10+).
     * The call log entry is created as soon as the call is placed, before it ends.
     */
    private static final class OutgoingCallLogEntry {
        final String number;
        final long dateMillis;

        OutgoingCallLogEntry(String number, long dateMillis) {
            this.number = number != null ? number : "";
            this.dateMillis = dateMillis;
        }
    }

    private OutgoingCallLogEntry getLatestOutgoingCallEntry() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) {
            return new OutgoingCallLogEntry("", 0L);
        }

        try (Cursor cursor = getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE},
                null, null,
                CallLog.Calls.DATE + " DESC")) {
            if (cursor != null && cursor.moveToFirst()) {
                int typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE);
                int numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER);
                int dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE);
                int type = typeIdx >= 0 ? cursor.getInt(typeIdx) : -1;
                long date = dateIdx >= 0 ? cursor.getLong(dateIdx) : 0L;
                if (type == CallLog.Calls.OUTGOING_TYPE || type == 0) {
                    String number = numberIdx >= 0 ? cursor.getString(numberIdx) : "";
                    return new OutgoingCallLogEntry(number, date);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getLatestOutgoingCallEntry failed: " + e.getMessage());
        }

        return new OutgoingCallLogEntry("", 0L);
    }

    private void cancelPendingCallLogFallback() {
        if (pendingCallLogRunnable != null) {
            mainHandler.removeCallbacks(pendingCallLogRunnable);
            pendingCallLogRunnable = null;
        }
        pendingCallLogRetries = 0;
    }

    private void handlePhoneStateChanged(Intent intent) {
        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        if (state == null) return;

        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            String ringingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
            if (ringingNumber == null) ringingNumber = "";

            if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(lastPhoneState)) {
                // Call waiting: a second call is ringing while one is offhook.
                // Keep the active call's context (number/direction) — only publish
                // the waiting caller alongside it. state stays "offhook" so the web
                // does not mistake this for a fresh standalone incoming call.
                callWasInProgress = true;
                waitingCallNumber = ringingNumber;
                if (firestoreManager != null) {
                    String activeNumber = !lastDialedNumber.isEmpty() ? lastDialedNumber : lastIncomingNumber;
                    String activeDirection = !lastDialedNumber.isEmpty() ? "outgoing" : "incoming";
                    firestoreManager.updateCallState("offhook", activeNumber, activeDirection,
                            ringingNumber, getContactNameForNumber(ringingNumber));
                }
            } else {
                cancelPendingCallLogFallback();
                callWasInProgress = true;
                // A new incoming ringing event should not inherit any previous outgoing target.
                lastDialedNumber = "";
                lastIncomingNumber = ringingNumber;
                if (firestoreManager != null) {
                    firestoreManager.updateCallState("ringing", lastIncomingNumber, "incoming");
                }
            }
        } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
            callWasInProgress = true;
            if (!waitingCallNumber.isEmpty()) {
                // The waiting call resolved. The aggregate phone state cannot tell
                // "accepted" from "rejected on the handset", so we promote the waiting
                // call only when we issued the accept ourselves; otherwise we assume
                // the original call continues. The call-log reconciliation at IDLE
                // fixes history either way.
                //
                // Whichever way it resolved, nothing is ringing any more — retire the
                // incoming node alongside the waiting fields, since the remote ring
                // surfaces (web popup, iOS CallKit screen) key off it.
                if (firestoreManager != null) firestoreManager.clearIncomingCallStatus();
                if (acceptWaitingIssued) {
                    lastIncomingNumber = waitingCallNumber;
                    lastDialedNumber = "";
                    if (firestoreManager != null) {
                        firestoreManager.updateCallState("offhook", lastIncomingNumber, "incoming");
                    }
                } else if (firestoreManager != null) {
                    String activeNumber = !lastDialedNumber.isEmpty() ? lastDialedNumber : lastIncomingNumber;
                    String activeDirection = !lastDialedNumber.isEmpty() ? "outgoing" : "incoming";
                    firestoreManager.updateCallState("offhook", activeNumber, activeDirection);
                }
                waitingCallNumber = "";
                acceptWaitingIssued = false;
            } else if (!lastDialedNumber.isEmpty()) {
                if (firestoreManager != null) firestoreManager.clearIncomingCallStatus();
                // Web-initiated outgoing call — number known immediately
                if (firestoreManager != null)
                    firestoreManager.updateCallState("offhook", lastDialedNumber, "outgoing");
            } else if (!OutgoingCallReceiver.lastOutgoingNumber.isEmpty()) {
                // Device-initiated outgoing — captured via ACTION_NEW_OUTGOING_CALL
                String num = OutgoingCallReceiver.lastOutgoingNumber;
                OutgoingCallReceiver.lastOutgoingNumber = "";
                if (firestoreManager != null) firestoreManager.clearIncomingCallStatus();
                if (firestoreManager != null)
                    firestoreManager.updateCallState("offhook", num, "outgoing");
            } else if (!lastIncomingNumber.isEmpty()) {
                // Incoming call answered on device. Clear the incoming node too: the call
                // is no longer ringing, and remote ring surfaces (agent-web's incoming
                // popup, the iOS CallKit screen) key off it. Leaving it set until IDLE
                // used to make them keep ringing over a call that was already connected.
                if (firestoreManager != null) {
                    firestoreManager.clearIncomingCallStatus();
                    firestoreManager.updateCallState("offhook", lastIncomingNumber, "incoming");
                }
            } else {
                // ACTION_NEW_OUTGOING_CALL not received (blocked on Android 10+).
                // Write a placeholder immediately, then wait for a *new* call-log row.
                // Without the timestamp guard, some devices return the previous outgoing
                // call here, which makes the web UI show stale caller info.
                if (firestoreManager != null) firestoreManager.clearIncomingCallStatus();
                if (firestoreManager != null)
                    firestoreManager.updateCallState("offhook", "", "outgoing");
                cancelPendingCallLogFallback();
                final long baselineDate = getLatestOutgoingCallEntry().dateMillis;
                pendingCallLogRetries = 0;
                pendingCallLogRunnable = new Runnable() {
                    @Override
                    public void run() {
                        OutgoingCallLogEntry entry = getLatestOutgoingCallEntry();
                        Log.d(TAG, "call-log fallback number: " + entry.number + " date=" + entry.dateMillis + " baseline=" + baselineDate + " retry=" + pendingCallLogRetries);
                        if (firestoreManager != null && !entry.number.isEmpty() && entry.dateMillis > baselineDate) {
                            firestoreManager.updateCallState("offhook", entry.number, "outgoing");
                            pendingCallLogRunnable = null;
                            return;
                        }

                        pendingCallLogRetries++;
                        if (pendingCallLogRetries < 12) {
                            mainHandler.postDelayed(this, 1000);
                        } else {
                            Log.w(TAG, "call-log fallback gave no new outgoing entry; leaving number empty to avoid stale sync");
                            pendingCallLogRunnable = null;
                        }
                    }
                };
                mainHandler.postDelayed(pendingCallLogRunnable, 1000);
            }
        } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
            cancelPendingCallLogFallback();
            if (firestoreManager != null) {
                firestoreManager.updateCallState("idle", "", "");
            }
            if (callWasInProgress && !TelephonyManager.EXTRA_STATE_IDLE.equals(lastPhoneState)) {
                callWasInProgress = false;
                syncFirestoreOnDeviceCallEnded();
            }
            // Clear tracked numbers so the next call cannot reuse stale caller info.
            lastDialedNumber = "";
            lastIncomingNumber = "";
            waitingCallNumber = "";
            acceptWaitingIssued = false;
            OutgoingCallReceiver.lastOutgoingNumber = "";
        }
        lastPhoneState = state;
    }

    /** Contact display-name lookup for the call-waiting banner; "" when unknown. */
    @SuppressLint("Range")
    private String getContactNameForNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()) return "";
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) return "";
        Uri uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber));
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = cursor.getString(idx);
                    return name != null ? name : "";
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Contact lookup failed: " + e.getMessage());
        }
        return "";
    }

    private void syncFirestoreOnDeviceCallEnded() {
        if (firestoreManager == null) return;
        // Mirror the web disconnect path's terminal state so the web auto-dialer
        // advances after a device-side hangup, without writing status=0 which would
        // re-enter the service's own disconnect handler.
        Map<String, Object> infoUpdate = new HashMap<>();
        infoUpdate.put("status", 3);
        firestoreManager.updateInfo(infoUpdate);
        firestoreManager.updateCallStatus(2);
        firestoreManager.clearIncomingCallStatus();
        if (callHistorySync != null) {
            callHistorySync.syncCallHistory();
        }
    }

    // ── Call actions ──────────────────────────────────────────────────────────

    private void makePhoneCall(String number) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "CALL_PHONE permission not granted");
            return;
        }

        String normalizedNumber = number != null ? number.trim() : "";
        if (normalizedNumber.isEmpty()) {
            Log.e(TAG, "makePhoneCall: empty number");
            return;
        }

        if (placeCallDirectly(normalizedNumber)) {
            return;
        }

        // Fallback only if the direct TelecomManager path is rejected on this device.
        triggerCallViaActivity(normalizedNumber);
    }

    private boolean placeCallDirectly(String number) {
        TelecomManager telecomManager = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
        if (telecomManager == null) {
            Log.e(TAG, "placeCallDirectly: TelecomManager null");
            return false;
        }

        try {
            Bundle extras = new Bundle();
            extras.putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false);
            if (pendingDialSimSlot >= 0) {
                PhoneAccountHandle handle = resolvePhoneAccountHandle(pendingDialSimSlot);
                if (handle != null) {
                    extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle);
                    Log.d(TAG, "placeCallDirectly: routing via SIM slot " + pendingDialSimSlot);
                }
            }
            pendingDialSimSlot = -1;
            telecomManager.placeCall(Uri.fromParts("tel", number, null), extras);
            Log.d(TAG, "placeCallDirectly: " + number);
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "placeCallDirectly security failure: " + e.getMessage(), e);
        } catch (Exception e) {
            Log.e(TAG, "placeCallDirectly failed: " + e.getMessage(), e);
        }

        pendingDialSimSlot = -1;
        return false;
    }

    @SuppressLint("MissingPermission")
    private PhoneAccountHandle resolvePhoneAccountHandle(int simSlot) {
        TelecomManager tm = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
        if (tm == null) return null;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) return null;
        SubscriptionManager sm = (SubscriptionManager) getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        if (sm == null) return null;
        try {
            List<PhoneAccountHandle> handles = tm.getCallCapablePhoneAccounts();
            if (handles == null) return null;
            for (PhoneAccountHandle handle : handles) {
                String id = handle.getId();
                try {
                    int subId = Integer.parseInt(id);
                    SubscriptionInfo si = sm.getActiveSubscriptionInfo(subId);
                    if (si != null && si.getSimSlotIndex() == simSlot) {
                        return handle;
                    }
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "resolvePhoneAccountHandle failed: " + e.getMessage());
        }
        return null;
    }

    private void answerCall() {
        TelecomManager tm = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
        if (tm == null) { Log.e(TAG, "answerCall: TelecomManager null"); return; }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS)
                == PackageManager.PERMISSION_GRANTED) {
            if (!waitingCallNumber.isEmpty()) {
                // acceptRingingCall() answers the waiting call and the OS puts the
                // current call on hold. Remember that *we* accepted so the next
                // OFFHOOK broadcast promotes the waiting number to active.
                acceptWaitingIssued = true;
                Log.d(TAG, "answerCall: accepting waiting call " + waitingCallNumber);
            }
            tm.acceptRingingCall();
            Log.d(TAG, "answerCall: acceptRingingCall()");
        } else {
            Log.e(TAG, "answerCall: ANSWER_PHONE_CALLS permission not granted");
        }
    }

    /**
     * True when a call is currently ringing and could be declined: either the line is
     * RINGING, or a second caller is waiting while the first is offhook. Falls back to
     * the live telephony state so a service restart mid-ring doesn't misread it.
     */
    private boolean isSomethingRinging() {
        if (!waitingCallNumber.isEmpty()) return true;
        if (TelephonyManager.EXTRA_STATE_RINGING.equals(lastPhoneState)) return true;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            // Can't verify — fall back to the old unconditional behaviour rather than
            // swallowing a decline the operator really did press.
            return true;
        }
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        return tm != null && tm.getCallState() == TelephonyManager.CALL_STATE_RINGING;
    }

    private void disconnectCall() {
        TelecomManager tm = (TelecomManager) getApplicationContext().getSystemService(Context.TELECOM_SERVICE);
        if (tm != null && ActivityCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS)
                == PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // endCall() rejects the ringing call when one exists, otherwise it ends
                // the active call. During call waiting this means status='0' declines
                // only the waiting caller and the active call continues untouched.
                boolean rejectingWaiting = !waitingCallNumber.isEmpty();
                boolean success = tm.endCall();
                if (success) {
                    Toast.makeText(this, rejectingWaiting ? "Waiting Call Rejected" : "Call Disconnected!",
                            Toast.LENGTH_SHORT).show();
                    Log.d(TAG, rejectingWaiting ? "Waiting call rejected" : "Call ended");
                }
            }
        }
    }

    private void triggerCallViaActivity(String number) {
        // Step 1: Acquire a screen-wakeup lock so the display turns on immediately.
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock screenLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "DialingAssistant:CallScreenWake");
            screenLock.acquire(30_000L); // held for up to 30 s; released by CallTriggerActivity
        }

        Intent activityIntent = new Intent(this, CallTriggerActivity.class);
        activityIntent.putExtra("number", number);
        activityIntent.putExtra("notificationId", CALL_NOTIFICATION_ID);
        activityIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        // Step 2: Apps holding SYSTEM_ALERT_WINDOW can start activities from the background.
        boolean activityStarted = false;
        if (android.provider.Settings.canDrawOverlays(this)) {
            try {
                startActivity(activityIntent);
                activityStarted = true;
                Log.d(TAG, "triggerCallViaActivity: direct startActivity succeeded");
            } catch (Exception e) {
                Log.w(TAG, "triggerCallViaActivity: direct startActivity failed — " + e.getMessage());
            }
        }

        // Step 3: Always post a full-screen-intent notification as a fallback for OEM
        // devices that block direct background activity starts.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel callChannel = new NotificationChannel(
                    CALL_CHANNEL_ID, "Call Trigger", NotificationManager.IMPORTANCE_HIGH);
            callChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            getSystemService(NotificationManager.class).createNotificationChannel(callChannel);
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CALL_CHANNEL_ID)
                .setContentTitle("Outgoing Call")
                .setContentText("Dialing " + number)
                .setSmallIcon(R.drawable.ic_launcher_background)
                .setPriority(NotificationCompat.PRIORITY_MAX)        // MAX = heads-up on locked screen
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // show on lock screen
                .setFullScreenIntent(pendingIntent, true)
                .setOngoing(!activityStarted)  // keep alive if direct start failed
                .setAutoCancel(true)
                .build();

        getSystemService(NotificationManager.class).notify(CALL_NOTIFICATION_ID, notification);
        Log.d(TAG, "triggerCallViaActivity: full-screen notification posted");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(DataSnapshot snapshot) {
        if (snapshot == null || !snapshot.exists()) return null;
        Object value = snapshot.getValue();
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
    }

    private String asString(Object value) {
        return value != null ? value.toString() : "";
    }

    private Long asLong(Object value) {
        if (value instanceof Long) return (Long) value;
        if (value instanceof Integer) return ((Integer) value).longValue();
        if (value instanceof Double) return ((Double) value).longValue();
        if (value instanceof Number) return ((Number) value).longValue();
        return null;
    }
}
