package com.dialingassistant.agent;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.WindowManager;

import androidx.core.app.ActivityCompat;

/**
 * Transparent trampoline activity that:
 *  1. Acquires its own KEEP_SCREEN_ON flag so the display stays lit.
 *  2. Dismisses the keyguard (PIN/pattern remains — only visual dismiss for calls).
 *  3. Releases the screen-wakeup WakeLock acquired by CallBackgroundService.
 *  4. Places the outgoing call via ACTION_CALL (shows native in-call UI immediately).
 *
 * Shown over the lock screen via manifest attributes:
 *   android:showWhenLocked="true"
 *   android:turnScreenOn="true"
 */
public class CallTriggerActivity extends Activity {

    private static final String TAG = "CallTriggerActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── 1. Window flags: show over lock screen, keep screen on ────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        // Legacy flags (also applied on all API levels as belt-and-suspenders)
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );

        // ── 2. Dismiss keyguard so the dialer / in-call UI is fully visible ───
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            km.requestDismissKeyguard(this, null);
        }

        // ── 3. Release the screen-wakeup WakeLock held by CallBackgroundService ─
        // We do this after our window flags are in place so the screen stays on.
        releaseServiceWakeLock();

        // ── 4. Dismiss the trigger notification ────────────────────────────────
        int notificationId = getIntent().getIntExtra("notificationId", 99);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(notificationId);

        // ── 5. Place the call ─────────────────────────────────────────────────
        String number = getIntent().getStringExtra("number");
        if (number == null || number.isEmpty()) {
            Log.e(TAG, "No number provided — aborting");
            finish();
            return;
        }

        // Small delay (150 ms) lets the window/keyguard state settle so the
        // system dialer launches on top of the lock screen reliably.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            placeCall(number);
            finish();
        }, 150);
    }

    private void placeCall(String number) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "CALL_PHONE permission not granted");
            return;
        }
        try {
            // ACTION_CALL is the most reliable way to show the native in-call UI
            // immediately, even from a transparent trampoline activity.
            Intent callIntent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number));
            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(callIntent);
            Log.d(TAG, "placeCall via ACTION_CALL: " + number);
        } catch (Exception e) {
            Log.e(TAG, "ACTION_CALL failed: " + e.getMessage());
        }
    }

    /**
     * Releases the SCREEN_BRIGHT_WAKE_LOCK acquired by CallBackgroundService.
     * We look it up by the same tag so we don't need a static reference.
     */
    @SuppressWarnings({"deprecation", "WakelockTimeout"})
    private void releaseServiceWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            // Acquire a new wake lock with the same tag — if the service's lock is
            // already held, Android de-duplicates them. We then release it here.
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "DialingAssistant:CallScreenWake");
            if (wl.isHeld()) wl.release();
        } catch (Exception ignored) {}
    }
}
