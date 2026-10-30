package com.discord.vcbot;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class DiscordGatewayClient {
    private static final String TAG = "DiscordGateway";
    private static final String GATEWAY_URL = "wss://gateway.discord.gg/?v=9&encoding=json";

    public interface Listener {
        void onLog(String accountId, String message);
        void onAccountUpdated(AccountModel account);
    }

    private final AccountModel account;
    private final OkHttpClient httpClient;
    private WebSocket webSocket;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Listener listener;

    private String targetChannelId = null;
    private String currentGuildId = null;
    private String botUserId = null;

    private boolean stayInVc = false;
    private long heartbeatInterval = 41250;
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private Runnable heartbeatRunnable;
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private Runnable watchdogRunnable;
    private Runnable reconnectRunnable;
    private Runnable rejoinRunnable;
    private boolean isRunning = false;
    private int sequenceNumber = -1;

    public DiscordGatewayClient(String accountId, String token, Listener listener) {
        this.account = new AccountModel(accountId, token);
        this.listener = listener;
        this.httpClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public AccountModel getAccount() {
        return account;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public void start(String targetChannelId, boolean autoDeaf, boolean autoMute) {
        if (this.isRunning && this.webSocket != null) {
            log("[!] Already connected to Discord Gateway.");
            return;
        }
        this.targetChannelId = (targetChannelId != null && !targetChannelId.trim().isEmpty()) ? targetChannelId.trim() : null;
        this.account.setDeafened(autoDeaf);
        this.account.setMuted(autoMute);
        this.account.setCameraOn(false);
        this.account.setStreaming(false);
        this.account.setInVc(false);
        this.account.setChannelId(null);
        this.account.setStatus("Connecting");
        this.account.setErrorMessage(null);
        this.sequenceNumber = -1;
        this.stayInVc = (this.targetChannelId != null);
        this.isRunning = true;

        notifyUpdate();
        log("[+] Connecting to Discord Gateway...");
        connect();
    }

    public void stop() {
        this.isRunning = false;
        this.stayInVc = false;
        stopHeartbeat();
        stopVoiceWatchdog();
        if (reconnectRunnable != null) {
            mainHandler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
        if (rejoinRunnable != null) {
            mainHandler.removeCallbacks(rejoinRunnable);
            rejoinRunnable = null;
        }
        if (webSocket != null) {
            try {
                if (currentGuildId != null && !currentGuildId.isEmpty()) {
                    sendVoiceStateUpdate(currentGuildId, null, false, false, false);
                }
                sendVoiceStateUpdate(null, null, false, false, false);
                stopScreenShare();
            } catch (Exception ignored) {}
            try {
                webSocket.close(1000, "Account stopped by user");
            } catch (Exception ignored) {}
            webSocket = null;
        }
        account.setInVc(false);
        account.setChannelId(null);
        account.setStatus("Stopped");
        account.setStreaming(false);
        account.setCameraOn(false);
        targetChannelId = null;
        currentGuildId = null;
        notifyUpdate();
        log("[-] Account disconnected & stopped.");
    }

    private void connect() {
        if (!isRunning) return;
        if (reconnectRunnable != null) {
            mainHandler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
        if (webSocket != null) {
            try {
                webSocket.close(1000, "Resetting socket");
            } catch (Exception ignored) {}
            webSocket = null;
        }
        account.setStatus("Connecting");
        notifyUpdate();

        Request request = new Request.Builder()
                .url(GATEWAY_URL)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) discord/1.0.9210 Chrome/134.0.6998.205 Electron/35.3.0 Safari/537.36")
                .build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                log("[+] WebSocket connected. Awaiting HELLO...");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                handleMessage(text);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                log("[-] Gateway closed (" + code + ")");
                if (isRunning) {
                    account.setStatus("Connecting");
                    notifyUpdate();
                    scheduleReconnect(2000);
                } else {
                    account.setStatus("Stopped");
                    notifyUpdate();
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                String err = (t != null ? t.getMessage() : "Connection failed");
                log("[!] Gateway error: " + err);
                if (isRunning) {
                    account.setStatus("Error");
                    account.setErrorMessage(err);
                    notifyUpdate();
                    scheduleReconnect(3000);
                } else {
                    account.setStatus("Stopped");
                    notifyUpdate();
                }
            }
        });
    }

    private void scheduleReconnect(long delayMs) {
        stopHeartbeat();
        if (!isRunning) return;
        if (reconnectRunnable != null) {
            mainHandler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
        reconnectRunnable = () -> {
            reconnectRunnable = null;
            if (isRunning) {
                connect();
            }
        };
        mainHandler.postDelayed(reconnectRunnable, delayMs);
    }

    private void startVoiceWatchdog() {
        stopVoiceWatchdog();
        watchdogRunnable = new Runnable() {
            @Override
            public void run() {
                if (isRunning && stayInVc && targetChannelId != null && !targetChannelId.isEmpty()) {
                    String cur = account.getChannelId();
                    if (cur == null || !cur.equals(targetChannelId)) {
                        log("[Watchdog] Missing from target VC #" + targetChannelId + ". Auto-rejoining...");
                        joinVoiceChannel(currentGuildId, targetChannelId);
                    }
                }
                watchdogHandler.postDelayed(this, 15000);
            }
        };
        watchdogHandler.postDelayed(watchdogRunnable, 15000);
    }

    private void stopVoiceWatchdog() {
        if (watchdogRunnable != null) {
            watchdogHandler.removeCallbacks(watchdogRunnable);
            watchdogRunnable = null;
        }
    }

    private void handleMessage(String jsonStr) {
        try {
            JSONObject json = new JSONObject(jsonStr);
            int op = json.optInt("op", -1);
            if (json.has("s") && !json.isNull("s")) {
                sequenceNumber = json.getInt("s");
            }

            switch (op) {
                case 10: // HELLO
                    JSONObject d = json.getJSONObject("d");
                    heartbeatInterval = d.getLong("heartbeat_interval");
                    startHeartbeat(heartbeatInterval);
                    sendIdentify();
                    break;

                case 11: // HEARTBEAT_ACK
                    break;

                case 0: // DISPATCH
                    handleDispatch(json.optString("t"), json.optJSONObject("d"));
                    break;

                case 1:
                    sendHeartbeat();
                    break;

                case 7:
                case 9:
                    log("[!] Reconnect requested by Discord (OP " + op + ")");
                    if (webSocket != null) webSocket.close(4000, "Reconnect requested");
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing packet", e);
        }
    }

    private void handleDispatch(String event, JSONObject d) {
        if (event == null || d == null) return;

        if ("READY".equals(event)) {
            try {
                JSONObject user = d.getJSONObject("user");
                botUserId = user.getString("id");
                account.setUserId(botUserId);
                String un = user.optString("username", "User");
                String disc = user.optString("discriminator", "0");
                String fullTag = disc.equals("0") ? un : (un + "#" + disc);
                account.setUsername(fullTag);

                String globalName = user.optString("global_name", "");
                if (globalName != null && !globalName.trim().isEmpty()) {
                    account.setNickname(globalName.trim());
                } else {
                    account.setNickname(un);
                }

                account.setStatus("Online");
                account.setErrorMessage(null);
                log("[+] Ready: " + account.getNickname() + " (" + fullTag + ")");
                notifyUpdate();

                // Auto-join configured target voice channel
                if (targetChannelId != null && !targetChannelId.isEmpty()) {
                    joinVoiceChannel(null, targetChannelId);
                } else if (stayInVc && account.getChannelId() != null) {
                    sendVoiceStateUpdate(currentGuildId, account.getChannelId(), account.isMuted(), account.isDeafened(), account.isCameraOn());
                }
            } catch (Exception e) {
                log("[!] Error parsing READY event: " + e.getMessage());
            }
        } else if ("VOICE_STATE_UPDATE".equals(event)) {
            handleVoiceStateUpdate(d);
        }
    }

    private void handleVoiceStateUpdate(JSONObject d) {
        try {
            String userId = d.optString("user_id");
            if (botUserId != null && botUserId.equals(userId)) {
                String chId = d.optString("channel_id", null);
                if (chId != null && !chId.equals("null") && !chId.isEmpty()) {
                    account.setChannelId(chId);
                    account.setInVc(true);
                    String gId = d.optString("guild_id", null);
                    if (gId != null && !gId.isEmpty() && !gId.equals("null")) {
                        currentGuildId = gId;
                    }
                    account.setMuted(d.optBoolean("self_mute", false));
                    account.setDeafened(d.optBoolean("self_deaf", false));
                    account.setCameraOn(d.optBoolean("self_video", false));

                    // Re-enforce target VC if shifted to another channel
                    if (stayInVc && targetChannelId != null && !targetChannelId.isEmpty() && !chId.equals(targetChannelId)) {
                        log("[!] Voice shifted to " + chId + ". Re-asserting target VC: " + targetChannelId);
                        if (rejoinRunnable != null) {
                            mainHandler.removeCallbacks(rejoinRunnable);
                        }
                        rejoinRunnable = () -> {
                            rejoinRunnable = null;
                            if (stayInVc && targetChannelId != null) {
                                joinVoiceChannel(currentGuildId, targetChannelId);
                            }
                        };
                        mainHandler.postDelayed(rejoinRunnable, 800);
                        return;
                    }

                    stayInVc = true;
                    log("[+] In VC #" + chId + (account.isMuted() ? " [Muted]" : "") + (account.isDeafened() ? " [Deaf]" : ""));
                    notifyUpdate();
                } else if (stayInVc) {
                    // Disconnected or kicked from VC
                    String channelToRejoin = (targetChannelId != null && !targetChannelId.isEmpty()) ? targetChannelId : account.getChannelId();
                    if (channelToRejoin != null && !channelToRejoin.isEmpty()) {
                        log("[!] Disconnected from VC. Auto-rejoining in 1s...");
                        account.setInVc(false);
                        account.setChannelId(null);
                        notifyUpdate();
                        if (rejoinRunnable != null) {
                            mainHandler.removeCallbacks(rejoinRunnable);
                        }
                        rejoinRunnable = () -> {
                            rejoinRunnable = null;
                            if (stayInVc) {
                                joinVoiceChannel(currentGuildId, channelToRejoin);
                            }
                        };
                        mainHandler.postDelayed(rejoinRunnable, 1000);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public void joinVoiceChannel(String guildId, String channelId) {
        if (channelId == null || channelId.trim().isEmpty()) return;
        this.targetChannelId = channelId.trim();
        this.stayInVc = true;

        String gId = (guildId != null && !guildId.trim().isEmpty()) ? guildId.trim() : this.currentGuildId;
        if (gId != null && !gId.isEmpty()) {
            this.currentGuildId = gId;
            account.setChannelId(channelId.trim());
            account.setInVc(true);
            log("[+] Joining VC: #" + channelId + " (Guild: " + gId + ")");
            sendVoiceStateUpdate(currentGuildId, channelId.trim(), account.isMuted(), account.isDeafened(), account.isCameraOn());
            notifyUpdate();
            startVoiceWatchdog();
            return;
        }

        // Resolve guild ID via REST API
        new Thread(() -> {
            String resolvedGuildId = null;
            try {
                Request req = new Request.Builder()
                        .url("https://discord.com/api/v9/channels/" + channelId)
                        .addHeader("Authorization", account.getToken())
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build();

                try (Response resp = httpClient.newCall(req).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        String body = resp.body().string();
                        JSONObject json = new JSONObject(body);
                        resolvedGuildId = json.optString("guild_id", null);
                    }
                }
            } catch (Exception ignored) {}

            final String finalGuildId = resolvedGuildId;
            mainHandler.post(() -> {
                this.currentGuildId = finalGuildId;
                account.setChannelId(channelId);
                account.setInVc(true);
                sendVoiceStateUpdate(finalGuildId, channelId, account.isMuted(), account.isDeafened(), account.isCameraOn());
                notifyUpdate();
                startVoiceWatchdog();
            });
        }).start();
    }

    public void leaveVoiceChannel() {
        this.stayInVc = false;
        stopVoiceWatchdog();
        if (rejoinRunnable != null) {
            mainHandler.removeCallbacks(rejoinRunnable);
            rejoinRunnable = null;
        }
        stopScreenShare();
        account.setCameraOn(false);
        account.setInVc(false);
        account.setChannelId(null);
        log("[-] Left VC");
        if (currentGuildId != null && !currentGuildId.isEmpty()) {
            sendVoiceStateUpdate(currentGuildId, null, false, false, false);
        }
        sendVoiceStateUpdate(null, null, false, false, false);
        notifyUpdate();
    }

    public void setMute(boolean mute) {
        account.setMuted(mute);
        sendVoiceStateUpdate(currentGuildId, account.getChannelId(), mute, account.isDeafened(), account.isCameraOn());
        log(mute ? "[+] Muted" : "[+] Unmuted");
        notifyUpdate();
    }

    public void setDeafen(boolean deaf) {
        account.setDeafened(deaf);
        sendVoiceStateUpdate(currentGuildId, account.getChannelId(), account.isMuted(), deaf, account.isCameraOn());
        log(deaf ? "[+] Deafened" : "[+] Undeafened");
        notifyUpdate();
    }

    public void setCamera(boolean camera) {
        account.setCameraOn(camera);
        sendVoiceStateUpdate(currentGuildId, account.getChannelId(), account.isMuted(), account.isDeafened(), camera);
        log(camera ? "[+] Camera ON" : "[-] Camera OFF");
        notifyUpdate();
    }

    public void startScreenShare() {
        String chId = account.getChannelId();
        if (chId == null) {
            log("[-] Cannot start screenshare: Not connected to VC.");
            return;
        }
        try {
            account.setStreaming(true);
            String streamKey = currentGuildId != null
                    ? "guild:" + currentGuildId + ":" + chId + ":" + botUserId
                    : "call:" + chId + ":" + botUserId;

            // Opcode 18: STREAM_CREATE
            JSONObject op18 = new JSONObject();
            op18.put("op", 18);
            JSONObject d18 = new JSONObject();
            d18.put("type", currentGuildId != null ? "guild" : "call");
            d18.put("guild_id", currentGuildId != null ? currentGuildId : JSONObject.NULL);
            d18.put("channel_id", chId);
            d18.put("preferred_region", JSONObject.NULL);
            op18.put("d", d18);
            sendJson(op18);

            // Opcode 22: STREAM_SET_PAUSED (paused: false)
            JSONObject op22 = new JSONObject();
            op22.put("op", 22);
            JSONObject d22 = new JSONObject();
            d22.put("stream_key", streamKey);
            d22.put("paused", false);
            op22.put("d", d22);
            sendJson(op22);

            log("[+] Screenshare started");
            notifyUpdate();
        } catch (Exception e) {
            log("[-] Error starting stream: " + e.getMessage());
        }
    }

    public void stopScreenShare() {
        if (!account.isStreaming()) return;
        try {
            account.setStreaming(false);
            String chId = account.getChannelId();
            String streamKey = currentGuildId != null
                    ? "guild:" + currentGuildId + ":" + chId + ":" + botUserId
                    : "call:" + chId + ":" + botUserId;

            // Opcode 19: STREAM_DELETE
            JSONObject op19 = new JSONObject();
            op19.put("op", 19);
            JSONObject d19 = new JSONObject();
            d19.put("stream_key", streamKey);
            op19.put("d", d19);
            sendJson(op19);

            log("[-] Screenshare stopped");
            notifyUpdate();
        } catch (Exception ignored) {}
    }

    private void sendVoiceStateUpdate(String guildId, String channelId, boolean mute, boolean deaf, boolean video) {
        try {
            JSONObject d = new JSONObject();
            d.put("guild_id", (guildId != null && !guildId.equals("null")) ? guildId : JSONObject.NULL);
            d.put("channel_id", (channelId != null && !channelId.equals("null")) ? channelId : JSONObject.NULL);
            d.put("self_mute", mute);
            d.put("self_deaf", deaf);
            d.put("self_video", video);
            d.put("flags", 2);

            JSONObject packet = new JSONObject();
            packet.put("op", 4);
            packet.put("d", d);
            sendJson(packet);
        } catch (Exception e) {
            Log.e(TAG, "Error sending VoiceStateUpdate", e);
        }
    }

    private void sendIdentify() {
        try {
            JSONObject props = new JSONObject();
            props.put("os", "Windows");
            props.put("browser", "Discord Client");
            props.put("device", "Discord Client");

            JSONObject d = new JSONObject();
            d.put("token", account.getToken());
            d.put("capabilities", 8189);
            d.put("properties", props);

            JSONObject packet = new JSONObject();
            packet.put("op", 2);
            packet.put("d", d);
            sendJson(packet);
        } catch (Exception e) {
            Log.e(TAG, "Error sending IDENTIFY", e);
        }
    }

    private void startHeartbeat(long intervalMs) {
        stopHeartbeat();
        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                sendHeartbeat();
                heartbeatHandler.postDelayed(this, heartbeatInterval);
            }
        };
        heartbeatHandler.postDelayed(heartbeatRunnable, intervalMs);
    }

    private void stopHeartbeat() {
        if (heartbeatRunnable != null) {
            heartbeatHandler.removeCallbacks(heartbeatRunnable);
            heartbeatRunnable = null;
        }
    }

    private void sendHeartbeat() {
        try {
            JSONObject packet = new JSONObject();
            packet.put("op", 1);
            packet.put("d", sequenceNumber >= 0 ? sequenceNumber : JSONObject.NULL);
            sendJson(packet);
        } catch (Exception ignored) {}
    }

    private void sendJson(JSONObject json) {
        if (webSocket != null) {
            webSocket.send(json.toString());
        }
    }

    private void log(String msg) {
        if (listener != null) {
            listener.onLog(account.getId(), "[" + account.getNickname() + "] " + msg);
        }
    }

    private void notifyUpdate() {
        if (listener != null) {
            listener.onAccountUpdated(account);
        }
    }
}
