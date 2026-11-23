package io.github.katachinosekai.quillremote;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Allow / Deny buttons on an approval notification. Answers through Quill Desktop's /phone/session/{id}/answer, the
 * same call the chat's permission card makes, then removes the notification (or says why it couldn't).
 */
public class NotifyAction extends BroadcastReceiver {
    static final String ACTION = "io.github.katachinosekai.quillremote.ANSWER";
    static final String EXTRA_SESSION = "session";
    static final String EXTRA_REQUEST = "request";
    static final String EXTRA_ANSWER = "answer";
    static final String EXTRA_ID = "notification";
    static final String EXTRA_TITLE = "title";

    static PendingIntent intent(Context c, int notification, String session, String request, String answer, String title) {
        Intent i = new Intent(c, NotifyAction.class).setAction(ACTION)
                .putExtra(EXTRA_SESSION, session)
                .putExtra(EXTRA_REQUEST, request)
                .putExtra(EXTRA_ANSWER, answer)
                .putExtra(EXTRA_ID, notification)
                .putExtra(EXTRA_TITLE, title);
        int code = (notification + "|" + answer).hashCode();
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        if (!ACTION.equals(intent.getAction())) {
            return;
        }
        String session = intent.getStringExtra(EXTRA_SESSION);
        String request = intent.getStringExtra(EXTRA_REQUEST);
        String answer = intent.getStringExtra(EXTRA_ANSWER);
        int id = intent.getIntExtra(EXTRA_ID, 0);
        String title = intent.getStringExtra(EXTRA_TITLE);
        if (session == null || request == null || request.isEmpty() || !SessionBars.REMOTE_ID.matcher(session).matches()
                || !("allow".equals(answer) || "deny".equals(answer))) {
            return;
        }
        Context app = c.getApplicationContext();
        android.app.KeyguardManager km = app.getSystemService(android.app.KeyguardManager.class);
        if (km == null || km.isKeyguardLocked()) {
            // Never answer from the lock screen: someone holding the locked phone must not approve anything.
            failed(app, id, session, title, "Unlock your phone to answer. Tap to open the session.");
            return;
        }
        PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                PhoneApi api = new PhoneApi(app.getSharedPreferences("quill", Context.MODE_PRIVATE));
                JSONObject body = new JSONObject().put("requestId", request).put("action", answer);
                PhoneApi.Reply r = api.answer(session, body);
                if (r.ok()) {
                    app.getSystemService(NotificationManager.class).cancel(id);
                } else {
                    failed(app, id, session, title, message(r));
                }
            } catch (JSONException ignored) {
            } finally {
                pr.finish();
            }
        }, "quill-answer").start();
    }

    static String message(PhoneApi.Reply r) {
        switch (r.error()) {
            case "stale":
                return "Already answered on another device.";
            case "locked":
                return "Quill Desktop wants this device's password. Tap to open Quill Android and unlock.";
            case "control_off":
                return "Quill Desktop doesn't let paired devices control sessions (Settings → Sessions → Devices).";
            case "unreachable":
                return "Can't reach Quill Desktop. Tap to open the session.";
            default:
                return "Couldn't answer: " + r.error() + ". Tap to open the session.";
        }
    }

    /** Replace the approval with a plain notification saying what went wrong; tapping it opens the session. */
    static void failed(Context c, int id, String session, String title, String msg) {
        Intent open = Home.open(c, session);
        PendingIntent pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, Notifier.CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title == null || title.isEmpty() ? "Approval needed" : title)
                .setContentText(msg)
                .setStyle(new Notification.BigTextStyle().bigText(msg))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build();
        try {
            c.getSystemService(NotificationManager.class).notify(id, n);
        } catch (SecurityException ignored) {
        }
    }
}
