package com.f50.sip;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;

/** Receives SMS_DELIVER (only delivered to the *default* SMS app). We:
 *  1. Persist the message to content://sms/inbox so it doesn't appear lost
 *     (Android won't insert it automatically when we are the default app).
 *  2. Forward as a SIP MESSAGE via SipForegroundService.
 *
 *  The default-SMS-app role is required for SMS_DELIVER. We never actually
 *  show SMS UI — it's a forwarding hop.
 */
public class SmsDeliverReceiver extends BroadcastReceiver {
    private static final String TAG = "F50SIP";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        if (!Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(intent.getAction())) return;

        SmsMessage[] msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (msgs == null || msgs.length == 0) return;

        // Concatenate multipart bodies by sender
        StringBuilder body = new StringBuilder();
        String from = msgs[0].getOriginatingAddress();
        long ts = msgs[0].getTimestampMillis();
        for (SmsMessage m : msgs) {
            String b = m.getMessageBody();
            if (b != null) body.append(b);
        }
        String text = body.toString();
        Log.i(TAG, "SMS from " + from + " (" + text.length() + " chars)");

        // Persist to inbox (we are the default SMS app — Android won't do it)
        try {
            ContentValues v = new ContentValues();
            v.put("address", from);
            v.put("body", text);
            v.put("date", System.currentTimeMillis());
            v.put("date_sent", ts);
            v.put("read", 0);
            v.put("seen", 0);
            v.put("type", 1); // 1 = inbox
            Uri u = ctx.getContentResolver().insert(
                    Uri.parse("content://sms/inbox"), v);
            Log.d(TAG, "persisted inbox row: " + u);
        } catch (Exception e) {
            Log.w(TAG, "inbox insert failed: " + e.getMessage());
        }

        // Forward to SIP
        SipForegroundService.forwardSms(ctx, from, text);
    }
}
