package com.dialingassistant.agent;

import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * Centralises the Realtime Database interactions needed by the lite (Call + SMS) build
 * for a single agent. Device-control, music, and notification-mirror nodes used by the
 * full app are intentionally omitted here.
 *
 * Realtime Database schema (under agents/{agentId}/):
 *   state/info                  – {number, status, cmdId, simSlot}  (web → device dial/hangup)
 *   state/call_status           – {answer}                          (web → device answer/decline)
 *   state/call_state            – {state, number, direction, timestamp}  (device → web)
 *   state/incoming_call_status  – {mobile, username, simSlot}
 *   call_sync/{dateMillis}      – call log entry
 *   sms_messages/{msgId}        – individual SMS
 *   sms_threads/{threadId}      – per-thread metadata
 *   state/sms_send_cmd          – outgoing SMS command (web → device)
 *   state/sms_sync_req          – manual full-resync request (web → device)
 *   state/device_controls       – {call_volume_cmd, music_cmd, mirror_req}  (web → device, self-clearing)
 *                                 + {mirror_ip, mirror_ip_ts}               (device → web: LAN IP for scrcpy)
 */
public class FirestoreManager {

    private static final String TAG = "FirestoreManager";
    private final String agentId;
    private final FirebaseDatabase db;

    public final DatabaseReference infoRef;
    public final DatabaseReference callStatusRef;
    public final DatabaseReference deviceControlsRef;
    public final DatabaseReference incomingCallStatusRef;
    public final DatabaseReference callSyncRef;
    public final DatabaseReference callStateRef;
    public final DatabaseReference smsThreadsRef;
    public final DatabaseReference smsMessagesRef;
    public final DatabaseReference smsSendCmdRef;
    public final DatabaseReference smsSyncReqRef;
    public final DatabaseReference fcmTokensRef;

    public interface SnapshotListener {
        void onDataChange(DataSnapshot snapshot, DatabaseError error);
    }

    public FirestoreManager(String agentId) {
        this.agentId = agentId;
        this.db = FirebaseDatabase.getInstance();
        String base = "agents/" + agentId;
        infoRef = db.getReference(base + "/state/info");
        callStatusRef = db.getReference(base + "/state/call_status");
        deviceControlsRef = db.getReference(base + "/state/device_controls");
        incomingCallStatusRef = db.getReference(base + "/state/incoming_call_status");
        callSyncRef = db.getReference(base + "/call_sync");
        callStateRef = db.getReference(base + "/state/call_state");
        smsThreadsRef = db.getReference(base + "/sms_threads");
        smsMessagesRef = db.getReference(base + "/sms_messages");
        smsSendCmdRef = db.getReference(base + "/state/sms_send_cmd");
        smsSyncReqRef = db.getReference(base + "/state/sms_sync_req");
        fcmTokensRef = db.getReference(base + "/fcm_tokens");

        // Keep the command nodes synced so that when the websocket reconnects (e.g. after
        // an FCM wake pulls the app out of Doze) the latest dial/answer/volume command is
        // fetched immediately, instead of waiting for the value-listener to re-prime.
        infoRef.keepSynced(true);
        callStatusRef.keepSynced(true);
        deviceControlsRef.keepSynced(true);
    }

    /**
     * Registers this device's FCM registration token under agents/{uid}/fcm_tokens so the
     * web app / chrome-extension can send it a high-priority wake message on dial. The raw
     * token is stored as the value under a Firebase-safe key.
     */
    public Task<Void> registerFcmToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        return fcmTokensRef.child(sanitizeFirebaseKey(token)).setValue(token)
                .addOnFailureListener(e -> Log.e(TAG, "FCM token register failed: " + e.getMessage()));
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

    public ListenerRegistration listenToIncomingCallStatus(SnapshotListener listener) {
        return listen(incomingCallStatusRef, listener);
    }

    public ListenerRegistration listenToCallStatus(SnapshotListener listener) {
        return listen(callStatusRef, listener);
    }

    public ListenerRegistration listenToDeviceControls(SnapshotListener listener) {
        return listen(deviceControlsRef, listener);
    }

    /**
     * Seed the RTDB nodes used by the lite app so initial listeners and merge/update
     * writes always have a place to land.
     */
    public Task<Void> seedInitialStateDocuments() {
        Map<String, Object> infoDefaults = new HashMap<>();
        infoDefaults.put("number", "");
        infoDefaults.put("status", 2);

        Map<String, Object> callStatusDefaults = new HashMap<>();
        callStatusDefaults.put("answer", 2);

        Map<String, Object> incomingCallDefaults = new HashMap<>();
        incomingCallDefaults.put("mobile", "");
        incomingCallDefaults.put("username", "");

        Map<String, Object> callStateDefaults = new HashMap<>();
        callStateDefaults.put("state", "idle");
        callStateDefaults.put("number", "");
        callStateDefaults.put("direction", "unknown");

        Map<String, Object> batteryInfoDefaults = new HashMap<>();
        batteryInfoDefaults.put("battery_level", 0);
        batteryInfoDefaults.put("battery_status", false);

        Map<String, Object> deviceControlDefaults = new HashMap<>();
        deviceControlDefaults.put("battery_level", 0);
        deviceControlDefaults.put("battery_percentage", 0);
        deviceControlDefaults.put("battery_status", false);
        deviceControlDefaults.put("charging_status", "Unknown");
        deviceControlDefaults.put("battery_info", batteryInfoDefaults);
        deviceControlDefaults.put("volume-level", 0);
        deviceControlDefaults.put("call_volume", 0);
        deviceControlDefaults.put("music_volume", 0);
        deviceControlDefaults.put("speaker", 0);
        deviceControlDefaults.put("mute", 0);
        deviceControlDefaults.put("sync_status", 0);
        deviceControlDefaults.put("music", 0);
        deviceControlDefaults.put("music_state", "idle");
        deviceControlDefaults.put("music_title", "");
        deviceControlDefaults.put("music_artist", "");
        deviceControlDefaults.put("music_package", "");
        deviceControlDefaults.put("dial", "");

        Map<String, Object> updates = new HashMap<>();
        updates.put("state/info", infoDefaults);
        updates.put("state/call_status", callStatusDefaults);
        updates.put("state/incoming_call_status", incomingCallDefaults);
        updates.put("state/call_state", callStateDefaults);
        updates.put("state/device_controls", deviceControlDefaults);

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

    public Task<Void> updateDeviceControls(Map<String, Object> data) {
        return deviceControlsRef.updateChildren(data)
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
        // No waiting call → always clear the waiting fields so a resolved
        // call-waiting episode can't leave a stale banner on the web.
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

    public Task<Void> writeCallSync(String key, Map<String, Object> logMap) {
        return callSyncRef.child(key).setValue(logMap)
                .addOnSuccessListener(v -> Log.d(TAG, "Call sync written: " + key))
                .addOnFailureListener(e -> Log.e(TAG, "Call sync write failed: " + e.getMessage()));
    }

    public String getAgentId() {
        return agentId;
    }

    // ── SMS ──────────────────────────────────────────────────────────────────

    private String sanitizeFirebaseKey(String key) {
        if (key == null || key.isEmpty()) return "";
        return key.replaceAll("[.#$\\[\\]/]", "_");
    }

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
}
