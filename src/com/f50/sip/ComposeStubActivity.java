package com.f50.sip;

import android.app.Activity;
import android.os.Bundle;

/** Required by the default SMS role spec — an activity handling SEND /
 *  SENDTO for sms: / mms: URIs. We accept the intent and immediately
 *  finish; headless device, no compose UI. */
public class ComposeStubActivity extends Activity {
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        finish();
    }
}
