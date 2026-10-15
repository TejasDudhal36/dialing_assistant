package com.dialingassistant.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.HashMap;
import java.util.Map;

/**
 * Receives incoming SMS broadcasts and syncs them to Firebase RTDB immediately.
 * Works in tandem with SmsSyncManager which handles full-history sync and outgoing.
 */
public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!android.provider.Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.w(TAG, "No authenticated user — dropping incoming SMS event");
            return;
        }

        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        Object[] pdus = (Object[]) bundle.get("pdus");
        String format = bundle.getString("format");
        if (pdus == null || pdus.length == 0) return;

        // Reconstruct potentially multi-part message
        StringBuilder bodyBuilder = new StringBuilder();
        String sender = null;
        long timestamp = System.currentTimeMillis();

        for (Object pdu : pdus) {
            SmsMessage msg = SmsMessage.createFromPdu((byte[]) pdu, format);
            if (msg != null) {
                if (sender == null) sender = msg.getDisplayOriginatingAddress();
                bodyBuilder.append(msg.getDisplayMessageBody());
                timestamp = msg.getTimestampMillis();
            }
        }

        if (sender == null) {
            Log.w(TAG, "SmsReceiver: sender null, dropping");
            return;
        }

        String body = bodyBuilder.toString();
        String msgId = timestamp + "_" + Math.abs(sender.hashCode());
        String threadId = sanitizeKey(sender);

        FirestoreManager fm = new FirestoreManager(user.getUid());

        Map<String, Object> msgData = new HashMap<>();
        msgData.put("id", msgId);
        msgData.put("threadId", threadId);
        msgData.put("address", sender);
        msgData.put("displayName", sender);
        msgData.put("body", body);
        msgData.put("timestamp", timestamp);
        msgData.put("direction", "incoming");
        msgData.put("status", "received");
        msgData.put("read", false);
        fm.writeSmsMessage(msgId, msgData);

        Map<String, Object> threadData = new HashMap<>();
        threadData.put("id", threadId);
        threadData.put("address", sender);
        threadData.put("displayName", sender);
        threadData.put("lastMessage", body);
        threadData.put("lastTimestamp", timestamp);
        threadData.put("direction", "incoming");
        fm.updateSmsThread(threadId, threadData);

        Log.d(TAG, "Incoming SMS from " + sender + " → Firebase");
    }

    private String sanitizeKey(String key) {
        if (key == null || key.isEmpty()) return "unknown";
        return key.replaceAll("[.#$\\[\\]/+\\s]", "_");
    }
}
