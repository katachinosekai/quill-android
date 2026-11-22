package io.github.katachinosekai.quillremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

public class NotifyService extends Service {
    static final String CHANNEL = "background";
    static final String ACTION_STOP = "io.github.katachinosekai.quillremote.STOP_NOTIFY";
    static final int ID = 7001;
    Notifier notifier;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, NotifyService.class));
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, NotifyService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            getSharedPreferences("quill", MODE_PRIVATE).edit().putBoolean("notifyBackground", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Background watching", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Shown while Quill Android watches for approvals in the background");
            nm.createNotificationChannel(ch);
        }
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, NotifyService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Quill is watching for approvals")
                .setContentText("On your local network. Tap Stop to turn off.")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ID, n);
        }
        if (notifier == null) {
            Net.init(this);
            notifier = new Notifier(this);
        }
        notifier.start(25);
        updateTick.removeCallbacks(updateCheck);
        updateTick.postDelayed(updateCheck, 60_000);
        return START_STICKY;
    }

    /** While Quill Android watches in the background, it also looks for app updates (hourly) and posts one notification per new version. */
    final android.os.Handler updateTick = new android.os.Handler(android.os.Looper.getMainLooper());
    Updater updater;
    final Runnable updateCheck = new Runnable() {
        @Override
        public void run() {
            android.content.SharedPreferences prefs = getSharedPreferences("quill", MODE_PRIVATE);
            if (updater == null) {
                updater = new Updater(NotifyService.this, prefs, new Updater.Listener() {
                    @Override
                    public void onUpdate(Updater.Release r) {
                        if (Notifier.appVisible || r.version.equals(prefs.getString("update.notified", ""))) {
                            return;
                        }
                        prefs.edit().putString("update.notified", r.version).apply();
                        Notifier.channel(NotifyService.this);
                        PendingIntent open = PendingIntent.getActivity(NotifyService.this, 3, new Intent(NotifyService.this, MainActivity.class),
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                        Notification n = new Notification.Builder(NotifyService.this, Notifier.CHANNEL)
                                .setSmallIcon(R.drawable.ic_notify)
                                .setContentTitle("Quill update")
                                .setContentText("Quill Android " + r.version + " is available. Tap to update.")
                                .setContentIntent(open)
                                .setAutoCancel(true)
                                .build();
                        getSystemService(NotificationManager.class).notify(ID + 1, n);
                    }

                    @Override
                    public void onMessage(String msg) {
                    }
                });
            }
            updater.checkIfDue();
            updateTick.postDelayed(this, Updater.CHECK_EVERY);
        }
    };

    @Override
    public void onDestroy() {
        updateTick.removeCallbacks(updateCheck);
        if (notifier != null) {
            notifier.stop();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
