package app.bluekeys;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class TypingService extends Service {

    private static final String CHANNEL = "bluekeys_typing";
    private static final int    NOTIF   = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel(
            CHANNEL, "Typing in progress", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Shown while BlueKeys is typing over Bluetooth");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notif = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("BlueKeys")
            .setContentText("Typing in progress…")
            .setOngoing(true)
            .build();
        startForeground(NOTIF, notif);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
