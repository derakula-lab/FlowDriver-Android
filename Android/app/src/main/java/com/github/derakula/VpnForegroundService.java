package com.github.derakula;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import androidlib.FlowClient;
import androidlib.Androidlib;

public class VpnForegroundService extends Service {

    private static final String TAG        = "Flow Driver";
    private static final String CHANNEL_ID = "xvpn_channel";
    private static final int    NOTIF_ID   = 1001;

    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_STOP  = "ACTION_STOP";

    public class LocalBinder extends Binder {
        public VpnForegroundService getService() { return VpnForegroundService.this; }
    }
    private final IBinder binder = new LocalBinder();

    private FlowClient       flowClient;
    private volatile boolean running  = false;
    private volatile boolean stopping = false; // اگه موقع connect دستور stop اومد

    private String currentListenAddr;
    private String currentProfileName;

    public interface StatusListener {
        void onStarted(String listenAddr);
        void onStopped();
        void onError(String msg);
    }
    private StatusListener listener;
    public void setListener(StatusListener l) {
        this.listener = l;
        if (running && l != null) {
            l.onStarted(currentListenAddr);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        if (ACTION_START.equals(intent.getAction())) {
            if (running) return START_NOT_STICKY;

            String credJSON    = intent.getStringExtra("credJSON");
            String tokenJSON   = intent.getStringExtra("tokenJSON");
            String cfgJSON     = intent.getStringExtra("cfgJSON");
            currentListenAddr  = intent.getStringExtra("listenAddr");
            currentProfileName = intent.getStringExtra("profileName");

            stopping = false; // ریست کن قبل از start جدید

            startForeground(NOTIF_ID,
                    buildNotification("Connecting...", currentProfileName),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);

            startClientAsync(credJSON, tokenJSON, cfgJSON,
                    currentListenAddr, currentProfileName);

        } else if (ACTION_STOP.equals(intent.getAction())) {
            stopClientAndSelf();
        }

        return START_NOT_STICKY;
    }

    private void startClientAsync(String credJSON, String tokenJSON, String cfgJSON,
                                  String listenAddr, String profileName) {
        new Thread(() -> {
            try {
                // این خط ممکنه چند ثانیه طول بکشه
                FlowClient client = Androidlib.newClient(credJSON, tokenJSON, cfgJSON,
                        getFilesDir().getAbsolutePath());

                // اگه همین مدت دستور stop اومده بود، client جدید رو فوری stop کن
                if (stopping) {
                    Log.i(TAG, "Stop requested during connect, shutting down immediately");
                    try { client.stop(); } catch (Exception ignored) {}
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                    return;
                }

                flowClient = client;
                running = true;

                updateNotification("Connected  |  " + listenAddr, profileName);

                if (listener != null)
                    listener.onStarted(listenAddr);

                Log.i(TAG, "Client started on " + listenAddr);

            } catch (Exception e) {
                Log.e(TAG, "Start failed: " + e.getMessage());
                running = false;
                flowClient = null;
                if (listener != null) listener.onError(e.getMessage());
                stopSelf();
            }
        }).start();
    }

    public void stopClientAndSelf() {
        running  = false;  // فوری، synchronous
        stopping = true;   // به startClientAsync بگو داری stop میکنی
        new Thread(() -> {
            try {
                if (flowClient != null) {
                    flowClient.stop();
                    flowClient = null;
                }
            } catch (Exception ignored) {}
            stopping = false;
            if (listener != null) listener.onStopped();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }).start();
    }

    public boolean isRunning() { return running; }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Flow Driver Service", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Proxy status");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification(String status, String profileName) {
        Intent stopIntent = new Intent(this, VpnForegroundService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_background)
                .setContentTitle("Flow Driver  |  " + (profileName != null ? profileName : ""))
                .setContentText(status)
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
                .build();
    }

    private void updateNotification(String status, String profileName) {
        getSystemService(NotificationManager.class)
                .notify(NOTIF_ID, buildNotification(status, profileName));
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running  = false;
        stopping = true;
        if (flowClient != null) {
            try { flowClient.stop(); } catch (Exception ignored) {}
            flowClient = null;
        }
    }
}