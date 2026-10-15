package com.dialingassistant.agent;

import android.service.notification.NotificationListenerService;

/**
 * Minimal NotificationListenerService.
 *
 * The lite build does NOT mirror notifications. This component exists solely so the
 * app can be granted "Notification Access", which is the prerequisite for
 * {@link android.media.session.MediaSessionManager#getActiveSessions(android.content.ComponentName)}
 * — the API CallBackgroundService uses to read and control active media sessions
 * (music play/pause/next/previous/volume).
 */
public class NotificationListener extends NotificationListenerService {
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        try {
            ServiceStarter.startCallBackgroundService(this);
        } catch (Exception ignored) {}
    }
}
