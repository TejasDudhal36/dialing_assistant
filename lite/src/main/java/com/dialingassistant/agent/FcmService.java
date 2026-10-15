package com.dialingassistant.agent;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/**
 * Receives high-priority FCM data messages sent by the web app / chrome-extension when they
 * write a dial command. A high-priority data message is allowed to wake the app from Doze,
 * which gives CallBackgroundService a network window to pull the just-written command node
 * from RTDB and place the call — without waiting for the next Doze maintenance window.
 *
 * The dial payload itself still travels over RTDB (state/info); this push only wakes the
 * device and ensures the listener is connected.
 */
public class FcmService extends FirebaseMessagingService {

    private static final String TAG = "FcmService";

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        Log.d(TAG, "FCM wake received: " + message.getData());
        // Force the RTDB client back online (Doze may have parked it) and bring the
        // foreground service up so its listeners reconnect and read the pending command.
        try {
            FirebaseDatabase.getInstance().goOnline();
        } catch (Exception e) {
            Log.w(TAG, "goOnline failed: " + e.getMessage());
        }
        try {
            ServiceStarter.startCallBackgroundService(this);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start service from FCM wake: " + e.getMessage());
        }
    }

    @Override
    public void onNewToken(@NonNull String token) {
        Log.d(TAG, "New FCM token");
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            new FirestoreManager(user.getUid()).registerFcmToken(token);
        }
    }
}
