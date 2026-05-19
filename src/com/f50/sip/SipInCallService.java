package com.f50.sip;

import android.telecom.Call;
import android.telecom.InCallService;
import android.util.Log;

/** Hooks Android's Telecom framework. The system delivers Call objects
 *  here whenever the F50's cellular SIM rings or places a call. We just
 *  observe — we never answer, hang up, or play audio. On a RINGING
 *  incoming, we forward the event to SipForegroundService so it can
 *  push a SIP MESSAGE / INVITE to the configured forward target. */
public class SipInCallService extends InCallService {
    private static final String TAG = "F50SIP";

    private final Call.Callback callCB = new Call.Callback() {
        @Override
        public void onStateChanged(Call call, int state) {
            String num = handleOf(call);
            Log.i(TAG, "call state → " + state + " (num=" + num + ")");
            if (state == Call.STATE_RINGING) {
                SipForegroundService.notifyIncomingCall(SipInCallService.this, num);
            }
        }
    };

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        String num = handleOf(call);
        int state = call.getState();
        Log.i(TAG, "onCallAdded: state=" + state + " num=" + num);
        call.registerCallback(callCB);
        if (state == Call.STATE_RINGING) {
            SipForegroundService.notifyIncomingCall(this, num);
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        call.unregisterCallback(callCB);
    }

    private static String handleOf(Call c) {
        try {
            if (c.getDetails() != null && c.getDetails().getHandle() != null) {
                return c.getDetails().getHandle().getSchemeSpecificPart();
            }
        } catch (Exception ignored) {}
        return "unknown";
    }
}
