package com.dialingassistant.agent;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * DeviceAdminReceiver required for Device Owner mode.
 *
 * To grant device-owner privileges (one-time ADB setup):
 *   adb shell dpm set-device-owner com.dialingassistant.agent/.AgentDeviceAdmin
 *
 * Once set, the app can:
 *   - Toggle WiFi via DevicePolicyManager.setWifiEnabled()      (API 34+)
 *   - Toggle Bluetooth via DevicePolicyManager.setBluetoothEnabled() (API 34+)
 */
public class AgentDeviceAdmin extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {}

    @Override
    public void onDisabled(Context context, Intent intent) {}
}
