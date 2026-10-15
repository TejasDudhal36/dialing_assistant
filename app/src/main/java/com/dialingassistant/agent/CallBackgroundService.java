package com.dialingassistant.agent;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.telecom.TelecomManager;
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

import android.annotation.SuppressLint;
import android.database.Cursor;
import android.provider.CallLog;
import android.telecom.PhoneAccountHandle;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CallBackgroundService extends Service {

    private static final String TAG = "CallBgService";
    private static final String CHANNEL_ID = "ForegroundServiceChannel";
    private static final String CALL_CHANNEL_ID = "CallTriggerChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final int CALL_NOTIFICATION_ID = 99;

    // RTDB manager (created after auth check)
    private FirestoreManager firestoreManager;

    // RTDB snapshot listeners
    private ListenerRegistration infoListener;
    private ListenerRegistration workStatusListener;
    private ListenerRegistration deviceControlsListener;
    private ListenerRegistration incomingCallListener;
    private ListenerRegistration callStatusListener;

    private final CallReceiver receiver = new CallReceiver();
    private DeviceStateManager deviceStateManager;
    private SmsSyncManager smsSyncManager;
    private LocalNetworkManager localNetworkManager;
    private PowerManager.WakeLock wakeLock;

    private String lastProcessedDialCommand = "";
    private String lastHandledDialCmdId = "";
    private String lastPhoneState = TelephonyManager.EXTRA_STATE_IDLE;
    private boolean callWasInProgress = false;
    private String lastDialedNumber = "";
    private String lastIncomingNumber = "";
    private String waitingCallNumber = "";
    private boolean acceptWaitingIssued = false;
    private boolean callStatusInitialized = false;
    private int pendingDialSimSlot = -1;
    private AudioManager audioManager;
    private boolean initialized = false;
    private MediaSessionManager mediaSessionManager;
    private ComponentName notificationListenerComponent;
    private final List<MediaController> trackedMediaControllers = new ArrayList<>();
    private final List<MediaController.Callback> trackedMediaCallbacks = new ArrayList<>();
    private static final String PREFS_NAME = "call_bg_prefs";
    private static final String PREF_LAST_MUSIC_PACKAGE = "last_music_package";
    private static final String PREF_LAST_DIAL_CMD_ID = "last_dial_cmd_id";
    private static final int MUSIC_INIT_RETRY_DELAY_MS = 1500;
    private static final int MUSIC_INIT_MAX_ATTEMPTS = 10;
    private static final int MUSIC_INIT_INITIAL_DELAY_MS = 2500;
    private final Runnable mediaStatePollRunnable = new Runnable() {
        @Override
        public void run() {
            publishMusicState();
            scheduleMusicStatePoll();
        }
    };
    private final Runnable musicInitRetryRunnable = new Runnable() {
        @Override
        public void run() {
            runMusicInitializationAttempt();
        }
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingCallLogRunnable;
    private int pendingCallLogRetries = 0;
    private final MediaSessionManager.OnActiveSessionsChangedListener activeSessionsChangedListener = controllers ->
            refreshMediaControllers(controllers);
    private String lastPublishedMusicSignature = "";
    private String lastPublishedMusicTitle = "";
    private String lastKnownMusicPackage = "";
    private String pendingMusicLaunchPackage = "";
    private int pendingMusicLaunchAttempts = 0;

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
        lastKnownMusicPackage = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_LAST_MUSIC_PACKAGE, "");
        lastHandledDialCmdId = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_LAST_DIAL_CMD_ID, "");

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

            IntentFilter phoneFilter = new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
            registerReceiver(receiver, phoneFilter);
            registerReceiver(phoneStateSyncReceiver, phoneFilter);

            deviceStateManager = new DeviceStateManager(this, firestoreManager);
            deviceStateManager.register();

            smsSyncManager = new SmsSyncManager(this, firestoreManager);
            smsSyncManager.register();

            localNetworkManager = new LocalNetworkManager();
            localNetworkManager.start(this, firestoreManager, new LocalNetworkManager.ServiceCallbacks() {
                @Override public void onDial(String number)                     { makePhoneCall(number); }
                @Override public void onHangup()                                { disconnectCall(); }
                @Override public void onAnswer()                                { answerCall(); }
                @Override public void onSms(String to, String body, String id)  { smsSyncManager.handleLocalSendCmd(to, body, id); }
                @Override public void onWifi(int value)                         { setWifiEnabled(value == 1); }
                @Override public void onBluetooth(int value)                    { setBluetoothEnabled(value == 1); }
                @Override public void onHotspot(int value)                      { setHotspotEnabled(value == 1); }
                @Override public void onCallVolume(int value)                   { handleCallVolumeCommand(value); }
                @Override public void onMusic(int value)                        { handleMusicCommand(value); }
            });
            initMediaSessionTracking();
            primeMusicPlaybackSession();

            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DialingAssistant:WakeLock");
                wakeLock.acquire(60 * 60 * 1000L); // 1-hour max, auto-released
            }
            Log.d(TAG, "Service listeners initialized");
        } else {
            Log.d(TAG, "onStartCommand: service already initialized");
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (pendingCallLogRunnable != null) mainHandler.removeCallbacks(pendingCallLogRunnable);
        cancelMusicInitialization();
        removeFirestoreListeners();
        if (deviceStateManager != null) deviceStateManager.unregister();
        if (smsSyncManager != null) smsSyncManager.unregister();
        if (localNetworkManager != null) localNetworkManager.stop();
        clearMediaSessionTracking();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        try { unregisterReceiver(phoneStateSyncReceiver); } catch (Exception ignored) {}
        initialized = false;
        Log.d(TAG, "Service destroyed");
    }

    // ── Foreground notification ────────────────────────────────────────��───────

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

        // work_status listener → call forwarding
        workStatusListener = firestoreManager.listenToWorkStatus((snapshot, error) -> {
            if (error != null || snapshot == null || !snapshot.exists()) return;
            Long statusLong = asLong(snapshot.child("status").getValue());
            if (statusLong == null) return;
            int status = statusLong.intValue();
            Log.d(TAG, "work_status: " + status);
            if (status == 0) stopForwarding();
            else if (status == 1) startForwarding();
            // Reset work_status to 2 (idle) after handling
            firestoreManager.updateWorkStatus(2);
        });

        // device_controls listener
        deviceControlsListener = firestoreManager.listenToDeviceControls((snapshot, error) -> {
            if (error != null || snapshot == null || !snapshot.exists()) return;
            readDeviceInfoData(snapshot);
        });

        // call_status listener → answer (1) or disconnect (0) from web app
        callStatusListener = firestoreManager.listenToCallStatus((snapshot, error) -> {
            if (error != null || snapshot == null || !snapshot.exists()) return;
            Long answer = asLong(snapshot.child("answer").getValue());
            if (answer == null) return;
            if (!callStatusInitialized) {
                // Value listeners replay persisted data on startup. That replay is not a
                // new command and must not answer a later incoming call.
                callStatusInitialized = true;
                if (answer == 1 || answer == 0) firestoreManager.updateCallStatus(2);
                return;
            }
            Log.d(TAG, "call_status answer=" + answer);
            if (answer == 1) {
                if (isSomethingRinging()) answerCall();
                else Log.d(TAG, "answer=1 ignored — no ringing or waiting call");
            } else if (answer == 0) {
                if (isSomethingRinging()) disconnectCall();
                else Log.d(TAG, "answer=0 ignored — no ringing or waiting call");
            }
            // The device is the authoritative command resetter.
            if (answer == 1 || answer == 0) firestoreManager.updateCallStatus(2);
        });

        // incoming_call_status listener → update battery info on incoming call
        incomingCallListener = firestoreManager.listenToIncomingCallStatus((snapshot, error) -> {
            if (error != null || snapshot == null || !snapshot.exists()) return;
            // When there's an incoming call, push fresh battery info
            BatteryUtils.BatteryInfo batteryInfo = BatteryUtils.getBatteryInfo(getApplicationContext());
            Map<String, Object> batteryData = new HashMap<>();
            batteryData.put("battery_level", batteryInfo.batteryPercentage);
            batteryData.put("battery_status", batteryInfo.isCharging);
            Map<String, Object> update = new HashMap<>();
            update.put("battery_info", batteryData);
            firestoreManager.updateDeviceControls(update);
        });
    }

    private void removeFirestoreListeners() {
        if (infoListener != null) infoListener.remove();
        if (workStatusListener != null) workStatusListener.remove();
        if (deviceControlsListener != null) deviceControlsListener.remove();
        if (incomingCallListener != null) incomingCallListener.remove();
        if (callStatusListener != null) callStatusListener.remove();
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
        deviceStateManager.syncCallHistory();

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

    // ── Device controls handler ─────────────────────────────────────────────��──

    private void readDeviceInfoData(DataSnapshot snapshot) {
        Map<String, Object> map = toMap(snapshot);
        if (map == null) return;

        Long syncStatus = asLong(map.get("sync_status"));
        if (syncStatus != null && syncStatus == 1L) {
            deviceStateManager.syncCallHistory();
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

        // Handle direct dial command
        Object dialObj = map.get("dial");
        if (dialObj != null) {
            String dialNumber = dialObj.toString().trim();
            if (dialNumber.isEmpty()) {
                lastProcessedDialCommand = "";
            } else if (!dialNumber.equals(lastProcessedDialCommand)) {
                lastProcessedDialCommand = dialNumber;
                Object dialSimObj = map.get("dial_sim");
                pendingDialSimSlot = (dialSimObj instanceof Number) ? ((Number) dialSimObj).intValue() : -1;
                Log.d(TAG, "Dial command: " + dialNumber + " simSlot=" + pendingDialSimSlot);
                makePhoneCall(dialNumber);
                firestoreManager.clearDeviceControlField("dial");
                firestoreManager.clearDeviceControlField("dial_sim");
            }
        }

        // Handle mute_cmd
        Object muteCmdObj = map.get("mute_cmd");
        if (muteCmdObj != null && audioManager != null) {
            int val = ((Number) muteCmdObj).intValue();
            audioManager.setMicrophoneMute(val == 1);
            Log.d(TAG, "mute_cmd: " + val);
            firestoreManager.clearDeviceControlField("mute_cmd");
            // Report actual state back
            firestoreManager.updateDeviceControlField("mute", audioManager.isMicrophoneMute() ? 1 : 0);
        }

        // Handle speaker_cmd
        Object speakerCmdObj = map.get("speaker_cmd");
        if (speakerCmdObj != null && audioManager != null) {
            int val = ((Number) speakerCmdObj).intValue();
            // Ensure in-call mode so speaker change takes effect
            if (audioManager.getMode() != AudioManager.MODE_IN_CALL) {
                audioManager.setMode(AudioManager.MODE_IN_CALL);
            }
            audioManager.setSpeakerphoneOn(val == 1);
            Log.d(TAG, "speaker_cmd: " + val);
            firestoreManager.clearDeviceControlField("speaker_cmd");
            // Report actual state back
            firestoreManager.updateDeviceControlField("speaker", audioManager.isSpeakerphoneOn() ? 1 : 0);
        }

        // Handle wifi_cmd
        Object wifiCmdObj = map.get("wifi_cmd");
        if (wifiCmdObj != null) {
            int val = ((Number) wifiCmdObj).intValue();
            setWifiEnabled(val == 1);
            firestoreManager.clearDeviceControlField("wifi_cmd");
        }

        // Handle bluetooth_cmd
        Object bluetoothCmdObj = map.get("bluetooth_cmd");
        if (bluetoothCmdObj != null) {
            int val = ((Number) bluetoothCmdObj).intValue();
            setBluetoothEnabled(val == 1);
            firestoreManager.clearDeviceControlField("bluetooth_cmd");
        }

        // Handle hotspot_cmd
        Object hotspotCmdObj = map.get("hotspot_cmd");
        if (hotspotCmdObj != null) {
            int val = ((Number) hotspotCmdObj).intValue();
            setHotspotEnabled(val == 1);
            firestoreManager.clearDeviceControlField("hotspot_cmd");
        }

        // Handle screen_lock_cmd
        Object screenLockCmdObj = map.get("screen_lock_cmd");
        if (screenLockCmdObj != null) {
            int val = ((Number) screenLockCmdObj).intValue();
            if (val == 1) lockScreen();
            firestoreManager.clearDeviceControlField("screen_lock_cmd");
        }

        // Handle screen_wake_cmd
        Object screenWakeCmdObj = map.get("screen_wake_cmd");
        if (screenWakeCmdObj != null) {
            int val = ((Number) screenWakeCmdObj).intValue();
            if (val == 1) wakeScreen();
            firestoreManager.clearDeviceControlField("screen_wake_cmd");
        }

        // Handle screen_timeout_cmd
        Object screenTimeoutCmdObj = map.get("screen_timeout_cmd");
        if (screenTimeoutCmdObj != null) {
            int seconds = ((Number) screenTimeoutCmdObj).intValue();
            setScreenTimeout(seconds);
            firestoreManager.clearDeviceControlField("screen_timeout_cmd");
        }

        // Handle call_volume_cmd
        Object callVolumeCmdObj = map.get("call_volume_cmd");
        if (callVolumeCmdObj != null) {
            int val = ((Number) callVolumeCmdObj).intValue();
            handleCallVolumeCommand(val);
            firestoreManager.clearDeviceControlField("call_volume_cmd");
        }

        // Handle music_cmd
        Object musicCmdObj = map.get("music_cmd");
        if (musicCmdObj != null) {
            int val = ((Number) musicCmdObj).intValue();
            handleMusicCommand(val);
            firestoreManager.clearDeviceControlField("music_cmd");
        }
    }

    // ── Device control helpers ────────────────────────────────────────────────

    /**
     * Toggles WiFi.
     *
     * Attempt order:
     *  1. Device Owner: WifiManager.setWifiEnabled() is permitted for DO on all API levels.
     *  2. API < 29: WifiManager.setWifiEnabled() permitted for all apps.
     *  3. Shell cmd: Runtime.exec("cmd wifi set-wifi-enabled …") — works if the cmd service
     *     allows app-UID callers (varies by vendor/Android version).
     *  4. Open WiFi settings directly via startActivity (no popup notification).
     *  5. Last resort: heads-up notification with tap-to-settings.
     */
    private void setWifiEnabled(boolean enable) {
        // 1. Device Owner path
        android.app.admin.DevicePolicyManager dpm =
                (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wm.setWifiEnabled(enable);
                Log.d(TAG, "WiFi via WifiManager (Device Owner): " + enable);
                return;
            }
        }

        // 2. Pre-Android 10
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wm.setWifiEnabled(enable);
                Log.d(TAG, "WiFi via WifiManager (API<29): " + enable);
                return;
            }
        }

        // 3. Accessibility service — opens Quick Settings and taps the WiFi tile
        DeviceControlAccessibilityService.Toggle wifiToggle = enable
                ? DeviceControlAccessibilityService.Toggle.WIFI_ON
                : DeviceControlAccessibilityService.Toggle.WIFI_OFF;
        if (DeviceControlAccessibilityService.requestToggle(wifiToggle)) {
            Log.d(TAG, "WiFi toggle dispatched via AccessibilityService: " + enable);
            return;
        }

        // 4. Shell cmd fallback (may work on some vendor builds)
        String wifiArg = enable ? "enabled" : "disabled";
        if (execShellCmd("cmd", "wifi", "set-wifi-enabled", wifiArg)) {
            Log.d(TAG, "WiFi via shell cmd: " + enable);
            return;
        }

        // 5. Open settings panel directly (no notification)
        Intent intent = new Intent(android.provider.Settings.Panel.ACTION_WIFI);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            Log.d(TAG, "WiFi settings panel opened");
        } catch (Exception e) {
            PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            showControlNotification("WiFi " + (enable ? "ON" : "OFF") + " — tap to open",
                    "Tap to open WiFi settings", pi, 201);
        }
    }

    /**
     * Toggles Bluetooth.
     *
     * Attempt order:
     *  1. API < 33: BluetoothAdapter.enable()/disable() — permitted for all apps.
     *  2. Shell cmd: Runtime.exec("cmd bluetooth_manager enable/disable") — the service
     *     only requires BLUETOOTH_CONNECT which we hold; works if the caller UID is accepted.
     *  3. Open Bluetooth settings directly via startActivity (no popup notification).
     *  4. Last resort: heads-up notification.
     */
    @SuppressWarnings("deprecation")
    private void setBluetoothEnabled(boolean enable) {
        // 1. Pre-Android 13
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt != null) {
                if (enable) bt.enable(); else bt.disable();
                Log.d(TAG, "Bluetooth via BluetoothAdapter: " + enable);
                return;
            }
        }

        // 2. Accessibility service — opens Quick Settings and taps the Bluetooth tile
        DeviceControlAccessibilityService.Toggle btToggle = enable
                ? DeviceControlAccessibilityService.Toggle.BT_ON
                : DeviceControlAccessibilityService.Toggle.BT_OFF;
        if (DeviceControlAccessibilityService.requestToggle(btToggle)) {
            Log.d(TAG, "Bluetooth toggle dispatched via AccessibilityService: " + enable);
            return;
        }

        // 3. Shell cmd fallback
        String btCmd = enable ? "enable" : "disable";
        if (execShellCmd("cmd", "bluetooth_manager", btCmd)) {
            Log.d(TAG, "Bluetooth via shell cmd: " + enable);
            return;
        }

        // 4. Open settings directly (no notification)
        Intent intent = new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            Log.d(TAG, "Bluetooth settings opened");
        } catch (Exception e) {
            PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            showControlNotification("Bluetooth " + (enable ? "ON" : "OFF") + " — tap to open",
                    "Tap to open Bluetooth settings", pi, 202);
        }
    }

    /**
     * Runs a shell command synchronously and returns true if exit code == 0.
     * The subprocess inherits this app's UID, so it can use permissions we hold.
     */
    private boolean execShellCmd(String... args) {
        try {
            Process p = Runtime.getRuntime().exec(args);
            int exit = p.waitFor();
            if (exit != 0) {
                java.io.BufferedReader err = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getErrorStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = err.readLine()) != null) sb.append(line);
                Log.w(TAG, "execShellCmd " + java.util.Arrays.toString(args)
                        + " exit=" + exit + " err=" + sb);
            }
            return exit == 0;
        } catch (Exception e) {
            Log.w(TAG, "execShellCmd failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Toggles Mobile Hotspot (WiFi tethering).
     *
     * There is no public API for hotspot control without root or TETHER_PRIVILEGED
     * on Android 8+. We try legacy reflection as a best-effort and fall back to a
     * notification that opens the Hotspot settings page.
     */
    @SuppressWarnings("JavaReflectionMemberAccess")
    private void setHotspotEnabled(boolean enable) {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        boolean reflectionWorked = false;
        if (wm != null) {
            try {
                java.lang.reflect.Method setWifiApEnabled = wm.getClass()
                        .getMethod("setWifiApEnabled", android.net.wifi.WifiConfiguration.class, boolean.class);
                setWifiApEnabled.invoke(wm, null, enable);
                Log.d(TAG, "hotspot set via reflection: " + enable);
                reflectionWorked = true;
            } catch (Exception ignored) {}
        }

        if (!reflectionWorked) {
            // Fallback: notify user to toggle manually
            Intent hotspotIntent = new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS);
            hotspotIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(this, 0, hotspotIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            showControlNotification("Hotspot " + (enable ? "ON" : "OFF") + " requested",
                    "Tap to open Wireless settings", pi, 203);
            Log.w(TAG, "Hotspot toggle via reflection failed — notified user");
        }
    }

    /**
     * Locks the screen immediately via DevicePolicyManager.
     * Requires Device Admin to be active (Settings → Security → Device Admin Apps).
     */
    private void lockScreen() {
        android.app.admin.DevicePolicyManager dpm =
                (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        android.content.ComponentName admin = new android.content.ComponentName(this, AgentDeviceAdmin.class);
        if (dpm != null && dpm.isAdminActive(admin)) {
            dpm.lockNow();
            Log.d(TAG, "Screen locked via DevicePolicyManager");
        } else {
            Intent intent = new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            showControlNotification("Screen Lock: activate Device Admin",
                    "Tap → Security → Device Admin Apps → enable Dialing Assistant", pi, 204);
            Log.w(TAG, "lockScreen: DeviceAdmin not active");
        }
    }

    /** Turns the screen on by acquiring a full wake lock. */
    @SuppressWarnings("deprecation")
    private void wakeScreen() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "DialingAssistant:ScreenWake");
            wl.acquire(10_000L);
            Log.d(TAG, "Screen woken");
        }
    }

    /**
     * Sets the screen-off timeout via Settings.System.
     * Requires WRITE_SETTINGS special permission (Settings → Apps → Special App Access →
     * Modify system settings → enable Dialing Assistant).
     */
    private void setScreenTimeout(int seconds) {
        if (android.provider.Settings.System.canWrite(this)) {
            android.provider.Settings.System.putInt(
                    getContentResolver(),
                    android.provider.Settings.System.SCREEN_OFF_TIMEOUT,
                    seconds * 1000);
            firestoreManager.updateDeviceControlField("screen_timeout", seconds);
            Log.d(TAG, "Screen timeout set to " + seconds + "s");
        } else {
            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            showControlNotification("Screen Timer: grant permission",
                    "Tap → allow Modify system settings", pi, 205);
            Log.w(TAG, "setScreenTimeout: WRITE_SETTINGS not granted");
        }
    }

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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            publishMusicState();
            return;
        }
        mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (mediaSessionManager == null) {
            Log.w(TAG, "initMediaSessionTracking: mediaSessionManager unavailable");
            publishMusicState();
            return;
        }
        if (notificationListenerComponent == null) {
            notificationListenerComponent = new ComponentName(this, NotificationListener.class);
        }
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(
                    activeSessionsChangedListener, notificationListenerComponent, mainHandler);
        } catch (Exception e) {
            Log.w(TAG, "Failed to register active sessions listener: " + e.getMessage());
        }
        try {
            refreshMediaControllers(mediaSessionManager.getActiveSessions(notificationListenerComponent));
        } catch (SecurityException e) {
            Log.w(TAG, "getActiveSessions denied — grant Notification Access in Settings: " + e.getMessage());
            publishMusicState();
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSessionManager != null
                && notificationListenerComponent != null) {
            try {
                mediaSessionManager.removeOnActiveSessionsChangedListener(activeSessionsChangedListener);
            } catch (Exception ignored) {}
        }
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
        LocalNetworkServer.broadcast("device_state", update);
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
        mainHandler.postDelayed(mediaStatePollRunnable, hasActiveMusicController() ? 500 : 3000);
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

    private void publishCallVolumeState() {
        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        if (audioManager == null) return;
        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
        int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
        int callVolume = (maxVolume > 0) ? (int) Math.round((currentVolume / (double) maxVolume) * 100) : 0;
        Map<String, Object> update = new HashMap<>();
        update.put("volume-level", (maxVolume > 0) ? (int) Math.round((currentVolume / (double) maxVolume) * 10) : 0);
        update.put("call_volume", callVolume);
        if (firestoreManager != null) {
            firestoreManager.updateDeviceControls(update);
        }
        LocalNetworkServer.broadcast("device_state", update);
    }

    /** Shows a high-priority notification with a settings shortcut. Auto-cancels on tap. */
    private void showControlNotification(String title, String text, PendingIntent action, int id) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    "ctrl_channel", "Device Controls", NotificationManager.IMPORTANCE_HIGH);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        Notification n = new NotificationCompat.Builder(this, "ctrl_channel")
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher_background)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(action)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(id, n);
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

    @SuppressLint("Range")
    private String getLatestOutgoingCallNumber() {
        return getLatestOutgoingCallEntry().number;
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
                // A second incoming call is call waiting. Keep the active call as the
                // primary state and expose the new caller separately.
                callWasInProgress = true;
                waitingCallNumber = ringingNumber;
                String activeNumber = !lastDialedNumber.isEmpty() ? lastDialedNumber : lastIncomingNumber;
                String activeDirection = !lastDialedNumber.isEmpty() ? "outgoing" : "incoming";
                if (firestoreManager != null) {
                    firestoreManager.updateCallState("offhook", activeNumber, activeDirection,
                            ringingNumber, "");
                }
            } else {
                cancelPendingCallLogFallback();
                callWasInProgress = true;
                lastDialedNumber = "";
                lastIncomingNumber = ringingNumber;
                if (firestoreManager != null) {
                    firestoreManager.updateCallState("ringing", lastIncomingNumber, "incoming");
                }
                broadcastCallState("ringing", lastIncomingNumber, "incoming");
            }
        } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
            callWasInProgress = true;
            if (!waitingCallNumber.isEmpty()) {
                if (firestoreManager != null) firestoreManager.clearIncomingCallStatus();
                if (acceptWaitingIssued) {
                    lastIncomingNumber = waitingCallNumber;
                    lastDialedNumber = "";
                    if (firestoreManager != null)
                        firestoreManager.updateCallState("offhook", lastIncomingNumber, "incoming");
                } else {
                    String activeNumber = !lastDialedNumber.isEmpty() ? lastDialedNumber : lastIncomingNumber;
                    String activeDirection = !lastDialedNumber.isEmpty() ? "outgoing" : "incoming";
                    if (firestoreManager != null)
                        firestoreManager.updateCallState("offhook", activeNumber, activeDirection);
                }
                waitingCallNumber = "";
                acceptWaitingIssued = false;
            } else if (!lastDialedNumber.isEmpty()) {
                if (firestoreManager != null) firestoreManager.clearIncomingCallStatus();
                // Web-initiated outgoing call — number known immediately
                if (firestoreManager != null)
                    firestoreManager.updateCallState("offhook", lastDialedNumber, "outgoing");
                broadcastCallState("offhook", lastDialedNumber, "outgoing");
            } else if (!OutgoingCallReceiver.lastOutgoingNumber.isEmpty()) {
                // Device-initiated outgoing — captured via ACTION_NEW_OUTGOING_CALL
                String num = OutgoingCallReceiver.lastOutgoingNumber;
                OutgoingCallReceiver.lastOutgoingNumber = "";
                if (firestoreManager != null) firestoreManager.clearIncomingCallStatus();
                if (firestoreManager != null)
                    firestoreManager.updateCallState("offhook", num, "outgoing");
                broadcastCallState("offhook", num, "outgoing");
            } else if (!lastIncomingNumber.isEmpty()) {
                // Incoming call answered on device
                if (firestoreManager != null)
                    firestoreManager.updateCallState("offhook", lastIncomingNumber, "incoming");
                broadcastCallState("offhook", lastIncomingNumber, "incoming");
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
                            broadcastCallState("offhook", entry.number, "outgoing");
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
            broadcastCallState("idle", "", "");
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
        if (deviceStateManager != null) {
            deviceStateManager.syncCallHistory();
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                extras.putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false);
            }
            if (pendingDialSimSlot >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!waitingCallNumber.isEmpty()) {
                    acceptWaitingIssued = true;
                    Log.d(TAG, "answerCall: accepting waiting call " + waitingCallNumber);
                }
                tm.acceptRingingCall();
                Log.d(TAG, "answerCall: acceptRingingCall()");
            }
        } else {
            Log.e(TAG, "answerCall: ANSWER_PHONE_CALLS permission not granted");
        }
    }

    /** True only when a command can target a ringing or waiting call. */
    private boolean isSomethingRinging() {
        if (!waitingCallNumber.isEmpty()) return true;
        if (TelephonyManager.EXTRA_STATE_RINGING.equals(lastPhoneState)) return true;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) return false;
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        return tm != null && tm.getCallState() == TelephonyManager.CALL_STATE_RINGING;
    }

    private void disconnectCall() {
        Toast.makeText(this, "Call Disconnected!", Toast.LENGTH_SHORT).show();
        TelecomManager tm = (TelecomManager) getApplicationContext().getSystemService(Context.TELECOM_SERVICE);
        if (tm != null && ActivityCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS)
                == PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                boolean success = tm.endCall();
                if (success) Log.d(TAG, "Call ended");
            }
        }
        if (firestoreManager != null && audioManager != null) {
            // Sync mute state after call ends
            Map<String, Object> update = new HashMap<>();
            update.put("mute", audioManager.isMicrophoneMute() ? 1 : 0);
            firestoreManager.updateDeviceControls(update);
        }
    }

    private void triggerCallViaActivity(String number) {
        // Step 1: Acquire a screen-wakeup lock so the display turns on immediately.
        // This is separate from the service's partial wakelock — we need FULL brightness
        // plus ACQUIRE_CAUSES_WAKEUP to physically light up the screen on a locked device.
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

        // Step 2: Try to start CallTriggerActivity directly from the foreground service.
        // Apps that hold SYSTEM_ALERT_WINDOW can start activities while in the background
        // (Android 10+, section 3 of background-activity-start exceptions).
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

        // Step 3: Always post a full-screen-intent notification as a belt-and-suspenders
        // fallback for OEM devices (OPPO/Realme/Xiaomi) that block direct activity starts
        // from the background. The notification also gives the user a tap target if the
        // activity somehow doesn't show over the lock screen.
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

    // ── Call forwarding ───────────────────────────────────────────────────────

    private static final String MOBILE_NUMBER = "8805550284";

    private void startForwarding() {
        makePhoneCall("*405*" + MOBILE_NUMBER);
    }

    private void stopForwarding() {
        makePhoneCall("*413");
    }

    // ── Local-network broadcast helpers ──────────────────────────────────────

    private void broadcastCallState(String state, String number, String direction) {
        try {
            org.json.JSONObject data = new org.json.JSONObject();
            data.put("state",     state);
            data.put("number",    number    != null ? number    : "");
            data.put("direction", direction != null ? direction : "");
            LocalNetworkServer.broadcast("call_state", data);
        } catch (org.json.JSONException ignored) {}
    }

    // ── FirestoreManager accessor (used by CallReceiver) ─────────────────────

    public FirestoreManager getFirestoreManager() {
        return firestoreManager;
    }

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
