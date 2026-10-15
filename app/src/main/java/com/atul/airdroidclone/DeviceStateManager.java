package com.dialingassistant.agent;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Listens to device hardware/system events and pushes changes to Firestore immediately.
 */
public class DeviceStateManager {

    private static final String TAG = "DeviceStateManager";

    private final Context context;
    private final AudioManager audioManager;
    private final FirestoreManager firestoreManager;

    private BroadcastReceiver batteryReceiver;
    private BroadcastReceiver wifiReceiver;
    private BroadcastReceiver bluetoothReceiver;
    private BroadcastReceiver ringerModeReceiver;
    private ContentObserver volumeObserver;
    private ContentObserver callLogObserver;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable callLogSyncRunnable;

    public DeviceStateManager(Context context, FirestoreManager firestoreManager) {
        this.context = context.getApplicationContext();
        this.firestoreManager = firestoreManager;
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    public void register() {
        registerBatteryReceiver();
        registerWifiReceiver();
        registerBluetoothReceiver();
        registerRingerModeReceiver();
        registerVolumeObserver();
        registerCallLogObserver();
        pushFullSnapshot();
        Log.d(TAG, "All device event listeners registered");
    }

    public void unregister() {
        try { context.unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
        try { context.unregisterReceiver(wifiReceiver); } catch (Exception ignored) {}
        try { context.unregisterReceiver(bluetoothReceiver); } catch (Exception ignored) {}
        try { context.unregisterReceiver(ringerModeReceiver); } catch (Exception ignored) {}
        try { context.getContentResolver().unregisterContentObserver(volumeObserver); } catch (Exception ignored) {}
        try { context.getContentResolver().unregisterContentObserver(callLogObserver); } catch (Exception ignored) {}
        debounceHandler.removeCallbacksAndMessages(null);
        Log.d(TAG, "All device event listeners unregistered");
    }

    // ─── Battery ──────────────────────────────────────────────────────────────

    private void registerBatteryReceiver() {
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                int batteryPct = (scale > 0) ? Math.max(0, (int) ((level / (float) scale) * 100)) : 0;
                boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;

                Map<String, Object> batteryInfo = new HashMap<>();
                batteryInfo.put("battery_level", batteryPct);
                batteryInfo.put("battery_status", isCharging);

                Map<String, Object> update = new HashMap<>();
                update.put("battery_level", batteryPct);
                update.put("battery_percentage", batteryPct);
                update.put("battery_status", isCharging);
                update.put("charging_status", isCharging ? "Charging" : "Discharging");
                update.put("battery_info", batteryInfo);

                firestoreManager.updateDeviceControls(update);
                LocalNetworkServer.broadcast("device_state", update);
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        context.registerReceiver(batteryReceiver, filter);
    }

    // ─── WiFi ─────────────────────────────────────────────────────────────────

    private void registerWifiReceiver() {
        wifiReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                int wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN);
                int wifi = (wifiState == WifiManager.WIFI_STATE_ENABLED) ? 1 : 0;
                firestoreManager.updateDeviceControlField("wifi", wifi);
                Map<String, Object> update = new HashMap<>();
                update.put("wifi", wifi);
                LocalNetworkServer.broadcast("device_state", update);
            }
        };
        context.registerReceiver(wifiReceiver, new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION));
    }

    // ─── Bluetooth ────────────────────────────────────────────────────────────

    private void registerBluetoothReceiver() {
        bluetoothReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                int bluetooth = (state == BluetoothAdapter.STATE_ON) ? 1 : 0;
                firestoreManager.updateDeviceControlField("bluetooth", bluetooth);
                Map<String, Object> update = new HashMap<>();
                update.put("bluetooth", bluetooth);
                LocalNetworkServer.broadcast("device_state", update);
            }
        };
        context.registerReceiver(bluetoothReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
    }

    // ─── Ringer mode ──────────────────────────────────────────────────────────

    private void registerRingerModeReceiver() {
        ringerModeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                int ringerMode = audioManager.getRingerMode();
                int silentMode = (ringerMode == AudioManager.RINGER_MODE_SILENT
                        || ringerMode == AudioManager.RINGER_MODE_VIBRATE) ? 1 : 0;
                int mute = audioManager.isMicrophoneMute() ? 1 : 0;

                Map<String, Object> update = new HashMap<>();
                update.put("silent-mode", silentMode);
                update.put("mute", mute);
                firestoreManager.updateDeviceControls(update);
                LocalNetworkServer.broadcast("device_state", update);
            }
        };
        context.registerReceiver(ringerModeReceiver, new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION));
    }

    // ─── Volume ───────────────────────────────────────────────────────────────

    private void registerVolumeObserver() {
        volumeObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
                int volumeLevel = (maxVolume > 0) ? (int) Math.round((currentVolume / (double) maxVolume) * 10) : 0;
                int callVolume = (maxVolume > 0) ? (int) Math.round((currentVolume / (double) maxVolume) * 100) : 0;
                int musicMaxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int musicCurrentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                int musicVolume = (musicMaxVolume > 0) ? (int) Math.round((musicCurrentVolume / (double) musicMaxVolume) * 100) : 0;
                int speaker = audioManager.isSpeakerphoneOn() ? 1 : 0;

                Map<String, Object> update = new HashMap<>();
                update.put("volume-level", volumeLevel);
                update.put("call_volume", callVolume);
                update.put("music_volume", musicVolume);
                update.put("speaker", speaker);
                firestoreManager.updateDeviceControls(update);
                LocalNetworkServer.broadcast("device_state", update);
            }
        };
        context.getContentResolver().registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver);
    }

    // ─── Call History ─────────────────────────────────────────────────────────

    private void registerCallLogObserver() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CALL_LOG permission not granted; skipping call log observer");
            return;
        }
        callLogObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                final Runnable current = callLogSyncRunnable;
                if (current != null) debounceHandler.removeCallbacks(current);
                final Runnable next = DeviceStateManager.this::syncCallHistory;
                callLogSyncRunnable = next;
                debounceHandler.postDelayed(next, 2000);
            }
        };
        context.getContentResolver().registerContentObserver(CallLog.Calls.CONTENT_URI, true, callLogObserver);
        Log.d(TAG, "Call log observer registered");
    }

    @SuppressLint("MissingPermission")
    private java.util.Map<Integer, Integer> buildSubIdToSlotMap() {
        java.util.Map<Integer, Integer> map = new HashMap<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return map;
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) return map;
        SubscriptionManager sm = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        if (sm == null) return map;
        try {
            java.util.List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
            if (subs != null) {
                for (SubscriptionInfo si : subs) {
                    map.put(si.getSubscriptionId(), si.getSimSlotIndex());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "buildSubIdToSlotMap failed: " + e.getMessage());
        }
        return map;
    }

    public void syncCallHistory() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "READ_CALL_LOG permission not granted");
            return;
        }

        java.util.Map<Integer, Integer> subIdToSlot = buildSubIdToSlotMap();

        Cursor cursor = context.getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                null, null, null,
                CallLog.Calls.DATE + " DESC"
        );

        if (cursor == null) {
            Log.e(TAG, "Call log cursor is null");
            return;
        }

        int numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER);
        int nameIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME);
        int typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE);
        int dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE);
        int durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION);
        int phoneAccountIndex = cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID);

        if (numberIndex < 0 || typeIndex < 0 || dateIndex < 0 || durationIndex < 0) {
            Log.e(TAG, "Required column missing from call log");
            cursor.close();
            return;
        }

        int count = 0;
        java.util.List<Map<String, Object>> entries = new java.util.ArrayList<>();
        try {
            while (cursor.moveToNext() && count < 10) {
                String number = cursor.getString(numberIndex);
                String name = (nameIndex >= 0) ? cursor.getString(nameIndex) : null;
                int type = cursor.getInt(typeIndex);
                long dateMillis = cursor.getLong(dateIndex);
                String duration = cursor.getString(durationIndex);

                int simSlot = -1;
                if (phoneAccountIndex >= 0) {
                    String accountId = cursor.getString(phoneAccountIndex);
                    if (accountId != null) {
                        try {
                            int subId = Integer.parseInt(accountId);
                            Integer slot = subIdToSlot.get(subId);
                            if (slot != null) simSlot = slot;
                        } catch (NumberFormatException ignored) {}
                    }
                }

                String callType;
                switch (type) {
                    case CallLog.Calls.OUTGOING_TYPE: callType = "OUTGOING"; break;
                    case CallLog.Calls.INCOMING_TYPE: callType = "INCOMING"; break;
                    case CallLog.Calls.MISSED_TYPE:   callType = "MISSED";   break;
                    case CallLog.Calls.REJECTED_TYPE: callType = "REJECTED"; break;
                    case CallLog.Calls.BLOCKED_TYPE:  callType = "BLOCKED";  break;
                    default:                          callType = "UNKNOWN";  break;
                }

                SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
                isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
                String readableDate = isoFormat.format(new Date(dateMillis));

                Map<String, Object> logMap = new HashMap<>();
                logMap.put("name", name != null ? name : "Unknown");
                logMap.put("number", number);
                logMap.put("type", callType);
                logMap.put("date", readableDate);
                logMap.put("duration", duration);
                logMap.put("simSlot", simSlot);

                firestoreManager.writeCallSync(String.valueOf(dateMillis), logMap);
                entries.add(logMap);
                count++;
            }
        } finally {
            cursor.close();
        }

        // Broadcast the full list so local-mode web app can refresh call history
        if (!entries.isEmpty()) {
            try {
                org.json.JSONObject payload = new org.json.JSONObject();
                org.json.JSONArray arr = new org.json.JSONArray();
                for (Map<String, Object> e : entries) {
                    org.json.JSONObject item = new org.json.JSONObject();
                    for (Map.Entry<String, Object> kv : e.entrySet()) {
                        item.put(kv.getKey(), kv.getValue() == null ? org.json.JSONObject.NULL : kv.getValue());
                    }
                    arr.put(item);
                }
                payload.put("entries", arr);
                LocalNetworkServer.broadcast("call_history_update", payload);
            } catch (org.json.JSONException ignored) {}
        }

        Log.d(TAG, "Call history synced: " + count + " entries");
    }

    // ─── Full snapshot on startup ─────────────────────────────────────────────

    private void pushFullSnapshot() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, ifilter);
        if (batteryStatus == null) return;

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int batteryPct = (scale > 0) ? Math.max(0, (int) ((level / (float) scale) * 100)) : 0;
        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;

        Map<String, Object> batteryInfo = new HashMap<>();
        batteryInfo.put("battery_level", batteryPct);
        batteryInfo.put("battery_status", isCharging);

        Map<String, Object> update = new HashMap<>();
        update.put("battery_level", batteryPct);
        update.put("battery_percentage", batteryPct);
        update.put("battery_status", isCharging);
        update.put("charging_status", isCharging ? "Charging" : "Discharging");
        update.put("battery_info", batteryInfo);

        // WiFi
        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        update.put("wifi", (wm != null && wm.isWifiEnabled()) ? 1 : 0);

        // Bluetooth
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        update.put("bluetooth", (bt != null && bt.isEnabled()) ? 1 : 0);

        // Hotspot
        int hotspotOn = 0;
        if (wm != null) {
            try {
                java.lang.reflect.Method isApEnabled = wm.getClass().getMethod("isWifiApEnabled");
                hotspotOn = Boolean.TRUE.equals(isApEnabled.invoke(wm)) ? 1 : 0;
            } catch (Exception ignored) {}
        }
        update.put("hotspot", hotspotOn);

        // Silent / Ringer
        int ringerMode = audioManager.getRingerMode();
        update.put("silent-mode", (ringerMode == AudioManager.RINGER_MODE_SILENT
                || ringerMode == AudioManager.RINGER_MODE_VIBRATE) ? 1 : 0);
        update.put("mute", audioManager.isMicrophoneMute() ? 1 : 0);
        update.put("speaker", audioManager.isSpeakerphoneOn() ? 1 : 0);

        // Volume
        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
        int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
        update.put("volume-level", (maxVolume > 0)
                ? (int) Math.round((currentVolume / (double) maxVolume) * 10) : 0);
        update.put("call_volume", (maxVolume > 0)
                ? (int) Math.round((currentVolume / (double) maxVolume) * 100) : 0);
        int musicMaxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int musicCurrentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        update.put("music_volume", (musicMaxVolume > 0)
                ? (int) Math.round((musicCurrentVolume / (double) musicMaxVolume) * 100) : 0);

        // Screen timeout
        try {
            int timeoutMs = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, 30000);
            update.put("screen_timeout", timeoutMs / 1000);
        } catch (Exception ignored) {}

        // Default values for controlled fields (so web always has valid data)
        update.put("sync_status", 0);
        update.put("music", 0);
        update.put("hotspot", 0);
        update.put("dial", "");

        firestoreManager.updateDeviceControls(update);
        LocalNetworkServer.broadcast("device_state", update);
    }
}
