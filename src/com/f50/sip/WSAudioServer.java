package com.f50.sip;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal WebSocket audio server bound to 127.0.0.1:8963.
 *
 * sipserver (Go) is the WebSocket *client*: it connects, expects unmasked
 * server→client frames of binary PCM Int16, 2-channel interleaved, 48 kHz.
 * Each frame's payload is appended into the sipserver's downsample buffer,
 * which then turns it into μ-law / 8 kHz / mono RTP for the SIP side.
 *
 * For now this server:
 *  - Accepts the handshake (RFC 6455 server side).
 *  - Spawns a capture thread that tries AudioRecord(VOICE_CALL) and falls
 *    back to AudioRecord(MIC) if the SDK rejects VOICE_CALL (lack of
 *    CAPTURE_AUDIO_OUTPUT signature permission).
 *  - Streams 48 kHz stereo PCM frames out at ~20 ms cadence.
 *  - Reads (and currently discards) inbound frames; later they will be
 *    played into STREAM_VOICE_CALL via AudioTrack so the cellular peer
 *    can hear the SIP user.
 *
 * Lifecycle: started by SipForegroundService.onCreate, killed by close().
 * Designed to survive being connected to repeatedly — each accept() spawns
 * a fresh capture/playback pair.
 */
public final class WSAudioServer implements Runnable {
    private static final String TAG = "F50SIP.WSAudio";
    private static final int PORT = 8963;
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS    = 2;
    private static final int FRAME_MS    = 20;
    private static final int FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000;
    private static final int FRAME_BYTES   = FRAME_SAMPLES * CHANNELS * 2;

    private final Context appCtx;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket server;
    private Thread acceptThread;
    
    public WSAudioServer(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }
    
    public void start() {
        if (running.getAndSet(true)) return;
        acceptThread = new Thread(this, "f50sip-wsaudio-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void close() {
        running.set(false);
        if (server != null) {
            try { server.close(); } catch (IOException ignored) {}
        }
    }

    @Override
    public void run() {
        try {
            server = new ServerSocket(PORT, 4, java.net.InetAddress.getByName("127.0.0.1"));
            Log.i(TAG, "listening on 127.0.0.1:" + PORT);
            while (running.get()) {
                final Socket client = server.accept();
                Log.i(TAG, "client connected: " + client.getInetAddress() + ":" + client.getPort());
                Thread t = new Thread(new Runnable() {
                    @Override public void run() { handleClient(client); }
                }, "f50sip-wsaudio-client");
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            if (running.get()) Log.e(TAG, "accept loop", e);
        }
    }

private void handleClient(Socket sock) {
    try {
        sock.setSoTimeout(0);
        InputStream in = sock.getInputStream();
        OutputStream out = sock.getOutputStream();

        if (!handshake(in, out)) {
            Log.w(TAG, "handshake rejected");
            sock.close();
            return;
        }
        Log.i(TAG, "handshake OK, starting audio bridge");

        enableAcousticRoute();

        CaptureLoop cap = new CaptureLoop(out);
        Thread capT = new Thread(cap, "f50sip-wsaudio-capture");
        capT.setDaemon(true);
        capT.start();

        try {
            ReadLoop reader = new ReadLoop(in);
            reader.run();
        } finally {
            cap.stop();
            restoreAudioRoute();
        }
    } catch (Exception e) {
        Log.e(TAG, "client", e);
    } finally {
        try { sock.close(); } catch (IOException ignored) {}
    }
}

    private void enableAcousticRoute() {
    try {
        AudioManager am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        am.setSpeakerphoneOn(true);
        Log.i(TAG, "acoustic route: speakerphone ON, mode=IN_COMMUNICATION");
    } catch (Throwable t) {
        Log.w(TAG, "enableAcousticRoute failed", t);
    }
}

private void restoreAudioRoute() {
    try {
        AudioManager am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
        am.setSpeakerphoneOn(false);
        am.setMode(AudioManager.MODE_NORMAL);
    } catch (Throwable ignored) {}
}

    // ─── handshake (server side, RFC 6455) ─────────────────────────────────

    private boolean handshake(InputStream in, OutputStream out) throws IOException {
        StringBuilder req = new StringBuilder();
        int b, prev = -1;
        // Read until \r\n\r\n
        int consec = 0;
        while ((b = in.read()) != -1) {
            req.append((char) b);
            if ((prev == '\r' && b == '\n')) {
                consec++;
                if (consec == 2) break;
            } else if (b != '\r') {
                consec = 0;
            }
            prev = b;
        }
        String text = req.toString();
        if (!text.startsWith("GET ")) return false;

        String key = null;
        for (String line : text.split("\r\n")) {
            int c = line.indexOf(':');
            if (c < 0) continue;
            String name = line.substring(0, c).trim().toLowerCase();
            String val  = line.substring(c + 1).trim();
            if (name.equals("sec-websocket-key")) { key = val; break; }
        }
        if (key == null) return false;

        String accept = computeAcceptKey(key);
        String resp =
                "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + accept + "\r\n" +
                "\r\n";
        out.write(resp.getBytes("US-ASCII"));
        out.flush();
        return true;
    }

    private static String computeAcceptKey(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("US-ASCII"));
            return Base64.getEncoder().encodeToString(sha1.digest());
        } catch (Exception e) {
            return "";
        }
    }

    // ─── server→client write: unmasked binary frames ───────────────────────

