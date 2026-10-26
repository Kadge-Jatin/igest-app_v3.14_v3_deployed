package com.example.tremor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Foreground service that keeps the app process alive while a BLE device is active.
 *
 * Without a foreground service, Android terminates the background process which drops
 * the BluetoothGatt connection held in BleManager. This prevents the 15-minute periodic
 * flash sync from being received when the app screen is off.
 *
 * By running as a foreground service the process is protected from being killed and
 * BLE GATT notifications continue to be delivered regardless of screen state.
 */
public class TremorForegroundService extends Service {

    private static final String CHANNEL_ID = "tremor_fg_channel";
    /** Notification ID for the persistent foreground notification — must not clash with MainActivity.NOTIF_ID (12345). */
    static final int FG_NOTIF_ID = 54321;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(FG_NOTIF_ID, buildNotification());
        // START_STICKY: if the process is killed, Android will restart the service automatically
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        Intent notifIntent = new Intent(this, MainActivity.class);
        notifIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notifIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("iGest Tremor Monitor")
                .setContentText("Monitoring active — periodic sync will run in background")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Tremor Monitor",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps iGest active in background for periodic BLE sync");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }
}