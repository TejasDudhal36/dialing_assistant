package com.dialingassistant.agent;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.BroadcastReceiver.PendingResult;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.ContactsContract;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.FirebaseDatabase;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Receives phone state broadcasts and syncs incoming call info to RTDB.
 *
 * Note: BroadcastReceivers have a very short lifecycle. All RTDB writes
 * here are one-shot (fire-and-forget) rather than subscribing to listeners.
 * The CallBackgroundService owns the persistent RTDB listeners.
 */
public class CallReceiver extends BroadcastReceiver {

    private static final String TAG = "CallReceiver";

    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pendingResult = goAsync();
        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        Log.d(TAG, "Phone state: " + state);

        if (!TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            pendingResult.finish();
            return;
        }

        String incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
        if (incomingNumber == null) {
            pendingResult.finish();
            return;
        }

        // Resolve UID: try Firebase Auth first, fall back to SharedPreferences.
        // We stored the UID on login so it's available even after process death.
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        String uid = null;
        if (currentUser != null) {
            uid = currentUser.getUid();
            // Keep SharedPreferences in sync while Firebase Auth is available.
            context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)
                   .edit().putString("uid", uid).apply();
        } else {
            uid = context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)
                         .getString("uid", null);
            if (uid != null) {
                Log.w(TAG, "Firebase Auth not ready — using cached UID from SharedPreferences");
            } else {
                Log.w(TAG, "No UID available — push will broadcast to all subscribers");
            }
        }

        String callerName = getContactName(context, incomingNumber);
        if (callerName == null) callerName = "";

        int simSlot = detectIncomingSimSlot(context, intent);

        // Write incoming call status to RTDB (fire-and-forget, requires UID).
        if (uid != null) {
            Map<String, Object> data = new HashMap<>();
            data.put("mobile", incomingNumber);
            data.put("username", callerName);
            data.put("simSlot", simSlot);
            FirebaseDatabase.getInstance()
                    .getReference("agents/" + uid + "/state/incoming_call_status")
                    .setValue(data)
                    .addOnSuccessListener(v -> Log.d(TAG, "Incoming call status written"))
                    .addOnFailureListener(e -> Log.e(TAG, "Failed to write incoming call status: " + e.getMessage()));
            // Mirror to local WebSocket clients when offline
            LocalNetworkServer.broadcast("incoming_call", data);
        }

        // Send Web Push directly to the Next.js server route so closed tabs
        // still receive the notification without relying on any open browser UI.
        final String finalUid = uid != null ? uid : "";
        sendWebPush(incomingNumber, callerName, System.currentTimeMillis(), finalUid, pendingResult);

        // NOTE: We intentionally do NOT read call_status/answer here to auto-answer or
        // reject. `answer` is a transient command, not a desired-state flag — reading it
        // at RINGING time would act on a stale value left over from a previous call (e.g.
        // auto-answering a brand-new incoming call). The live answer/reject command is
        // delivered by CallBackgroundService.callStatusListener while the service runs.
    }

    @SuppressLint("MissingPermission")
    private static int detectIncomingSimSlot(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return -1;
        int subId = intent.getIntExtra(TelephonyManager.EXTRA_SUBSCRIPTION_ID,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return -1;
        return slotForSubscription(context, subId);
    }

    @SuppressLint("MissingPermission")
    private static int slotForSubscription(Context context, int subId) {
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return -1;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return -1;
        SubscriptionManager sm = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        if (sm == null) return -1;
        try {
            SubscriptionInfo si = sm.getActiveSubscriptionInfo(subId);
            return (si != null) ? si.getSimSlotIndex() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    @SuppressLint("Range")
    private static String getContactName(Context context, String phoneNumber) {
        if (phoneNumber != null && !phoneNumber.startsWith("+") && phoneNumber.replaceAll("[^0-9]", "").length() <= 10) {
            phoneNumber = "+91" + phoneNumber;
        }
        ContentResolver cr = context.getContentResolver();
        Uri uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber));
        Cursor cursor = cr.query(uri,
                new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME},
                null, null, null);
        if (cursor == null) return null;
        String name = null;
        try {
            if (cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME);
                if (idx >= 0) name = cursor.getString(idx);
            }
        } finally {
            cursor.close();
        }
        return name;
    }

    /**
     * POST to /api/incoming-call-push on the Next.js server so that every
     * registered browser receives a push notification, even if all tabs are closed.
     *
     * Uses goAsync() so the receiver stays alive until the network request completes.
     */
    private static void sendWebPush(String mobile, String username, long calledAt, String agentId, PendingResult pendingResult) {
        Thread t = new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String endpoint = BuildConfig.WEB_APP_URL + "/api/incoming-call-push";
                conn = (HttpURLConnection) new URL(endpoint).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("X-Notification-Secret", BuildConfig.NOTIFICATION_SECRET);
                conn.setDoOutput(true);
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);

                String safeUsername = username.replace("\\", "\\\\").replace("\"", "\\\"");
                String safeAgentId = agentId != null ? agentId.replace("\\", "\\\\").replace("\"", "\\\"") : "";
                String body = "{\"mobile\":\"" + mobile + "\","
                        + "\"username\":\"" + safeUsername + "\","
                        + "\"calledAt\":" + calledAt + ","
                        + "\"agentId\":\"" + safeAgentId + "\"}";

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                Log.d(TAG, "Web push sent — HTTP " + code);
            } catch (Exception e) {
                Log.e(TAG, "Web push failed: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
                pendingResult.finish();
            }
        });
        t.start();
    }

}
