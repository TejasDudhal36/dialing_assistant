package com.dialingassistant.agent;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

public class BatteryUtils {

    public static class BatteryInfo {
        public int batteryPercentage;
        public boolean isCharging;

        public BatteryInfo(int batteryPercentage, boolean isCharging) {
            this.batteryPercentage = batteryPercentage;
            this.isCharging = isCharging;
        }
    }

    public static BatteryInfo getBatteryInfo(Context context) {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, ifilter);

        if (batteryStatus == null) return new BatteryInfo(0, false);

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);

        int batteryPct = (scale > 0) ? Math.max(0, (int) ((level / (float) scale) * 100)) : 0;
        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL;

        return new BatteryInfo(batteryPct, isCharging);
    }
}
