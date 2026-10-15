package com.dialingassistant.agent;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

final class ServiceStarter {

    private ServiceStarter() {}

    static void startCallBackgroundService(Context context) {
        Intent serviceIntent = new Intent(context, CallBackgroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}
