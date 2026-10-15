package com.dialingassistant.agent;

import android.net.Uri;
import android.os.Build;
import android.telecom.Call;
import android.telecom.InCallService;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class CallTelemetryInCallService extends InCallService {

    private static final String TAG = "CallTelemetryService";

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        syncCall(call);
        call.registerCallback(new Call.Callback() {
            @Override
            public void onDetailsChanged(Call observedCall, Call.Details details) {
                syncCall(observedCall);
            }

            @Override
            public void onStateChanged(Call observedCall, int state) {
                syncCall(observedCall);
            }
        });
    }

    @Override
    public void onCallRemoved(Call call) {
        syncCall(call);
        super.onCallRemoved(call);
    }

    private void syncCall(Call call) {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null || call == null) {
            return;
        }

        Call.Details details = call.getDetails();
        if (details == null) {
            return;
        }

        FirestoreManager firestoreManager = new FirestoreManager(currentUser.getUid());
        int state = call.getState();
        if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
            firestoreManager.updateCallState("idle", "", "");
            firestoreManager.clearIncomingCallStatus();
            Log.d(TAG, "synced disconnected call as idle");
            return;
        }

        Uri handle = details.getHandle();
        String rawNumber = "";
        if (handle != null && "tel".equals(handle.getScheme())) {
            rawNumber = handle.getSchemeSpecificPart();
        }

        int direction = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? details.getCallDirection()
                : Call.Details.DIRECTION_UNKNOWN;
        boolean isOutgoing = direction == Call.Details.DIRECTION_OUTGOING
                || (direction == Call.Details.DIRECTION_UNKNOWN && isLikelyOutgoing(call));

        if (!isOutgoing || rawNumber == null || rawNumber.trim().isEmpty()) {
            return;
        }

        String number = rawNumber.trim();
        firestoreManager.clearIncomingCallStatus();
        firestoreManager.updateCallState("offhook", number, "outgoing");
        Log.d(TAG, "synced live outgoing call: " + number + " state=" + state);
    }

    private boolean isLikelyOutgoing(Call call) {
        int state = call.getState();
        return state == Call.STATE_DIALING
                || state == Call.STATE_CONNECTING
                || state == Call.STATE_ACTIVE;
    }
}
