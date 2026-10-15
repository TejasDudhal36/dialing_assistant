package com.dialingassistant.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Captures the phone number for device-initiated outgoing calls.
 * This fires before the call is placed, giving us the number to sync to Firestore.
 */
public class OutgoingCallReceiver extends BroadcastReceiver {

    private static final String TAG = "OutgoingCallReceiver";

    /** Last outgoing number dialed from the device (not via web command). Thread-safe read. */
    public static volatile String lastOutgoingNumber = "";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_NEW_OUTGOING_CALL.equals(intent.getAction())) return;
        String number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
        if (number != null && !number.isEmpty()) {
            lastOutgoingNumber = number;
            Log.d(TAG, "Outgoing call captured: " + number);
        }
    }
}
