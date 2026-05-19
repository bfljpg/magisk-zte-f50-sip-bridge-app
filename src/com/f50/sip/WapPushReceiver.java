package com.f50.sip;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Required by the default SMS role spec (MMS path). We don't handle
 *  MMS — drop silently. */
public class WapPushReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        // intentionally empty
    }
}
