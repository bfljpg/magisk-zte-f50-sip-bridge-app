package com.f50.sip;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.ConnectivityManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/** Loads SIP credentials from /sdcard/f50sip.conf (preferred for headless
 *  setup) or SharedPreferences fallback. Also resolves a useful local IPv4
 *  for SIP Via / Contact headers. */
public final class SipConfig {

    private static final String PREFS = "f50sip";

    public static SipClient.Config load(Context ctx) {
        SipClient.Config c = new SipClient.Config();

        // Defaults
        c.serverPort = 5060;
        c.localPort = 5060;
        c.registerExpires = 1800;

        // SharedPreferences fallback
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        c.serverHost    = p.getString("serverHost",    null);
        c.username      = p.getString("username",      null);
        c.password      = p.getString("password",      null);
        c.forwardTarget = p.getString("forwardTarget", null);

        // File-based overrides, tried in order. The internal files dir
        // (/data/data/com.f50.sip/files/f50sip.conf) is the one we
        // recommend — root can drop the file there and the app reads
        // it without runtime storage permissions. /sdcard is kept as
        // a legacy fallback for older installs.
        File conf = pickConf(ctx);
        if (conf != null && conf.canRead()) {
            try (BufferedReader r = new BufferedReader(new FileReader(conf))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    String k = line.substring(0, eq).trim();
                    String v = line.substring(eq + 1).trim();
                    if (v.startsWith("\"") && v.endsWith("\""))
                        v = v.substring(1, v.length() - 1);
                    switch (k) {
                        case "server_host":     c.serverHost = v; break;
                        case "server_port":     c.serverPort = Integer.parseInt(v); break;
                        case "username":        c.username = v; break;
                        case "password":        c.password = v; break;
                        case "forward_target":  c.forwardTarget = v; break;
                        case "local_port":      c.localPort = Integer.parseInt(v); break;
                        case "register_expires":c.registerExpires = Integer.parseInt(v); break;
                    }
                }
            } catch (Exception e) {
                // best-effort; SharedPreferences may still have valid values
            }
        }

        c.localIp = bestLocalIp(ctx);
        return c;
    }

    private static File pickConf(Context ctx) {
        File[] candidates = new File[] {
            new File(ctx.getFilesDir(), "f50sip.conf"),
            new File(ctx.getExternalFilesDir(null), "f50sip.conf"),
            new File("/sdcard/f50sip.conf"),
        };
        for (File f : candidates) {
            if (f != null && f.canRead()) return f;
        }
        return null;
    }

    public static boolean isComplete(SipClient.Config c) {
        return c != null
            && c.serverHost != null && !c.serverHost.isEmpty()
            && c.username   != null && !c.username.isEmpty()
            && c.password   != null && !c.password.isEmpty()
            && c.forwardTarget != null && !c.forwardTarget.isEmpty();
    }

    private static String bestLocalIp(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n == null) return "127.0.0.1";
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) return "127.0.0.1";
            for (LinkAddress la : lp.getLinkAddresses()) {
                String host = la.getAddress().getHostAddress();
                if (host == null) continue;
                if (host.indexOf(':') >= 0) continue;   // IPv6
                if (host.startsWith("127.")) continue;
                return host;
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }
}
