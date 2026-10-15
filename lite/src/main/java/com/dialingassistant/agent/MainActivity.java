package com.dialingassistant.agent;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private Button btn_start_service, btn_stop_service, btn_logout;
    private boolean startupPermissionDialogShowing = false;

    // Lite permission set — only what's needed to read SMS and perform calling operations.
    private final String[] permissions = {
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
    };

    private final String[] startupRuntimePermissions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ? new String[] { Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS,
                             Manifest.permission.SEND_SMS, Manifest.permission.POST_NOTIFICATIONS }
            : new String[] { Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS,
                             Manifest.permission.SEND_SMS };

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // If not authenticated, go to login
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        // Request draw-over-other-apps permission (needed for call trigger overlay)
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, 0);
        }

        btn_start_service = findViewById(R.id.btn_start_service);
        btn_stop_service   = findViewById(R.id.btn_stop_service);
        btn_logout         = findViewById(R.id.btn_logout);

        checkPermissions();
        requestBatteryOptimizationExemption();
        if (hasStartupPermissions()) {
            ServiceStarter.startCallBackgroundService(this);
        } else {
            showStartupPermissionDialog();
        }

        btn_start_service.setOnClickListener(v -> {
            ServiceStarter.startCallBackgroundService(MainActivity.this);
            Toast.makeText(this, "Service Started!", Toast.LENGTH_SHORT).show();
            updateServiceButtonState();
        });

        btn_stop_service.setOnClickListener(v -> {
            stopService(new Intent(MainActivity.this, CallBackgroundService.class));
            Toast.makeText(this, "Service Stopped!", Toast.LENGTH_SHORT).show();
            updateServiceButtonState();
        });

        btn_logout.setOnClickListener(v -> logout());
        updateServiceButtonState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasStartupPermissions()) {
            startupPermissionDialogShowing = false;
            if (hasRequiredPermissions()) {
                ServiceStarter.startCallBackgroundService(this);
            }
        } else if (!startupPermissionDialogShowing) {
            showStartupPermissionDialog();
        }
        updateServiceButtonState();
    }

    private void logout() {
        // Stop background service so RTDB listeners are cleaned up
        stopService(new Intent(this, CallBackgroundService.class));
        // Sign out of Firebase
        FirebaseAuth.getInstance().signOut();
        // Go to login screen, clear the back stack
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void checkPermissions() {
        boolean permissionsNeeded = false;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded = true;
                break;
            }
        }
        if (permissionsNeeded) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
        } else {
            Log.d(TAG, "All permissions already granted");
        }
    }

    private boolean hasStartupPermissions() {
        return hasRuntimeStartupPermissions();
    }

    private boolean hasRuntimeStartupPermissions() {
        for (String permission : startupRuntimePermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void showStartupPermissionDialog() {
        if (startupPermissionDialogShowing || isFinishing()) return;
        startupPermissionDialogShowing = true;

        String message = "To sync SMS and handle calls, please grant:\n\n"
                + "• Phone permission (place & answer calls)\n"
                + "• SMS permission (read & send)\n"
                + "• Notification permission\n\n"
                + "The app will guide you through the required system screens.";

        new AlertDialog.Builder(this)
                .setTitle("Set Up Permissions")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Grant Now", (dialog, which) -> {
                    startupPermissionDialogShowing = false;
                    requestStartupPermissions();
                })
                .setNegativeButton("Later", (dialog, which) -> {
                    startupPermissionDialogShowing = false;
                    dialog.dismiss();
                })
                .show();
    }

    private void requestStartupPermissions() {
        if (!hasRuntimeStartupPermissions()) {
            ActivityCompat.requestPermissions(this, startupRuntimePermissions, PERMISSION_REQUEST_CODE);
            return;
        }
        if (hasRequiredPermissions()) {
            ServiceStarter.startCallBackgroundService(this);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) { allGranted = false; break; }
            }
            if (allGranted) {
                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show();
                if (hasStartupPermissions() && hasRequiredPermissions()) {
                    ServiceStarter.startCallBackgroundService(this);
                }
            } else {
                Toast.makeText(this, "Some permissions were denied. The app may not function correctly.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean hasRequiredPermissions() {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void updateServiceButtonState() {
        boolean running = isServiceRunning();
        btn_start_service.setEnabled(!running);
        btn_stop_service.setEnabled(running);
        btn_start_service.setAlpha(running ? 0.5f : 1.0f);
        btn_stop_service.setAlpha(running ? 1.0f : 0.5f);
    }

    @SuppressWarnings("deprecation")
    private boolean isServiceRunning() {
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am == null) return false;
        for (android.app.ActivityManager.RunningServiceInfo svc : am.getRunningServices(Integer.MAX_VALUE)) {
            if (CallBackgroundService.class.getName().equals(svc.service.getClassName())) return true;
        }
        return false;
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager == null || powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }

        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Unable to request battery optimization exemption", e);
        }
    }
}
