package com.dialingassistant.agent;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * Lifecycle wrapper for {@link LocalNetworkServer}.
 *
 * Start it from {@link CallBackgroundService} after Firebase auth succeeds.
 * It will:
 *   1. Resolve the device's current WiFi IP address.
 *   2. Start the HTTPS + WebSocket server on {@link LocalNetworkServer#PORT}.
 *   3. Write the IP + port to Firebase so the web app can discover the device
 *      even when internet connectivity is temporarily lost.
 */
public class LocalNetworkManager {

    private static final String TAG = "LocalNetworkManager";

    private LocalNetworkServer server;
    private String localIp;

    // ── Callback interface (implemented by CallBackgroundService) ─────────────

    public interface ServiceCallbacks {
        void onDial(String number);
        void onHangup();
        void onAnswer();
        void onSms(String to, String body, String cmdId);
        void onWifi(int value);
        void onBluetooth(int value);
        void onHotspot(int value);
        void onCallVolume(int value);
        void onMusic(int value);
    }

    // ── Start / stop ──────────────────────────────────────────────────────────

    public void start(Context context, FirestoreManager firestoreManager,
                      ServiceCallbacks callbacks) {
        localIp = getWifiIpAddress(context);

        try {
            server = new LocalNetworkServer(context, new LocalNetworkServer.CommandHandler() {
                @Override public void onDial(String number)                      { callbacks.onDial(number); }
                @Override public void onHangup()                                 { callbacks.onHangup(); }
                @Override public void onAnswer()                                 { callbacks.onAnswer(); }
                @Override public void onSms(String to, String body, String id)  { callbacks.onSms(to, body, id); }
                @Override public void onWifi(int value)                          { callbacks.onWifi(value); }
                @Override public void onBluetooth(int value)                     { callbacks.onBluetooth(value); }
                @Override public void onHotspot(int value)                       { callbacks.onHotspot(value); }
                @Override public void onCallVolume(int value)                    { callbacks.onCallVolume(value); }
                @Override public void onMusic(int value)                         { callbacks.onMusic(value); }
            });
            server.start();
            Log.d(TAG, "Local server started — ip=" + localIp
                    + " port=" + LocalNetworkServer.PORT);

            // Publish IP to Firebase so the web can pre-cache it for fast reconnect
            if (firestoreManager != null && localIp != null) {
                Map<String, Object> data = new HashMap<>();
                data.put("ip",        localIp);
                data.put("port",      LocalNetworkServer.PORT);
                data.put("updatedAt", System.currentTimeMillis());
                firestoreManager.writeLocalIp(data);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start local server: " + e.getMessage(), e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop();
            server = null;
            Log.d(TAG, "Local server stopped");
        }
    }

    public String getLocalIp() { return localIp; }

    // ── WiFi IP helper ────────────────────────────────────────────────────────

    private static String getWifiIpAddress(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return null;
            int ip = info.getIpAddress();
            if (ip == 0) return null;
            // Android stores IP as little-endian int
            byte[] bytes = {
                (byte)  (ip        & 0xff),
                (byte) ((ip >>  8) & 0xff),
                (byte) ((ip >> 16) & 0xff),
                (byte) ((ip >> 24) & 0xff)
            };
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (Exception e) {
            Log.e(TAG, "getWifiIpAddress failed: " + e.getMessage());
            return null;
        }
    }
}
