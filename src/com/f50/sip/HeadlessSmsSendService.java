package com.f50.sip;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Required by the default SMS role spec. Android sends RESPOND_VIA_MESSAGE
 *  intents here when the user "respond with text" during a missed call.
 *  We just no-op — headless device, no quick-replies. */
public class HeadlessSmsSendService extends Service {
    @Override public IBinder onBind(Intent intent) { return null; }
}
