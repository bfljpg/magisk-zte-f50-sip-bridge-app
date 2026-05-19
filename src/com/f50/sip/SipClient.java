package com.f50.sip;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.security.MessageDigest;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal SIP-over-UDP client for the F50 SIP Bridge.
 *
 * Implements the small slice of RFC 3261 we actually need:
 *  - REGISTER with MD5 Digest auth challenge/response
 *  - MESSAGE (RFC 3428, SMS-over-SIP)
 *  - INVITE (basic outbound call signalling — no SDP audio negotiation;
 *    intended as a "ring my softphone" notifier, not full call setup)
 *
 * Thread model: not thread-safe. Caller serialises calls or holds a
 * monitor. SipForegroundService owns the single instance.
 */
public class SipClient {

    private static final String TAG = "F50SIP";

    public static class Config {
        public String serverHost;       // e.g. "sip.example.com"
        public int    serverPort = 5060;
        public String username;          // SIP user
        public String password;          // SIP password (digest)
        public String localIp;           // best-effort local IP for Contact / Via
        public int    localPort = 5060;  // we listen here too (CB messages)
        public String forwardTarget;     // e.g. "sip:companion@phone.example.com"
        public int    registerExpires = 1800;
    }

    private final Config cfg;
    private final DatagramSocket socket;
    private final AtomicInteger cseq = new AtomicInteger(1);
    private final Random rng = new Random();

    public interface OnIncomingMessage {
        void onMessage(String from, String body);
    }
    public interface OnIncomingInvite {
        void onInvite(String from);
    }

    public OnIncomingMessage onMessage;
    public OnIncomingInvite onInvite;

    public SipClient(Config cfg) throws SocketException {
        this.cfg = cfg;
        this.socket = new DatagramSocket(cfg.localPort);
        this.socket.setSoTimeout(0); // blocking listen by default
    }

    // ─── public API ────────────────────────────────────────────────────────

    /** Send a REGISTER, blocking up to a few seconds for the 200/401 reply. */
    public boolean register() {
        try {
            String branch = branch();
            String callId = newCallId();
            int cseqNum = cseq.getAndIncrement();
            String packet = buildRegister(branch, callId, cseqNum, null, null, null);
            sendUdp(packet);
            String resp = receiveOnce(4000);
            if (resp == null) {
                Log.w(TAG, "REGISTER: no response");
                return false;
            }
            if (resp.startsWith("SIP/2.0 200")) {
                Log.i(TAG, "REGISTER OK");
                return true;
            }
            if (resp.startsWith("SIP/2.0 401") || resp.startsWith("SIP/2.0 407")) {
                Auth a = parseAuth(resp);
                if (a == null) {
                    Log.w(TAG, "REGISTER 401: no auth header parseable");
                    return false;
                }
                String packet2 = buildRegister(branch(), callId, cseq.getAndIncrement(),
                        a.realm, a.nonce, a.qop);
                sendUdp(packet2);
                String resp2 = receiveOnce(4000);
                if (resp2 != null && resp2.startsWith("SIP/2.0 200")) {
                    Log.i(TAG, "REGISTER OK (after digest auth)");
                    return true;
                }
                Log.w(TAG, "REGISTER post-auth failed: " + firstLine(resp2));
                return false;
            }
            Log.w(TAG, "REGISTER unexpected: " + firstLine(resp));
            return false;
        } catch (Exception e) {
            Log.e(TAG, "REGISTER threw", e);
            return false;
        }
    }

    /** Send a SIP MESSAGE (RFC 3428) to cfg.forwardTarget with a body. */
    public boolean message(String body) {
        return message(cfg.forwardTarget, body);
    }
    public boolean message(String toUri, String body) {
        try {
            String packet = buildMessage(toUri, body);
            sendUdp(packet);
            String resp = receiveOnce(4000);
            return resp != null && (resp.startsWith("SIP/2.0 200") || resp.startsWith("SIP/2.0 202"));
        } catch (Exception e) {
            Log.e(TAG, "MESSAGE threw", e);
            return false;
        }
    }

    /** Send an INVITE to cfg.forwardTarget. We don't do SDP/media — this is
     *  just a "ring this URI so the user notices" notifier. The companion
     *  device's softphone gets a ring; user picks it up (it talks to ANOTHER
     *  endpoint, not us). For real audio bridging see notes in README. */
    public boolean invite(String fromCaller) {
        try {
            String packet = buildInvite(fromCaller);
            sendUdp(packet);
            return true; // fire-and-forget for this signalling layer
        } catch (Exception e) {
            Log.e(TAG, "INVITE threw", e);
            return false;
        }
    }

