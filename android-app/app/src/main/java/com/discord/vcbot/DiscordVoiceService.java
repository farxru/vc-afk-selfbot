package com.discord.vcbot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DiscordVoiceService extends Service implements DiscordGatewayClient.Listener {
    public static final String CHANNEL_ID = "DiscordVoiceServiceChannel";
    public static final int NOTIFICATION_ID = 1001;
    public static final int MAX_ACCOUNTS = 5;

    public interface UiListener {
        void onAccountsChanged(List<AccountModel> accounts);
        void onLog(String message);
        void onStatsChanged(int total, int online, int inVc);
    }

    private static volatile DiscordVoiceService instance = null;

    public static DiscordVoiceService getInstance() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    private final IBinder binder = new LocalBinder();
    private PowerManager.WakeLock wakeLock;
    private NotificationManager notificationManager;

    private final Map<String, DiscordGatewayClient> clients = new LinkedHashMap<>();
    private final List<String> logHistory = Collections.synchronizedList(new ArrayList<>());
    private UiListener uiListener = null;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler ramMonitorHandler = new Handler(Looper.getMainLooper());
    private Runnable ramMonitorRunnable;

    private SharedPreferences prefs;
    private int accountCounter = 1;

    public class LocalBinder extends Binder {
        public DiscordVoiceService getService() {
            return DiscordVoiceService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("discord_vc_bot_prefs", MODE_PRIVATE);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DiscordVCBot::ServiceWakeLock");
            wakeLock.acquire(24 * 60 * 60 * 1000L); // 24h wakelock
        }

        startForeground(NOTIFICATION_ID, buildNotification("Ready", 0, 0));
        loadSavedAccounts();
        startRamMonitor();
    }

    public void setUiListener(UiListener listener) {
        this.uiListener = listener;
        if (listener != null) {
            listener.onAccountsChanged(getAccountsList());
            notifyStats();
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

    public List<AccountModel> getAccountsList() {
        List<AccountModel> list = new ArrayList<>();
        synchronized (clients) {
            for (DiscordGatewayClient c : clients.values()) {
                list.add(c.getAccount());
            }
        }
        return list;
    }

    private void loadSavedAccounts() {
        String tokensJson = prefs.getString("pref_tokens_json", null);
        List<String> tokens = new ArrayList<>();

        if (tokensJson != null && !tokensJson.trim().isEmpty()) {
            try {
                JSONArray arr = new JSONArray(tokensJson);
                for (int i = 0; i < arr.length() && i < MAX_ACCOUNTS; i++) {
                    String t = arr.getString(i).trim();
                    if (!t.isEmpty() && !tokens.contains(t)) {
                        tokens.add(t);
                    }
                }
            } catch (Exception ignored) {}
        } else {
            // Backward compatibility
            String singleToken = prefs.getString("pref_token", null);
            if (singleToken != null && !singleToken.trim().isEmpty()) {
                tokens.add(singleToken.trim());
            }
        }

        String targetChannel = prefs.getString("pref_channel_id", null);
        boolean autoDeaf = prefs.getBoolean("pref_auto_deaf", true);
        boolean autoMute = prefs.getBoolean("pref_auto_mute", false);

        for (String t : tokens) {
            String accId = "acc_" + (accountCounter++);
            DiscordGatewayClient client = new DiscordGatewayClient(accId, t, this);
            clients.put(accId, client);
            client.start(targetChannel, autoDeaf, autoMute);
        }
    }

    public void saveTokens() {
        JSONArray arr = new JSONArray();
        synchronized (clients) {
            for (DiscordGatewayClient c : clients.values()) {
                arr.put(c.getAccount().getToken());
            }
        }
        prefs.edit().putString("pref_tokens_json", arr.toString()).apply();
    }

    public boolean addAccount(String token, String targetChannel, boolean autoDeaf, boolean autoMute) {
        if (token == null || token.trim().isEmpty()) return false;
        String trimmedToken = token.trim();

        synchronized (clients) {
            if (clients.size() >= MAX_ACCOUNTS) {
                appendLog("[!] Maximum limit of " + MAX_ACCOUNTS + " accounts reached.");
                return false;
            }
            for (DiscordGatewayClient c : clients.values()) {
                if (c.getAccount().getToken().equals(trimmedToken)) {
                    appendLog("[!] Account with this token already exists.");
                    return false;
                }
            }

            String accId = "acc_" + (accountCounter++);
            DiscordGatewayClient client = new DiscordGatewayClient(accId, trimmedToken, this);
            clients.put(accId, client);
            saveTokens();
            client.start(targetChannel, autoDeaf, autoMute);
        }

        notifyAccountsChanged();
        notifyStats();
        cleanRam("Account added");
        return true;
    }

    public void removeAccount(String accountId) {
        DiscordGatewayClient client;
        synchronized (clients) {
            client = clients.remove(accountId);
            if (client != null) {
                client.stop();
                saveTokens();
            }
        }
        if (client != null) {
            appendLog("[-] Removed account " + accountId);
            notifyAccountsChanged();
            notifyStats();
            cleanRam("Account removed");
        }
    }

    public void joinVc(List<String> accountIds, String channelId) {
        if (channelId == null || channelId.trim().isEmpty()) return;
        final String chId = channelId.trim().replaceAll("[<#>]", "");
        prefs.edit().putString("pref_channel_id", chId).apply();

        List<DiscordGatewayClient> targetClients = new ArrayList<>();
        synchronized (clients) {
            for (String id : accountIds) {
                DiscordGatewayClient c = clients.get(id);
                if (c != null) targetClients.add(c);
            }
        }

        if (targetClients.isEmpty()) return;

        // Stagger joins by 250ms to prevent gateway rate-limits / glitching
        for (int i = 0; i < targetClients.size(); i++) {
            final DiscordGatewayClient c = targetClients.get(i);
            long delay = i * 250L;
            mainHandler.postDelayed(() -> c.joinVoiceChannel(null, chId), delay);
        }
        cleanRam("post joinVc");
    }

    public void leaveVc(List<String> accountIds) {
        synchronized (clients) {
            for (String id : accountIds) {
                DiscordGatewayClient c = clients.get(id);
                if (c != null) {
                    c.leaveVoiceChannel();
                }
            }
        }
        cleanRam("post leaveVc");
    }

    public void toggleMute(List<String> accountIds) {
        synchronized (clients) {
            for (String id : accountIds) {
                DiscordGatewayClient c = clients.get(id);
                if (c != null) {
                    c.setMute(!c.getAccount().isMuted());
                }
            }
        }
    }

    public void toggleDeaf(List<String> accountIds) {
        synchronized (clients) {
            for (String id : accountIds) {
                DiscordGatewayClient c = clients.get(id);
                if (c != null) {
                    c.setDeafen(!c.getAccount().isDeafened());
                }
            }
        }
    }

    public void toggleCamera(List<String> accountIds) {
        synchronized (clients) {
            for (String id : accountIds) {
                DiscordGatewayClient c = clients.get(id);
                if (c != null) {
                    c.setCamera(!c.getAccount().isCameraOn());
                }
            }
        }
    }

    public void toggleStream(List<String> accountIds) {
        synchronized (clients) {
            for (String id : accountIds) {
                DiscordGatewayClient c = clients.get(id);
                if (c != null) {
                    if (c.getAccount().isStreaming()) {
                        c.stopScreenShare();
                    } else {
                        c.startScreenShare();
                    }
                }
            }
        }
    }

    public void cleanRam(String reason) {
        long beforeBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long beforeMb = beforeBytes / (1024 * 1024);

        // Limit log entries to keep memory tight
        synchronized (logHistory) {
            while (logHistory.size() > 50) {
                logHistory.remove(0);
            }
        }

        System.gc();

        long afterBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long afterMb = afterBytes / (1024 * 1024);
        appendLog("[RAM Cleaner] (" + reason + ") Cleaned: " + beforeMb + "MB -> " + afterMb + "MB");
    }

    private void startRamMonitor() {
        ramMonitorRunnable = new Runnable() {
            @Override
            public void run() {
                long usedBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                long usedMb = usedBytes / (1024 * 1024);
                // Whenever RAM touches 28-30MB, clean it automatically
                if (usedMb >= 28) {
                    cleanRam("Auto-Clean >= 30MB");
                }
                ramMonitorHandler.postDelayed(this, 3000);
            }
        };
        ramMonitorHandler.postDelayed(ramMonitorRunnable, 3000);
    }

    public void stopBotCompletely() {
        if (ramMonitorRunnable != null) {
            ramMonitorHandler.removeCallbacks(ramMonitorRunnable);
            ramMonitorRunnable = null;
        }

        synchronized (clients) {
            for (DiscordGatewayClient c : clients.values()) {
                c.stop();
            }
            clients.clear();
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {}
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception ignored) {}

        stopSelf();
        instance = null;
    }

    @Override
    public void onLog(String accountId, String message) {
        appendLog(message);
    }

    @Override
    public void onAccountUpdated(AccountModel account) {
        mainHandler.post(() -> {
            notifyAccountsChanged();
            notifyStats();
        });
    }

    private void appendLog(String line) {
        synchronized (logHistory) {
            logHistory.add(line);
            if (logHistory.size() > 100) {
                logHistory.remove(0);
            }
        }
        if (uiListener != null) {
            mainHandler.post(() -> uiListener.onLog(line));
        }
    }

    private void notifyAccountsChanged() {
        if (uiListener != null) {
            uiListener.onAccountsChanged(getAccountsList());
        }
    }

    private void notifyStats() {
        int total = clients.size();
        int online = 0;
        int inVc = 0;
        synchronized (clients) {
            for (DiscordGatewayClient c : clients.values()) {
                if ("Online".equals(c.getAccount().getStatus())) online++;
                if (c.getAccount().isInVc()) inVc++;
            }
        }

        updateNotification(online, inVc);

        if (uiListener != null) {
            uiListener.onStatsChanged(total, online, inVc);
        }
    }

    private void updateNotification(int online, int inVc) {
        Notification notification = buildNotification(online + "/" + clients.size() + " Online", online, inVc);
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification(String statusText, int online, int inVc) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        String content = "Online: " + online + "/" + clients.size() + " | In VC: " + inVc;

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("VC AFK Bot 24/7 (" + statusText + ")")
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Discord Voice Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("24/7 Discord Voice Channel Background Service");
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopBotCompletely();
    }
}
