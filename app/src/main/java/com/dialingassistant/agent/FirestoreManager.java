package com.dialingassistant.agent;

import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * Centralises all Realtime Database interactions for a single agent.
 *
 * Realtime Database schema (under agents/{agentId}/):
 *   state/info                  – {number, status}
 *   state/call_status            – {answer}
 *   state/work_status            – {status, last_status}
 *   state/device_controls        – {wifi, bluetooth, …, battery_info:{…}}
 *   state/incoming_call_status   – {mobile, username}
 *   state/call_state             – {state, number, direction, timestamp}
 *   call_sync/{timestampKey}     – call log entry
 *   notifications/{postTimeMs}   – notification mirror
 *   state/notification_actions   – action command from web
 */
public class FirestoreManager {

    private static final String TAG = "FirestoreManager";
    private final String agentId;
    private final FirebaseDatabase db;

    public final DatabaseReference infoRef;
    public final DatabaseReference callStatusRef;
    public final DatabaseReference workStatusRef;
    public final DatabaseReference deviceControlsRef;
    public final DatabaseReference incomingCallStatusRef;
    public final DatabaseReference callSyncRef;
    public final DatabaseReference notificationsRef;
    public final DatabaseReference callStateRef;
    public final DatabaseReference notificationActionsRef;
    public final DatabaseReference notificationActionQueueRef;
    public final DatabaseReference smsThreadsRef;
    public final DatabaseReference smsMessagesRef;
    public final DatabaseReference smsSendCmdRef;
    public final DatabaseReference smsSyncReqRef;
    public final DatabaseReference localIpRef;

    public interface SnapshotListener {
        void onDataChange(DataSnapshot snapshot, DatabaseError error);
    }

    public FirestoreManager(String agentId) {
        this.agentId = agentId;
        this.db = FirebaseDatabase.getInstance();
        String base = "agents/" + agentId;
        infoRef = db.getReference(base + "/state/info");
        callStatusRef = db.getReference(base + "/state/call_status");
        workStatusRef = db.getReference(base + "/state/work_status");
        deviceControlsRef = db.getReference(base + "/state/device_controls");
        incomingCallStatusRef = db.getReference(base + "/state/incoming_call_status");
        callSyncRef = db.getReference(base + "/call_sync");
        notificationsRef = db.getReference(base + "/notifications");
        callStateRef = db.getReference(base + "/state/call_state");
        notificationActionsRef = db.getReference(base + "/state/notification_actions");
        notificationActionQueueRef = db.getReference(base + "/state/notification_actions_queue");
        smsThreadsRef = db.getReference(base + "/sms_threads");
        smsMessagesRef = db.getReference(base + "/sms_messages");
        smsSendCmdRef = db.getReference(base + "/state/sms_send_cmd");
        smsSyncReqRef = db.getReference(base + "/state/sms_sync_req");
        localIpRef = db.getReference(base + "/state/local_ip");
    }

    // ── Listener helpers ────────────────────────────────────────────────────