    /** Long-running blocking receive loop. Call from a worker thread. */
    public void listenLoop() {
        byte[] buf = new byte[8192];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                socket.receive(pkt);
                String raw = new String(pkt.getData(), 0, pkt.getLength());
                handleIncoming(raw);
            } catch (IOException e) {
                Log.w(TAG, "listen IOException: " + e.getMessage());
            }
        }
    }

    public void close() {
        try { socket.close(); } catch (Exception ignored) {}
    }

    // ─── inbound dispatch ──────────────────────────────────────────────────

    private void handleIncoming(String raw) {
        String fl = firstLine(raw);
        if (fl == null) return;
        if (fl.startsWith("MESSAGE ")) {
            // Parse From and body, hand off
            String from = header(raw, "From");
            String body = bodyOf(raw);
            // Respond 200 OK
            try { sendUdp(buildOkFor(raw)); } catch (Exception e) { /* ignore */ }
            if (onMessage != null) onMessage.onMessage(from, body);
        } else if (fl.startsWith("INVITE ")) {
            String from = header(raw, "From");
            try { sendUdp(buildOkFor(raw)); } catch (Exception e) {}
            if (onInvite != null) onInvite.onInvite(from);
        } else if (fl.startsWith("OPTIONS ")) {
            try { sendUdp(buildOkFor(raw)); } catch (Exception e) {}
        }
    }

    // ─── packet builders ───────────────────────────────────────────────────

    private String buildRegister(String branch, String callId, int seq,
                                 String realm, String nonce, String qop) {
        String uri = "sip:" + cfg.serverHost;
        String contact = "<sip:" + cfg.username + "@" + cfg.localIp + ":" + cfg.localPort + ">";
        StringBuilder s = new StringBuilder();
        s.append("REGISTER ").append(uri).append(" SIP/2.0\r\n");
        s.append("Via: SIP/2.0/UDP ").append(cfg.localIp).append(':').append(cfg.localPort)
         .append(";branch=").append(branch).append(";rport\r\n");
        s.append("Max-Forwards: 70\r\n");
        s.append("From: <sip:").append(cfg.username).append('@').append(cfg.serverHost).append(">;tag=").append(tag()).append("\r\n");
        s.append("To: <sip:").append(cfg.username).append('@').append(cfg.serverHost).append(">\r\n");
        s.append("Call-ID: ").append(callId).append("\r\n");
        s.append("CSeq: ").append(seq).append(" REGISTER\r\n");
        s.append("User-Agent: F50-SIP/1.0\r\n");
        s.append("Contact: ").append(contact).append("\r\n");
        s.append("Expires: ").append(cfg.registerExpires).append("\r\n");
        if (realm != null && nonce != null) {
            String response = digestResponse("REGISTER", uri, realm, nonce);
            s.append("Authorization: Digest username=\"").append(cfg.username).append("\"")
             .append(", realm=\"").append(realm).append("\"")
             .append(", nonce=\"").append(nonce).append("\"")
             .append(", uri=\"").append(uri).append("\"")
             .append(", response=\"").append(response).append("\"")
             .append(", algorithm=MD5\r\n");
        }
        s.append("Content-Length: 0\r\n\r\n");
        return s.toString();
    }

    private String buildMessage(String toUri, String body) {
        StringBuilder s = new StringBuilder();
        s.append("MESSAGE ").append(toUri).append(" SIP/2.0\r\n");
        s.append("Via: SIP/2.0/UDP ").append(cfg.localIp).append(':').append(cfg.localPort)
         .append(";branch=").append(branch()).append(";rport\r\n");
        s.append("Max-Forwards: 70\r\n");
        s.append("From: <sip:").append(cfg.username).append('@').append(cfg.serverHost).append(">;tag=").append(tag()).append("\r\n");
        s.append("To: <").append(toUri).append(">\r\n");
        s.append("Call-ID: ").append(newCallId()).append("\r\n");
        s.append("CSeq: ").append(cseq.getAndIncrement()).append(" MESSAGE\r\n");
        s.append("User-Agent: F50-SIP/1.0\r\n");
        s.append("Content-Type: text/plain;charset=UTF-8\r\n");
        byte[] b = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        s.append("Content-Length: ").append(b.length).append("\r\n\r\n");
        s.append(body);
        return s.toString();
    }

    private String buildInvite(String fromCaller) {
        String toUri = cfg.forwardTarget;
        // No SDP body — we're just signalling "hey someone is calling".
        // For real audio bridging the companion side handles media.
        StringBuilder s = new StringBuilder();
        s.append("INVITE ").append(toUri).append(" SIP/2.0\r\n");
        s.append("Via: SIP/2.0/UDP ").append(cfg.localIp).append(':').append(cfg.localPort)
         .append(";branch=").append(branch()).append(";rport\r\n");
        s.append("Max-Forwards: 70\r\n");
        s.append("From: <sip:").append(cfg.username).append('@').append(cfg.serverHost).append(">;tag=").append(tag()).append("\r\n");
        s.append("To: <").append(toUri).append(">\r\n");
        s.append("Call-ID: ").append(newCallId()).append("\r\n");
        s.append("CSeq: ").append(cseq.getAndIncrement()).append(" INVITE\r\n");
        s.append("Contact: <sip:").append(cfg.username).append('@').append(cfg.localIp).append(':').append(cfg.localPort).append(">\r\n");
        s.append("Subject: Incoming call from ").append(fromCaller).append("\r\n");
        s.append("User-Agent: F50-SIP/1.0\r\n");
        s.append("Content-Length: 0\r\n\r\n");
        return s.toString();
    }

    private String buildOkFor(String request) {
        StringBuilder s = new StringBuilder();
        s.append("SIP/2.0 200 OK\r\n");
        for (String hn : new String[]{"Via", "From", "To", "Call-ID", "CSeq"}) {
            String v = header(request, hn);
            if (v != null) s.append(hn).append(": ").append(v).append("\r\n");
        }
        s.append("User-Agent: F50-SIP/1.0\r\n");
        s.append("Content-Length: 0\r\n\r\n");
        return s.toString();
    }

    // ─── auth ──────────────────────────────────────────────────────────────

    private static class Auth {
        String realm, nonce, qop, algorithm;
    }

    private Auth parseAuth(String resp) {
        String h = header(resp, "WWW-Authenticate");
        if (h == null) h = header(resp, "Proxy-Authenticate");
        if (h == null) return null;
        Auth a = new Auth();
        a.realm = extract(h, "realm");
        a.nonce = extract(h, "nonce");
        a.qop   = extract(h, "qop");
        a.algorithm = extract(h, "algorithm");
        if (a.realm == null || a.nonce == null) return null;
        return a;
    }

    private static String extract(String header, String key) {
        int i = header.indexOf(key + "=\"");
        if (i < 0) return null;
        int j = header.indexOf('"', i + key.length() + 2);
        if (j < 0) return null;
        return header.substring(i + key.length() + 2, j);
    }

    private String digestResponse(String method, String uri, String realm, String nonce) {
        String ha1 = md5(cfg.username + ":" + realm + ":" + cfg.password);
        String ha2 = md5(method + ":" + uri);
        return md5(ha1 + ":" + nonce + ":" + ha2);
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    // ─── plumbing ──────────────────────────────────────────────────────────

    private void sendUdp(String packet) throws IOException {
        byte[] data = packet.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        DatagramPacket p = new DatagramPacket(data, data.length,
                InetAddress.getByName(cfg.serverHost), cfg.serverPort);
        socket.send(p);
    }

    private String receiveOnce(int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
        try {
            byte[] buf = new byte[8192];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            socket.receive(pkt);
            return new String(pkt.getData(), 0, pkt.getLength());
        } catch (java.net.SocketTimeoutException e) {
            return null;
        } finally {
            socket.setSoTimeout(0);
        }
    }

    private String branch() { return "z9hG4bK" + Long.toHexString(rng.nextLong()); }
    private String tag()    { return Long.toHexString(rng.nextLong()).substring(0, 8); }
    private String newCallId() { return Long.toHexString(rng.nextLong()) + "@f50"; }

    private static String firstLine(String s) {
        if (s == null) return null;
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl).trim();
    }

    private static String header(String packet, String name) {
        String needle = "\n" + name + ":";
        int i = packet.toLowerCase().indexOf(needle.toLowerCase());
        if (i < 0) return null;
        int start = i + needle.length();
        int end = packet.indexOf('\n', start);
        if (end < 0) end = packet.length();
        return packet.substring(start, end).trim();
    }

    private static String bodyOf(String packet) {
        int i = packet.indexOf("\r\n\r\n");
        if (i < 0) i = packet.indexOf("\n\n");
        if (i < 0) return "";
        return packet.substring(i + (packet.charAt(i) == '\r' ? 4 : 2));
    }
}
