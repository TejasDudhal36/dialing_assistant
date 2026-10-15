package com.dialingassistant.agent;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Syncs the most recent call-log entries to RTDB (agents/{uid}/call_sync/{dateMillis}).
 *
 * This is a focused extraction of the call-history portion of the full app's
 * DeviceStateManager — the battery / WiFi / Bluetooth / volume listeners are
 * intentionally omitted from the lite build.
 *
 * A debounced ContentObserver on the call log keeps history fresh; syncCallHistory()
 * is also invoked directly by CallBackgroundService when a call ends.
 */
public class CallHistorySync {

    private static final String TAG = "CallHistorySync";
    private static final int MAX_ENTRIES = 10;
    /** Entries older than this are deleted from RTDB — nothing else prunes call_sync. */
    private static final long RETENTION_MS = 90L * 24 * 60 * 60 * 1000;
    private static final long PRUNE_INTERVAL_MS = 24L * 60 * 60 * 1000;

    private long lastPruneAt = 0L;

    private final Context context;
    private final FirestoreManager firestoreManager;
    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private ContentObserver callLogObserver;
    private Runnable callLogSyncRunnable;

    public CallHistorySync(Context context, FirestoreManager firestoreManager) {
        this.context = context.getApplicationContext();
        this.firestoreManager = firestoreManager;
    }

    public void register() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CALL_LOG not granted; call-history sync disabled");
            return;
        }
        callLogObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                final Runnable current = callLogSyncRunnable;
                if (current != null) debounceHandler.removeCallbacks(current);
                final Runnable next = CallHistorySync.this::syncCallHistory;
                callLogSyncRunnable = next;
                debounceHandler.postDelayed(next, 2000);
            }
        };
        context.getContentResolver().registerContentObserver(
                CallLog.Calls.CONTENT_URI, true, callLogObserver);
        Log.d(TAG, "Call log observer registered");
    }

    public void unregister() {
        try { context.getContentResolver().unregisterContentObserver(callLogObserver); } catch (Exception ignored) {}
        debounceHandler.removeCallbacksAndMessages(null);
    }

    @SuppressLint("MissingPermission")
    private Map<Integer, Integer> buildSubIdToSlotMap() {
        Map<Integer, Integer> map = new HashMap<>();
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) return map;
        SubscriptionManager sm = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        if (sm == null) return map;
        try {
            List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
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

        Map<Integer, Integer> subIdToSlot = buildSubIdToSlotMap();

        Cursor cursor = context.getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                new String[]{
                        CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE,
                        CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.PHONE_ACCOUNT_ID,
                },
                null, null,
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

        SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

        int count = 0;
        try {
            while (cursor.moveToNext() && count < MAX_ENTRIES) {
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

                String readableDate = isoFormat.format(new Date(dateMillis));

                Map<String, Object> logMap = new HashMap<>();
                logMap.put("name", name != null ? name : "Unknown");
                logMap.put("number", number);
                logMap.put("type", callType);
                logMap.put("date", readableDate);
                logMap.put("duration", duration);
                logMap.put("simSlot", simSlot);

                firestoreManager.writeCallSync(String.valueOf(dateMillis), logMap);
                count++;
            }
        } finally {
            cursor.close();
        }

        Log.d(TAG, "Call history synced: " + count + " entries");
        pruneOldEntriesIfDue();
    }

    /**
     * Deletes call_sync entries older than RETENTION_MS. Nothing else ever prunes
     * this node, so without retention every client downloads an ever-growing
     * history on each load. Keys are the entry's epoch millis, so an orderByKey
     * range query selects exactly the expired children.
     */
    private void pruneOldEntriesIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastPruneAt < PRUNE_INTERVAL_MS) return;
        lastPruneAt = now;

        String cutoffKey = String.valueOf(now - RETENTION_MS);
        firestoreManager.callSyncRef.orderByKey().endAt(cutoffKey).get()
                .addOnSuccessListener(snapshot -> {
                    if (!snapshot.exists()) return;
                    Map<String, Object> deletions = new HashMap<>();
                    for (com.google.firebase.database.DataSnapshot child : snapshot.getChildren()) {
                        if (child.getKey() != null) deletions.put(child.getKey(), null);
                    }
                    if (deletions.isEmpty()) return;
                    int n = deletions.size();
                    firestoreManager.callSyncRef.updateChildren(deletions)
                            .addOnSuccessListener(v -> Log.d(TAG, "Pruned " + n + " old call_sync entries"))
                            .addOnFailureListener(e -> Log.w(TAG, "call_sync prune failed: " + e.getMessage()));
                })
                .addOnFailureListener(e -> Log.w(TAG, "call_sync prune query failed: " + e.getMessage()));
    }
}