    private ListenerRegistration listen(DatabaseReference ref, SnapshotListener listener) {
        ValueEventListener valueEventListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                listener.onDataChange(snapshot, null);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                listener.onDataChange(null, error);
            }
        };
        ref.addValueEventListener(valueEventListener);
        return new ListenerRegistration(() -> ref.removeEventListener(valueEventListener));
    }

    public ListenerRegistration listenToInfo(SnapshotListener listener) {
        return listen(infoRef, listener);
    }

    public ListenerRegistration listenToWorkStatus(SnapshotListener listener) {
        return listen(workStatusRef, listener);
    }

    public ListenerRegistration listenToDeviceControls(SnapshotListener listener) {
        return listen(deviceControlsRef, listener);
    }

    public ListenerRegistration listenToCallSync(SnapshotListener listener) {
        return listen(callSyncRef, listener);
    }

    public ListenerRegistration listenToIncomingCallStatus(SnapshotListener listener) {
        return listen(incomingCallStatusRef, listener);
    }

    public ListenerRegistration listenToCallStatus(SnapshotListener listener) {
        return listen(callStatusRef, listener);
    }

    public ListenerRegistration listenToNotificationActions(SnapshotListener listener) {
        return listen(notificationActionsRef, listener);
    }

    public ListenerRegistration listenToNotificationActionQueue(SnapshotListener listener) {
        ChildEventListener childEventListener = new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot snapshot, String previousChildName) {
                listener.onDataChange(snapshot, null);
            }

            @Override
            public void onChildChanged(DataSnapshot snapshot, String previousChildName) {}

            @Override
            public void onChildRemoved(DataSnapshot snapshot) {}

            @Override
            public void onChildMoved(DataSnapshot snapshot, String previousChildName) {}

            @Override
            public void onCancelled(DatabaseError error) {
                listener.onDataChange(null, error);
            }
        };
        notificationActionQueueRef.addChildEventListener(childEventListener);
        return new ListenerRegistration(() -> notificationActionQueueRef.removeEventListener(childEventListener));
    }

    /**
     * Seed the RTDB nodes used by the app so initial listeners and merge/update
     * writes always have a place to land.
     */
    public Task<Void> seedInitialStateDocuments() {
        Map<String, Object> infoDefaults = new HashMap<>();
        infoDefaults.put("number", "");
        infoDefaults.put("status", 2);

        Map<String, Object> callStatusDefaults = new HashMap<>();
        callStatusDefaults.put("answer", 2);

        Map<String, Object> workStatusDefaults = new HashMap<>();
        workStatusDefaults.put("status", 2);
        workStatusDefaults.put("last_status", 2);

        Map<String, Object> batteryInfoDefaults = new HashMap<>();
        batteryInfoDefaults.put("battery_level", 0);
        batteryInfoDefaults.put("battery_status", false);

        Map<String, Object> deviceControlDefaults = new HashMap<>();
        deviceControlDefaults.put("battery_level", 0);
        deviceControlDefaults.put("battery_percentage", 0);
        deviceControlDefaults.put("battery_status", false);
        deviceControlDefaults.put("charging_status", "Unknown");
        deviceControlDefaults.put("battery_info", batteryInfoDefaults);
        deviceControlDefaults.put("wifi", 0);
        deviceControlDefaults.put("bluetooth", 0);
        deviceControlDefaults.put("hotspot", 0);
        deviceControlDefaults.put("silent-mode", 0);
        deviceControlDefaults.put("mute", 0);
        deviceControlDefaults.put("speaker", 0);
        deviceControlDefaults.put("volume-level", 0);
        deviceControlDefaults.put("call_volume", 0);
        deviceControlDefaults.put("music_volume", 0);
        deviceControlDefaults.put("sync_status", 0);
        deviceControlDefaults.put("music", 0);
        deviceControlDefaults.put("music_state", "idle");
        deviceControlDefaults.put("music_title", "");
        deviceControlDefaults.put("music_artist", "");
        deviceControlDefaults.put("music_package", "");
        deviceControlDefaults.put("dial", "");

        Map<String, Object> incomingCallDefaults = new HashMap<>();
        incomingCallDefaults.put("mobile", "");
        incomingCallDefaults.put("username", "");

        Map<String, Object> callStateDefaults = new HashMap<>();
        callStateDefaults.put("state", "idle");
        callStateDefaults.put("number", "");
        callStateDefaults.put("direction", "unknown");

        Map<String, Object> notificationActionDefaults = new HashMap<>();
        notificationActionDefaults.put("action", "");
        notificationActionDefaults.put("sbnKey", "");
        notificationActionDefaults.put("replyText", "");
        notificationActionDefaults.put("actionIndex", 0);

        Map<String, Object> updates = new HashMap<>();
        updates.put("state/info", infoDefaults);
        updates.put("state/call_status", callStatusDefaults);
        updates.put("state/work_status", workStatusDefaults);
        updates.put("state/device_controls", deviceControlDefaults);
        updates.put("state/incoming_call_status", incomingCallDefaults);
        updates.put("state/call_state", callStateDefaults);
        updates.put("state/notification_actions", notificationActionDefaults);

        return db.getReference("agents/" + agentId).updateChildren(updates);
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    public Task<Void> updateInfo(Map<String, Object> data) {
        return infoRef.updateChildren(data);
    }

    public Task<Void> setInfo(Map<String, Object> data) {
        return infoRef.setValue(data);
    }

    public Task<Void> updateCallStatus(int answer) {
        Map<String, Object> data = new HashMap<>();
        data.put("answer", answer);
        return callStatusRef.updateChildren(data);
    }

    public Task<Void> updateWorkStatus(int status) {
        Map<String, Object> data = new HashMap<>();
        data.put("status", status);
        return workStatusRef.updateChildren(data);
    }

    public Task<Void> updateDeviceControls(Map<String, Object> data) {
        return deviceControlsRef.updateChildren(data)
                .addOnSuccessListener(v -> Log.d(TAG, "Device controls updated"))
                .addOnFailureListener(e -> Log.e(TAG, "Device controls update failed: " + e.getMessage()));
    }

    public Task<Void> updateDeviceControlField(String field, Object value) {
        return deviceControlsRef.child(field).setValue(value);
    }

    public Task<Void> clearDeviceControlField(String field) {
        return deviceControlsRef.child(field).removeValue();
    }

    public Task<Void> updateIncomingCallStatus(String mobile, String username) {
        Map<String, Object> data = new HashMap<>();
        data.put("mobile", mobile);
        data.put("username", username);
        return incomingCallStatusRef.updateChildren(data);
    }

    public Task<Void> clearIncomingCallStatus() {
        Map<String, Object> data = new HashMap<>();
        data.put("mobile", "");
        data.put("username", "");
        return incomingCallStatusRef.updateChildren(data);
    }

    public Task<Void> updateCallState(String state, String number) {
        Map<String, Object> data = new HashMap<>();
        data.put("state", state);
        data.put("number", number != null ? number : "");
        data.put("timestamp", ServerValue.TIMESTAMP);
        return callStateRef.updateChildren(data);
    }

    public Task<Void> updateCallState(String state, String number, String direction) {
        return updateCallState(state, number, direction, "", "");
    }

    public Task<Void> updateCallState(String state, String number, String direction,
                                      String waitingNumber, String waitingName) {
        Map<String, Object> data = new HashMap<>();
        data.put("state", state);
        data.put("number", number != null ? number : "");
        data.put("direction", direction != null ? direction : "unknown");
        data.put("waitingNumber", waitingNumber != null ? waitingNumber : "");
        data.put("waitingName", waitingName != null ? waitingName : "");
        data.put("timestamp", ServerValue.TIMESTAMP);
        return callStateRef.updateChildren(data);
    }

    // ── Notification mirror ──────────────────────────────────────────────────

    private String sanitizeFirebaseKey(String key) {
        if (key == null || key.isEmpty()) return "";
        return key.replaceAll("[.#$\\[\\]/]", "_");
    }

    public Task<Void> writeNotification(String key, Map<String, Object> data) {
        return notificationsRef.child(sanitizeFirebaseKey(key)).setValue(data)
                .addOnFailureListener(e -> Log.e(TAG, "Notification write failed: " + e.getMessage()));
    }

    public Task<Void> removeNotification(String key) {
        return notificationsRef.child(sanitizeFirebaseKey(key)).removeValue()
                .addOnFailureListener(e -> Log.e(TAG, "Notification delete failed: " + e.getMessage()));
    }

    public Task<Void> clearNotificationAction() {
        return notificationActionsRef.removeValue()
                .addOnFailureListener(e -> Log.e(TAG, "Clear notification action failed: " + e.getMessage()));
    }

    public Task<Void> writeCallSync(String key, Map<String, Object> logMap) {
        return callSyncRef.child(key).setValue(logMap)
                .addOnSuccessListener(v -> Log.d(TAG, "Call sync written: " + key))
                .addOnFailureListener(e -> Log.e(TAG, "Call sync write failed: " + e.getMessage()));
    }

    public String getAgentId() {
        return agentId;
    }

    // ── SMS ──────────────────────────────────────────────────────────────────

    public ListenerRegistration listenToSmsSendCmd(SnapshotListener listener) {
        return listen(smsSendCmdRef, listener);
    }

    public Task<Void> writeSmsMessage(String msgId, Map<String, Object> data) {
        return smsMessagesRef.child(sanitizeFirebaseKey(msgId)).setValue(data)
                .addOnFailureListener(e -> Log.e(TAG, "SMS message write failed: " + e.getMessage()));
    }

    public Task<Void> updateSmsThread(String threadId, Map<String, Object> data) {
        return smsThreadsRef.child(sanitizeFirebaseKey(threadId)).updateChildren(data)
                .addOnFailureListener(e -> Log.e(TAG, "SMS thread update failed: " + e.getMessage()));
    }

    public Task<Void> updateSmsSendCmdStatus(String cmdId, String status) {
        Map<String, Object> update = new HashMap<>();
        update.put("status", status);
        update.put("processedAt", ServerValue.TIMESTAMP);
        return smsSendCmdRef.updateChildren(update)
                .addOnFailureListener(e -> Log.e(TAG, "SMS send cmd status update failed: " + e.getMessage()));
    }

    public ListenerRegistration listenToSmsSyncRequest(SnapshotListener listener) {
        return listen(smsSyncReqRef, listener);
    }

    public void clearSmsSyncRequest() {
        smsSyncReqRef.removeValue()
                .addOnFailureListener(e -> Log.e(TAG, "Clear sms_sync_req failed: " + e.getMessage()));
    }

    public Task<Void> writeLocalIp(Map<String, Object> data) {
        return localIpRef.setValue(data)
                .addOnFailureListener(e -> Log.e(TAG, "writeLocalIp failed: " + e.getMessage()));
    }
}
