package com.f50.sip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.telephony.SmsManager;
import android.util.Log;

import java.util.concurrent.LinkedBlockingQueue;

/** Long-running foreground service that:
 *  - Maintains SIP registration (re-REGISTER every cfg.registerExpires/2 seconds).
 *  - Listens for incoming SIP MESSAGE / INVITE on the bound UDP socket.
 *  - Forwards incoming SMS / call events out as SIP MESSAGE / INVITE.
 *  - Sends an outbound SMS via the cellular SIM when a SIP MESSAGE
 *    arrives whose body looks like `SMS:+90XXX:message body`.
 *
 *  Crash-restart by Android on its own; we don't supervise ourselves.
 */
public class SipForegroundService extends Service {
    private static final String TAG = "F50SIP";
    private static final String CHAN_ID = "f50sip";
    private static final int NOTIF_ID = 1;

    public static final String ACT_INCOMING_CALL = "f50sip.ACT_INCOMING_CALL";
    public static final String ACT_SMS_FORWARD   = "f50sip.ACT_SMS_FORWARD";

    private SipClient client;
    private Thread listenThread, regThread, workerThread;
    private final LinkedBlockingQueue<Runnable> work = new LinkedBlockingQueue<>();
    private volatile boolean running;
    private PowerManager.WakeLock wakeLock;

    // ─── public helpers (static so other components can fire intents) ──────

    public static void notifyIncomingCall(Context ctx, String number) {
        Intent i = new Intent(ctx, SipForegroundService.class);
        i.setAction(ACT_INCOMING_CALL);
        i.putExtra("number", number);
        startFg(ctx, i);
    }

    public static void forwardSms(Context ctx, String from, String body) {
        Intent i = new Intent(ctx, SipForegroundService.class);
        i.setAction(ACT_SMS_FORWARD);
        i.putExtra("from", from);
        i.putExtra("body", body);
        startFg(ctx, i);
    }

    private static void startFg(Context ctx, Intent i) {
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else                              ctx.startService(i);
    }

    // ─── lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIF_ID, buildNotif("starting"));
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "F50SIP::wl");
        wakeLock.acquire();
        running = true;
        startWorker();
        startSip();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            final String action = intent.getAction();
            final String num = intent.getStringExtra("number");
            final String from = intent.getStringExtra("from");
            final String body = intent.getStringExtra("body");
            work.offer(new Runnable() {
                @Override public void run() {
                    if (client == null) return;
                    if (ACT_INCOMING_CALL.equals(action)) {
                        Log.i(TAG, "→ SIP MESSAGE for call from " + num);
                        client.message("📞 Incoming call from " + (num == null ? "?" : num));
                    } else if (ACT_SMS_FORWARD.equals(action)) {
                        Log.i(TAG, "→ SIP MESSAGE for SMS from " + from);
                        client.message("📨 SMS from " + from + ":\n" + body);
                    }
                }
            });
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        if (client != null) client.close();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ─── SIP startup ───────────────────────────────────────────────────────

    private void startSip() {
        SipClient.Config cfg = SipConfig.load(this);
        if (!SipConfig.isComplete(cfg)) {
            Log.w(TAG, "config incomplete — drop /sdcard/f50sip.conf with server_host/username/password/forward_target");
            updateNotif("config missing — see /sdcard/f50sip.conf");
            return;
        }
        try {
            client = new SipClient(cfg);
        } catch (Exception e) {
            Log.e(TAG, "socket failed", e);
            updateNotif("socket bind failed: " + e.getMessage());
            return;
        }

        // Handle incoming SIP MESSAGE → may be a SMS-send command:
        //   SMS:+90555...:Hello body here
        final SipClient clientRef = client;
        client.onMessage = new SipClient.OnIncomingMessage() {
            @Override public void onMessage(String from, String body) {
                Log.i(TAG, "SIP MESSAGE from " + from + ": " + body);
                if (body != null && body.startsWith("SMS:")) {
                    int p1 = body.indexOf(':', 4);
                    if (p1 > 4) {
                        String to = body.substring(4, p1).trim();
                        String text = body.substring(p1 + 1);
                        try {
                            SmsManager sm = SmsManager.getDefault();
                            sm.sendTextMessage(to, null, text, null, null);
                            clientRef.message(from, "ack: sent to " + to);
                            Log.i(TAG, "outbound SMS to " + to + " (" + text.length() + " chars)");
                        } catch (Exception e) {
                            Log.e(TAG, "outbound SMS failed", e);
                            clientRef.message(from, "err: " + e.getMessage());
                        }
                    }
                }
            }
        };

        listenThread = new Thread(new Runnable() {
            @Override public void run() { clientRef.listenLoop(); }
        }, "f50sip-listen");
        listenThread.setDaemon(true);
        listenThread.start();

        final SipClient.Config cfgRef = cfg;
        regThread = new Thread(new Runnable() {
            @Override public void run() {
                while (running) {
                    boolean ok = clientRef.register();
                    updateNotif(ok ? "registered as " + cfgRef.username + "@" + cfgRef.serverHost
                                  : "register failed (will retry)");
                    try { Thread.sleep((ok ? cfgRef.registerExpires : 60) * 1000L / 2); }
                    catch (InterruptedException e) { return; }
                }
            }
        }, "f50sip-register");
        regThread.setDaemon(true);
        regThread.start();
    }

    private void startWorker() {
        workerThread = new Thread(new Runnable() {
            @Override public void run() {
                while (running) {
                    try { work.take().run(); }
                    catch (InterruptedException e) { return; }
                    catch (Throwable t) { Log.e(TAG, "worker", t); }
                }
            }
        }, "f50sip-worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    // ─── notification ──────────────────────────────────────────────────────

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHAN_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHAN_ID, "F50 SIP Bridge", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Keeps SIP registration alive");
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotif(String state) {
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHAN_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("F50 SIP Bridge")
                .setContentText(state)
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setOngoing(true)
                .build();
    }

    private void updateNotif(String state) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotif(state));
        } catch (Exception ignored) {}
    }
}
