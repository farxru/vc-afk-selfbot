package com.discord.vcbot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DiscordVoiceService extends Service implements DiscordGatewayClient.Listener {
    public static final String CHANNEL_ID = "DiscordVoiceServiceChannel";
    public static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_STOP = "ACTION_STOP";
    public static final String ACTION_MUTE_TOGGLE = "ACTION_MUTE_TOGGLE";
    public static final String ACTION_DEAF_TOGGLE = "ACTION_DEAF_TOGGLE";

    private static volatile DiscordVoiceService instance = null;

    public static DiscordVoiceService getInstance() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null && instance.isBotRunning();
    }

    private final IBinder binder = new LocalBinder();
    private PowerManager.WakeLock wakeLock;
    private DiscordGatewayClient client;
    private NotificationManager notificationManager;

    private final List<String> logHistory = Collections.synchronizedList(new ArrayList<>());
    private DiscordGatewayClient.Listener uiListener = null;

    private boolean isMuted = false;
    private boolean isDeafened = true;
    private boolean isInVc = false;
    private boolean isCameraOn = false;
    private boolean isStreaming = false;
    private boolean isConnected = false;
    private String currentStatus = "Stopped";
    private String currentChannel = "None";

    public class LocalBinder extends Binder {
        public DiscordVoiceService getService() {
            return DiscordVoiceService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DiscordVCBot::ServiceWakeLock");
            wakeLock.acquire(24 * 60 * 60 * 1000L); // Keep CPU awake up to 24h
        }

        client = new DiscordGatewayClient(this);
    }

    public void setUiListener(DiscordGatewayClient.Listener listener) {
        this.uiListener = listener;
        if (listener != null) {
            listener.onStatusChanged(currentStatus, isConnected);
            listener.onVoiceStateChanged(currentChannel, isInVc, isMuted, isDeafened, isCameraOn, isStreaming);
        }
    }

    public List<String> getLogHistory() {
        synchronized (logHistory) {
            return new ArrayList<>(logHistory);
        }
    }

    public void clearLogHistory() {
        synchronized (logHistory) {
            logHistory.clear();
        }
    }

    public String getCurrentStatus() {
        return currentStatus;
    }

    public boolean isConnected() {
        return isConnected;
    }

    public String getCurrentChannel() {
        return currentChannel;
    }

    public boolean isMuted() {
        return isMuted;
    }

    public boolean isDeafened() {
        return isDeafened;
    }

    public boolean isCameraOn() {
        return isCameraOn;
    }

    public boolean isStreaming() {
        return isStreaming;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopSelf();
                return START_NOT_STICKY;
            } else if (ACTION_MUTE_TOGGLE.equals(action)) {
                if (client != null) client.setMute(!isMuted);
            } else if (ACTION_DEAF_TOGGLE.equals(action)) {
                if (client != null) client.setDeafen(!isDeafened);
            } else if (ACTION_START.equals(action)) {
                String token = intent.getStringExtra("token");
                String ownerId = intent.getStringExtra("ownerId");
                String prefix = intent.getStringExtra("prefix");
                String channelId = intent.getStringExtra("channelId");
                boolean autoDeaf = intent.getBooleanExtra("autoDeaf", true);
                boolean autoMute = intent.getBooleanExtra("autoMute", false);
                if (client != null) {
                    client.start(token, ownerId, prefix, channelId, autoDeaf, autoMute);
                }
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification("Discord VC Bot 24/7", "Starting service..."));
        return START_STICKY;
    }

    public DiscordGatewayClient getClient() {
        return client;
    }

    public boolean isBotRunning() {
        return client != null && client.isRunning();
    }

    public boolean isInVc() {
        return isInVc;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Discord 24/7 VC Background Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps Discord VC Bot alive 24/7 in the background");
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String title, String content) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Intent stopIntent = new Intent(this, DiscordVoiceService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_delete, "Stop Bot", stopPendingIntent);

        return builder.build();
    }

    private void updateNotification(String title, String content) {
        if (notificationManager != null) {
            Notification notification = buildNotification(title, content);
            notificationManager.notify(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public void onLog(String message) {
        synchronized (logHistory) {
            logHistory.add(message);
            if (logHistory.size() > 300) {
                logHistory.remove(0);
            }
        }
        if (uiListener != null) {
            uiListener.onLog(message);
        }
    }

    @Override
    public void onStatusChanged(String status, boolean connected) {
        this.currentStatus = status;
        this.isConnected = connected;
        String content = isInVc ? ("In VC: " + currentChannel) : (connected ? "Connected to Gateway" : "24/7 service active");
        updateNotification("Discord VC Bot: " + status, content);
        if (uiListener != null) {
            uiListener.onStatusChanged(status, connected);
        }
    }

    @Override
    public void onVoiceStateChanged(String channelId, boolean inVc, boolean muted, boolean deafened, boolean camera, boolean streaming) {
        this.isInVc = inVc;
        this.isMuted = muted;
        this.isDeafened = deafened;
        this.isCameraOn = camera;
        this.isStreaming = streaming;
        this.currentChannel = (channelId != null && !channelId.equals("null")) ? channelId : "None";

        String state = inVc ? ("In VC: " + currentChannel + (muted ? " [Muted]" : "") + (deafened ? " [Deaf]" : "")) : "Connected (Waiting for VC)";
        updateNotification("Discord VC Bot 24/7", state);

        if (uiListener != null) {
            uiListener.onVoiceStateChanged(channelId, inVc, muted, deafened, camera, streaming);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // App was swiped away from recent apps.
        // Foreground service continues running 24/7 in background.
        uiListener = null;
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (client != null) {
            client.stop();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (uiListener != null) {
            uiListener.onStatusChanged("Stopped", false);
        }
        if (instance == this) {
            instance = null;
        }
        uiListener = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
