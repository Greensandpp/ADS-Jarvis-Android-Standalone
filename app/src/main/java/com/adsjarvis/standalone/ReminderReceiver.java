package com.adsjarvis.standalone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Posts a JARVIS reminder notification when its alarm fires. */
public final class ReminderReceiver extends BroadcastReceiver {
    static final String CHANNEL = "jarvis_reminders";

    static void ensureChannel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "JARVIS reminders", NotificationManager.IMPORTANCE_HIGH));
    }

    @Override public void onReceive(Context c, Intent intent) {
        long id = intent.getLongExtra("id", 0);
        String text = intent.getStringExtra("text");
        ensureChannel(c);
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("JARVIS reminder")
            .setContentText(text == null ? "" : text)
            .setStyle(new Notification.BigTextStyle().bigText(text == null ? "" : text))
            .setAutoCancel(true).setContentIntent(open).build();
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        try { if (nm != null) nm.notify((int) id, n); } catch (SecurityException ignored) { /* notifications not allowed */ }
        JarvisDb.get(c).markReminder(id, true);
    }
}