    static void writeFrame(OutputStream out, byte[] payload) throws IOException {
        ByteBuffer hdr = ByteBuffer.allocate(10);
        hdr.put((byte) 0x82);    // FIN + binary
        int len = payload.length;
        if (len < 126) {
            hdr.put((byte) len);
        } else if (len < 65536) {
            hdr.put((byte) 126);
            hdr.putShort((short) len);
        } else {
            hdr.put((byte) 127);
            hdr.putLong(len);
        }
        out.write(hdr.array(), 0, hdr.position());
        out.write(payload);
        out.flush();
    }

    // ─── client→server frame reader (masked) ───────────────────────────────

    private static final class ReadLoop {
        final InputStream in;
        ReadLoop(InputStream in) { this.in = in; }

        void run() throws IOException {
            byte[] hdr = new byte[2];
            byte[] mask = new byte[4];
            byte[] payload = new byte[65536];
            while (true) {
                if (readFull(in, hdr, 2) < 2) break;
                int op = hdr[0] & 0x0F;
                boolean masked = (hdr[1] & 0x80) != 0;
                int len = hdr[1] & 0x7F;
                if (len == 126) {
                    byte[] ext = new byte[2];
                    if (readFull(in, ext, 2) < 2) break;
                    len = ((ext[0] & 0xFF) << 8) | (ext[1] & 0xFF);
                } else if (len == 127) {
                    byte[] ext = new byte[8];
                    if (readFull(in, ext, 8) < 8) break;
                    long ll = 0;
                    for (int i = 0; i < 8; i++) ll = (ll << 8) | (ext[i] & 0xFF);
                    if (ll > payload.length) ll = payload.length;
                    len = (int) ll;
                }
                if (masked && readFull(in, mask, 4) < 4) break;
                if (len > payload.length) len = payload.length;
                if (readFull(in, payload, len) < len) break;
                if (masked) {
                    for (int i = 0; i < len; i++) payload[i] ^= mask[i & 3];
                }
                if (op == 0x8) break;             // close
                // op 0x2 (binary) — inbound audio from sipserver→F50: TODO
            }
        }

        private static int readFull(InputStream in, byte[] buf, int n) throws IOException {
            int got = 0;
            while (got < n) {
                int r = in.read(buf, got, n - got);
                if (r < 0) return got;
                got += r;
            }
            return got;
        }
    }

    // ─── PCM capture loop ──────────────────────────────────────────────────

    private static final class CaptureLoop implements Runnable {
        private final OutputStream out;
        private volatile boolean alive = true;
        CaptureLoop(OutputStream out) { this.out = out; }

        void stop() { alive = false; }

        @Override
        public void run() {
            int bufSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (bufSize <= 0) bufSize = FRAME_BYTES * 4;
            // VOICE_CALL is the only useful source for cellular bridging —
            // it tees the call's downlink (and on some HALs also the uplink
            // mic) without stealing the mic from the modem. It requires the
            // signature permission CAPTURE_AUDIO_OUTPUT, which an unsigned
            // APK cannot hold.
            //
            // Fallback to MIC is *broken* in practice on cellular: opening
            // AudioRecord(MIC) while a GSM call is up tells AudioFlinger
            // that an app wants the microphone — the routing layer takes
            // it from the modem and gives it to us. The modem then sees
            // silence on uplink and tears the call down at ~20 s. So we
            // explicitly do NOT fall back to MIC any more.
            AudioRecord rec = tryOpen(MediaRecorder.AudioSource.VOICE_CALL, bufSize);
            String src = "VOICE_CALL";
            if (rec == null || rec.getState() != AudioRecord.STATE_INITIALIZED) {
                if (rec != null) rec.release();
                Log.w(TAG, "VOICE_CALL unavailable — falling back to MIC (acoustic loopback)");
                rec = tryOpen(MediaRecorder.AudioSource.MIC, bufSize);
                src = "MIC";
                if (rec == null || rec.getState() != AudioRecord.STATE_INITIALIZED) {
                    if (rec != null) rec.release();
                    Log.w(TAG, "MIC also unavailable — silence mode");
                    sendSilence();
                    return;
                }
            }
            Log.i(TAG, "AudioRecord source=" + src + " buf=" + bufSize);
            try {
                rec.startRecording();
                byte[] frame = new byte[FRAME_BYTES];
                while (alive) {
                    int total = 0;
                    while (total < FRAME_BYTES) {
                        int r = rec.read(frame, total, FRAME_BYTES - total);
                        if (r <= 0) { alive = false; break; }
                        total += r;
                    }
                    if (!alive) break;
                    try { writeFrame(out, frame); } catch (IOException e) { alive = false; }
                }
            } catch (Exception e) {
                Log.e(TAG, "capture", e);
            } finally {
                try { rec.stop(); } catch (Exception ignored) {}
                rec.release();
                Log.i(TAG, "capture stopped (" + src + ")");
            }
        }

        private AudioRecord tryOpen(int source, int bufSize) {
            try {
                return new AudioRecord(
                        source,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(bufSize, FRAME_BYTES * 4));
            } catch (Throwable t) {
                Log.w(TAG, "AudioRecord(" + source + ") failed: " + t.getMessage());
                return null;
            }
        }

        private void sendSilence() {
            byte[] frame = new byte[FRAME_BYTES];
            while (alive) {
                try { writeFrame(out, frame); }
                catch (IOException e) { return; }
                try { Thread.sleep(FRAME_MS); }
                catch (InterruptedException e) { return; }
            }
        }
    }
}
