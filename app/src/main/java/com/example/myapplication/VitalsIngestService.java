package com.example.myapplication;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * Keeps the phone process in the foreground while the watch is streaming so OEM
 * battery managers are less likely to freeze {@link WatchDataListenerService}.
 */
public class VitalsIngestService extends Service {

    public static final String CHANNEL_ID = "vitals_ingest";
    private static final int NOTIFICATION_ID = 4201;

    public static void start(Context context) {
        ContextCompat.startForegroundService(
                context, new Intent(context, VitalsIngestService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, VitalsIngestService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Watch streaming")
                .setContentText("Receiving heart rate, EDA, and temperature from the watch")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Watch vitals ingest", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shown while Galaxy Watch samples are being stored on this phone");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }
}
