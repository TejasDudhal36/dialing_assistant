package com.dialingassistant.agent;

import android.app.Application;
import android.util.Log;

import com.google.firebase.database.FirebaseDatabase;

/**
 * Application entry point. Enables Realtime Database disk persistence as early as possible
 * (before any other component touches FirebaseDatabase), so the command listeners survive
 * process restarts and reconnect quickly after the device leaves Doze.
 */
public class App extends Application {

    private static final String TAG = "App";

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true);
        } catch (Exception e) {
            // setPersistenceEnabled throws if the database was already touched; safe to ignore.
            Log.w(TAG, "setPersistenceEnabled skipped: " + e.getMessage());
        }
    }
}
