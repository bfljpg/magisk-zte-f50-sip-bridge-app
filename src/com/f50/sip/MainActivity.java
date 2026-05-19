package com.f50.sip;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

/** Minimal activity. F50 is headless — this exists only to satisfy the
 *  "must have launcher activity" requirement for default-dialer /
 *  default-SMS role requests, and to be a manual entry point if anyone
 *  flashes the APK onto a normal phone for testing. */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        TextView tv = new TextView(this);
        tv.setText("F50 SIP Bridge\n\n"
                 + "Headless service. SIP config:\n"
                 + "  /sdcard/f50sip.conf\n\n"
                 + "Required keys:\n"
                 + "  server_host = sip.example.com\n"
                 + "  username    = user\n"
                 + "  password    = pass\n"
                 + "  forward_target = sip:companion@host\n\n"
                 + "To grant default-dialer + default-SMS roles, run from adb:\n"
                 + "  cmd role add-role-holder android.app.role.DIALER com.f50.sip\n"
                 + "  cmd role add-role-holder android.app.role.SMS com.f50.sip");
        setContentView(tv);

        // On Android Q+ also try the in-app role prompt as a fallback for
        // non-headless installs.
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                RoleManager rm = (RoleManager) getSystemService(Context.ROLE_SERVICE);
                if (rm != null) {
                    if (rm.isRoleAvailable(RoleManager.ROLE_DIALER) && !rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
                        startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), 1);
                    }
                    if (rm.isRoleAvailable(RoleManager.ROLE_SMS) && !rm.isRoleHeld(RoleManager.ROLE_SMS)) {
                        startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_SMS), 2);
                    }
                }
            } catch (Exception ignored) { /* headless device, no UI */ }
        }
    }
}
