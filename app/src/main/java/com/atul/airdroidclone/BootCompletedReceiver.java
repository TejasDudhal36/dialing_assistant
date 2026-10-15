package com.dialingassistant.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;

public class BootCompletedReceiver extends BroadcastReceiver {

    private static final String TAG = "BootCompletedReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Log.d(TAG, "Skipping service start on " + action + ": no authenticated user");
            return;
        }

        Log.d(TAG, "Starting service after " + action);
        ServiceStarter.startCallBackgroundService(context);
    }
}
