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
import android.telephony.PhoneStateListener;
import android.telephony.SmsManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
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
    private Executor telExec;
    private Object telCallback;     // TelephonyCallback (API 31+) or PhoneStateListener
    private TelephonyManager tm;
    private String lastNumber;
    private int    lastState = TelephonyManager.CALL_STATE_IDLE;
    private WSAudioServer wsAudio;
    private Thread keepaliveThread;
    private volatile boolean keepaliveActive;
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
        startCellularCallWatcher();
        startWSAudio();
    }

    private void startWSAudio() {
        try {
            wsAudio = new WSAudioServer();
            wsAudio.start();
            Log.i(TAG, "WSAudioServer started on 127.0.0.1:8963");
        } catch (Throwable t) {
            Log.e(TAG, "WSAudioServer start failed", t);
        }
    }

    // ─── cellular-call passive watcher ────────────────────────────────────
    //
    // We don't hold the DIALER role (the F50 firmware has no DIALER role at
    // all), so we cannot answer or end cellular calls. But READ_PHONE_STATE
    // is enough to *see* the incoming call (state + number) and announce it
    // to companions over SIP. Crash-resistant: any failure here is logged
    // and the rest of the service still runs.
    private void startCellularCallWatcher() {
        try {
            tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) {
                Log.w(TAG, "TelephonyManager not available — skipping call watcher");
                return;
            }
            if (Build.VERSION.SDK_INT >= 31) {
                telExec = Executors.newSingleThreadExecutor();
                // CallStateListener on API 31+ does NOT carry the phone number
                // unless our app holds READ_PHONE_NUMBERS + the dialer role.
                // We don't, so we only see state transitions — useful enough
                // for "ringing now / hung up" signals on SIP.
                CallStateCb cb = new CallStateCb();
                tm.registerTelephonyCallback(telExec, cb);
                telCallback = cb;
                Log.i(TAG, "TelephonyCallback registered (API 31+)");
            } else {
                PhoneStateListener psl = new PhoneStateListener() {
                    @Override public void onCallStateChanged(int state, String number) {
                        handleCallState(state, number);
                    }
                };
                tm.listen(psl, PhoneStateListener.LISTEN_CALL_STATE);
                telCallback = psl;
                Log.i(TAG, "PhoneStateListener registered (legacy)");
            }
        } catch (Throwable t) {
            Log.e(TAG, "call watcher init failed", t);
        }
    }

    private class CallStateCb extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {
        @Override public void onCallStateChanged(int state) {
            handleCallState(state, lastNumber);
        }
    }

    // Modem call-keepalive — pokes the modem with AT+CLCC every ~3s while
    // OFFHOOK so the Spreadtrum CP firmware's voice-disabled-SKU timer
    // gets reset before it tears the call down at ~16-20s. We spawn
    // /system/bin/su as a fixed argv (no shell parsing of any user
    // string), which then invokes /system/bin/sendat with a fixed
    // argument — input is a hard-coded literal, no injection surface.
    private void startKeepalive() {
        if (keepaliveThread != null && keepaliveThread.isAlive()) return;
        keepaliveActive = true;
        keepaliveThread = new Thread(new Runnable() {
            @Override public void run() {
                final String[] argv = { "su", "-c", "sendat -c AT+CLCC" };
                while (keepaliveActive) {
                    try {
                        Process p = new ProcessBuilder(argv)
                                .redirectErrorStream(true).start();
                        p.waitFor();
                    } catch (Throwable t) {
                        Log.e(TAG, "keepalive run", t);
                    }
                    try { Thread.sleep(3000); }
                    catch (InterruptedException e) { return; }
                }
            }
        }, "f50sip-call-keepalive");
        keepaliveThread.setDaemon(true);
        keepaliveThread.start();
        Log.i(TAG, "call-keepalive started (3s AT+CLCC)");
    }

    private void stopKeepalive() {
        if (!keepaliveActive) return;
        keepaliveActive = false;
        if (keepaliveThread != null) keepaliveThread.interrupt();
        keepaliveThread = null;
        Log.i(TAG, "call-keepalive stopped");
    }

    private void handleCallState(int state, String number) {
        if (state == lastState) return;     // dedupe — Android fires repeats
        lastState = state;
        if (number != null && !number.isEmpty()) lastNumber = number;
        final String num = lastNumber;
        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                Log.i(TAG, "cellular RINGING from " + num);
                work.offer(new Runnable() {
                    @Override public void run() {
                        if (client != null) client.message("📞 Incoming call from " + (num == null ? "unknown" : num));
                    }
                });
                break;
            case TelephonyManager.CALL_STATE_OFFHOOK:
                Log.i(TAG, "cellular OFFHOOK");
                work.offer(new Runnable() {
                    @Override public void run() {
                        if (client != null) client.message("📞 Call connected" + (num == null ? "" : " (" + num + ")"));
                    }
                });
                startKeepalive();
                break;
            case TelephonyManager.CALL_STATE_IDLE:
                Log.i(TAG, "cellular IDLE");
                work.offer(new Runnable() {
                    @Override public void run() {
                        if (client != null) client.message("📴 Call ended");
                    }
                });
                lastNumber = null;
                stopKeepalive();
                break;
        }
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
        if (tm != null && telCallback != null) {
            try {
                if (Build.VERSION.SDK_INT >= 31 && telCallback instanceof TelephonyCallback) {
                    tm.unregisterTelephonyCallback((TelephonyCallback) telCallback);
                } else if (telCallback instanceof PhoneStateListener) {
                    tm.listen((PhoneStateListener) telCallback, PhoneStateListener.LISTEN_NONE);
                }
            } catch (Throwable ignored) {}
        }
        if (client != null) client.close();
        if (wsAudio != null) {
            try { wsAudio.close(); } catch (Throwable ignored) {}
        }
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
