package io.github.katachinosekai.quillremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

public class Updated extends BroadcastReceiver {
    static final String CHANNEL = "updates";

    @Override
    public void onReceive(Context c, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            return;
        }
        String version;
        try {
            version = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        c.getSharedPreferences("quill", Context.MODE_PRIVATE).edit().remove("update.failed").apply();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Tells you when Quill Android has finished updating");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Quill updated to " + version)
                .setContentText("The update finished installing. Tap to open Quill Android.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(CHANNEL.hashCode(), n);
    }
}
