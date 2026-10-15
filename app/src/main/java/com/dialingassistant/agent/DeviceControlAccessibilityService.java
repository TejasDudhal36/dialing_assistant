package com.dialingassistant.agent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Accessibility service that toggles WiFi and Bluetooth by interacting with
 * the Quick Settings panel — the only reliable approach on Android 10+/13+
 * without Device Owner or root.
 *
 * One-time setup (device Settings → Accessibility → Dialing Assistant Controls → ON).
 */
public class DeviceControlAccessibilityService extends AccessibilityService {

    private static final String TAG = "DeviceCtrlA11y";

    // Tile label fragments matched case-insensitively across Android skins
    private static final String LABEL_WIFI      = "wi-fi";
    private static final String LABEL_WIFI_ALT  = "wifi";
    private static final String LABEL_BT        = "bluetooth";

    public enum Toggle { WIFI_ON, WIFI_OFF, BT_ON, BT_OFF }

    private static volatile DeviceControlAccessibilityService sInstance;

    private volatile Toggle pendingToggle = null;
    private int retryCount = 0;
    private static final int MAX_RETRIES = 5;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable retryRunnable = this::attemptClick;

    // ── Public API ──────────────────────────────────────────────────────────

    public static boolean isAvailable() {
        return sInstance != null;
    }

    public static boolean requestToggle(Toggle toggle) {
        DeviceControlAccessibilityService svc = sInstance;
        if (svc == null) return false;
        svc.startToggle(toggle);
        return true;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    protected void onServiceConnected() {
        sInstance = this;
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 100;
        setServiceInfo(info);
        Log.d(TAG, "Service connected");
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        handler.removeCallbacks(retryRunnable);
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {}

    // ── Toggle flow ─────────────────────────────────────────────────────────

    private void startToggle(Toggle toggle) {
        handler.post(() -> {
            pendingToggle = toggle;
            retryCount = 0;
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
            // Give the panel time to open, then attempt to click the tile
            handler.postDelayed(retryRunnable, 600);
        });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Events arrive while panel is animating open; attemptClick handles retries
    }

    private void attemptClick() {
        if (pendingToggle == null) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && clickTile(root, pendingToggle)) {
            pendingToggle = null;
            retryCount = 0;
            // Collapse QS after the toggle registers
            handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 400);
            Log.d(TAG, "Tile clicked successfully");
            return;
        }

        retryCount++;
        if (retryCount < MAX_RETRIES) {
            handler.postDelayed(retryRunnable, 300);
        } else {
            Log.w(TAG, "Tile not found after " + MAX_RETRIES + " retries for " + pendingToggle);
            pendingToggle = null;
            retryCount = 0;
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
    }

    // ── Node search ─────────────────────────────────────────────────────────

    private boolean clickTile(AccessibilityNodeInfo root, Toggle toggle) {
        String label = isWifi(toggle) ? LABEL_WIFI : LABEL_BT;
        String labelAlt = isWifi(toggle) ? LABEL_WIFI_ALT : null;

        // findAccessibilityNodeInfosByText does a case-insensitive substring match
        if (tryClick(root, label)) return true;
        if (labelAlt != null && tryClick(root, labelAlt)) return true;
        return false;
    }

    private boolean tryClick(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        for (AccessibilityNodeInfo node : nodes) {
            AccessibilityNodeInfo target = findClickable(node);
            if (target != null) {
                boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (ok) return true;
            }
        }
        return false;
    }

    private AccessibilityNodeInfo findClickable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isClickable()) return node;
        // Walk up to find the nearest clickable ancestor
        AccessibilityNodeInfo parent = node.getParent();
        int depth = 0;
        while (parent != null && depth < 6) {
            if (parent.isClickable()) return parent;
            parent = parent.getParent();
            depth++;
        }
        return null;
    }

    private boolean isWifi(Toggle toggle) {
        return toggle == Toggle.WIFI_ON || toggle == Toggle.WIFI_OFF;
    }
}
