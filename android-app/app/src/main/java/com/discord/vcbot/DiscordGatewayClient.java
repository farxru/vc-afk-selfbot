package com.discord.vcbot;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;
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
        void onLog(String message);
        void onStatusChanged(String status, boolean connected);
        void onVoiceStateChanged(String channelId, boolean inVc, boolean muted, boolean deafened, boolean camera, boolean streaming);
    }

    private final OkHttpClient httpClient;
    private WebSocket webSocket;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Listener listener;

    private String token;
    private String ownerId;
    private String prefix = ".";
    private String targetChannelId = null;
    private String currentGuildId = null;
    private String currentChannelId = null;
    private String botUserId = null;
    private String botUsername = null;

    private boolean stayInVc = false;
    private boolean isMuted = false;
    private boolean isDeafened = true;
    private boolean isCameraOn = false;
    private boolean isStreaming = false;

    private long heartbeatInterval = 41250;
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private Runnable heartbeatRunnable;
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private Runnable watchdogRunnable;
    private Runnable reconnectRunnable;
    private Runnable rejoinRunnable;
    private boolean isRunning = false;
    private int sequenceNumber = -1;

    public DiscordGatewayClient(Listener listener) {
        this.listener = listener;
        this.httpClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public boolean isInVc() {
        return stayInVc && currentChannelId != null;
    }

    public String getCurrentChannelId() {
        return currentChannelId;
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

    public void start(String token, String ownerId, String prefix, String targetChannelId, boolean autoDeaf, boolean autoMute) {
        if (this.isRunning && this.webSocket != null) {
            log("[!] Bot is already connected to Discord Gateway.");
            return;
        }
        this.token = token.trim();
        this.ownerId = ownerId.trim();
        this.prefix = (prefix == null || prefix.isEmpty()) ? "." : prefix.trim();
        this.targetChannelId = (targetChannelId != null && !targetChannelId.trim().isEmpty()) ? targetChannelId.trim() : null;
        this.isDeafened = autoDeaf;
        this.isMuted = autoMute;
        this.isCameraOn = false;
        this.isStreaming = false;
        this.currentChannelId = null;
        this.sequenceNumber = -1;
        this.stayInVc = (this.targetChannelId != null);
        this.isRunning = true;

        log("[+] Connecting to Discord Gateway (v9)...");
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
                webSocket.close(1000, "Bot stopped by user");
            } catch (Exception ignored) {}
            webSocket = null;
        }
        currentChannelId = null;
        currentGuildId = null;
        targetChannelId = null;
        sequenceNumber = -1;
        isCameraOn = false;
        isStreaming = false;
        notifyVoiceState(null, false, isMuted, isDeafened, false, false);
        notifyStatus("Stopped", false);
        log("[-] Bot completely turned off.");
    }

    private void connect() {
        if (!isRunning) return;
        if (reconnectRunnable != null) {
            mainHandler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
        if (webSocket != null) {
            try {
                webSocket.close(1000, "Opening fresh socket");
            } catch (Exception ignored) {}
            webSocket = null;
        }
        notifyStatus("Connecting...", false);

        Request request = new Request.Builder()
                .url(GATEWAY_URL)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) discord/1.0.9210 Chrome/134.0.6998.205 Electron/35.3.0 Safari/537.36")
                .build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                log("[+] WebSocket connection established. Awaiting HELLO...");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                handleMessage(text);
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                log("[!] Gateway closing: " + code + " / " + reason);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                log("[-] Gateway closed: " + code + " / " + reason);
                if (isRunning) {
                    notifyStatus("Disconnected", false);
                    scheduleReconnect(2000);
                } else {
                    notifyStatus("Stopped", false);
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                log("[!] Gateway connection error: " + (t != null ? t.getMessage() : "Unknown error"));
                if (isRunning) {
                    notifyStatus("Network Error", false);
                    scheduleReconnect(2500);
                } else {
                    notifyStatus("Stopped", false);
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
        log("[!] Reconnecting to Discord Gateway in " + (delayMs / 1000.0) + "s...");
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
                    if (currentChannelId == null || !currentChannelId.equals(targetChannelId)) {
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
                    log("[+] Received HELLO (Heartbeat interval: " + heartbeatInterval + "ms)");
                    startHeartbeat(heartbeatInterval);
                    sendIdentify();
                    break;

                case 11: // HEARTBEAT_ACK
                    // Normal heartbeat acknowledgment
                    break;

                case 0: // DISPATCH
                    handleDispatch(json.optString("t"), json.optJSONObject("d"));
                    break;

                case 1: // Discord requested immediate heartbeat
                    sendHeartbeat();
                    break;

                case 7: // RECONNECT
                case 9: // INVALID_SESSION
                    log("[!] Gateway requested reconnect (OP " + op + ")");
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
                String username = user.optString("username", "User");
                String disc = user.optString("discriminator", "0");
                botUsername = disc.equals("0") ? username : (username + "#" + disc);
                log("[+] Successfully logged in as: " + botUsername + " (" + botUserId + ")");
                notifyStatus("Ready (" + botUsername + ")", true);

                // Auto-join configured target voice channel
                if (targetChannelId != null && !targetChannelId.isEmpty()) {
                    log("[+] Auto-joining target Voice Channel: " + targetChannelId);
                    joinVoiceChannel(null, targetChannelId);
                } else if (stayInVc && currentChannelId != null) {
                    sendVoiceStateUpdate(currentGuildId, currentChannelId, isMuted, isDeafened, isCameraOn);
                }
            } catch (Exception e) {
                log("[!] Error parsing READY event: " + e.getMessage());
            }
        } else if ("MESSAGE_CREATE".equals(event)) {
            handleOwnerCommands(d);
        } else if ("VOICE_STATE_UPDATE".equals(event)) {
            handleVoiceStateUpdate(d);
        }
    }

    private void handleOwnerCommands(JSONObject d) {
        try {
            JSONObject author = d.optJSONObject("author");
            if (author == null) return;
            String authorId = author.optString("id");

            // Strict owner check: only owner ID or bot self
            boolean isOwner = authorId.equals(ownerId) || (botUserId != null && authorId.equals(botUserId));
            if (!isOwner) return;

            String content = d.optString("content", "").trim();
            if (!content.startsWith(prefix)) return;

            String[] parts = content.substring(prefix.length()).trim().split("\\s+");
            if (parts.length == 0) return;
            String cmd = parts[0].toLowerCase();

            switch (cmd) {
                case "jvc":
                    if (parts.length > 1) {
                        String chId = parts[1].replaceAll("[<#>]", "");
                        String guildId = d.optString("guild_id", null);
                        joinVoiceChannel(guildId, chId);
                    }
                    break;

                case "lvc":
                    leaveVoiceChannel();
                    break;

                case "mute":
                    setMute(true);
                    break;

                case "unmute":
                    setMute(false);
                    break;

                case "deaf":
                case "deafen":
                    setDeafen(true);
                    break;

                case "undeaf":
                case "undeafen":
                    setDeafen(false);
                    break;

                case "cam":
                    setCamera(true);
                    break;

                case "camoff":
                    setCamera(false);
                    break;

                case "ss":
                    startScreenShare();
                    break;

                case "ssoff":
                    stopScreenShare();
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling command", e);
        }
    }

    private void handleVoiceStateUpdate(JSONObject d) {
        try {
            String userId = d.optString("user_id");
            if (botUserId != null && botUserId.equals(userId)) {
                String chId = d.optString("channel_id", null);
                if (chId != null && !chId.equals("null") && !chId.isEmpty()) {
                    currentChannelId = chId;
                    String gId = d.optString("guild_id", null);
                    if (gId != null && !gId.isEmpty() && !gId.equals("null")) {
                        currentGuildId = gId;
                    }
                    isMuted = d.optBoolean("self_mute", false);
                    isDeafened = d.optBoolean("self_deaf", false);
                    isCameraOn = d.optBoolean("self_video", false);

                    // Check if account was moved or joined a different voice channel (e.g. from main Discord app)
                    if (stayInVc && targetChannelId != null && !targetChannelId.isEmpty() && !chId.equals(targetChannelId)) {
                        log("[!] Account joined different channel (" + chId + "). Re-asserting target VC: " + targetChannelId);
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
                    log("[+] Verified 24/7 Presence in VC: " + chId + (isMuted ? " [Muted]" : "") + (isDeafened ? " [Deaf]" : ""));
                    notifyVoiceState(chId, true, isMuted, isDeafened, isCameraOn, isStreaming);
                } else if (stayInVc) {
                    // Bot was kicked, disconnected by admin, or disconnected via main Discord
                    String channelToRejoin = (targetChannelId != null && !targetChannelId.isEmpty()) ? targetChannelId : currentChannelId;
                    if (channelToRejoin != null && !channelToRejoin.isEmpty()) {
                        log("[!] Kicked or disconnected from VC! Auto-rejoining #" + channelToRejoin + " in 1s...");
                        currentChannelId = null;
                        notifyVoiceState(null, false, isMuted, isDeafened, false, false);
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
            this.currentChannelId = channelId.trim();
            log("[+] Joining Voice Channel: " + channelId + " (Guild: " + gId + ")");
            sendVoiceStateUpdate(currentGuildId, currentChannelId, isMuted, isDeafened, isCameraOn);
            notifyVoiceState(channelId, true, isMuted, isDeafened, isCameraOn, isStreaming);
            startVoiceWatchdog();
            return;
        }

        // Guild ID missing: Auto-resolve via Discord REST API in <200ms
        log("[+] Resolving Guild ID for Voice Channel: " + channelId + "...");
        new Thread(() -> {
            String resolvedGuildId = null;
            String channelName = channelId;
            try {
                Request req = new Request.Builder()
                        .url("https://discord.com/api/v9/channels/" + channelId)
                        .addHeader("Authorization", token)
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build();

                try (Response resp = httpClient.newCall(req).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        String body = resp.body().string();
                        JSONObject json = new JSONObject(body);
                        resolvedGuildId = json.optString("guild_id", null);
                        channelName = json.optString("name", channelId);
                        log("[+] Found Voice Channel: #" + channelName + " in Guild: " + resolvedGuildId);
                    } else {
                        log("[!] Channel API lookup returned code: " + resp.code());
                    }
                }
            } catch (Exception e) {
                log("[!] Error resolving channel guild: " + e.getMessage());
            }

            final String finalGuildId = resolvedGuildId;
            final String finalChannelName = channelName;
            mainHandler.post(() -> {
                this.currentGuildId = finalGuildId;
                this.currentChannelId = channelId;
                log("[+] Sending Voice State Update for #" + finalChannelName);
                sendVoiceStateUpdate(finalGuildId, channelId, isMuted, isDeafened, isCameraOn);
                notifyVoiceState(channelId, true, isMuted, isDeafened, isCameraOn, isStreaming);
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
        isCameraOn = false;
        log("[-] Leaving Voice Channel: " + currentChannelId);
        if (currentGuildId != null && !currentGuildId.isEmpty()) {
            sendVoiceStateUpdate(currentGuildId, null, false, false, false);
        }
        sendVoiceStateUpdate(null, null, false, false, false);
        currentChannelId = null;
        notifyVoiceState(null, false, isMuted, isDeafened, false, false);
    }

    public void setMute(boolean mute) {
        this.isMuted = mute;
        sendVoiceStateUpdate(currentGuildId, currentChannelId, isMuted, isDeafened, isCameraOn);
        log(mute ? "[+] Muted in VC" : "[+] Unmuted in VC");
        notifyVoiceState(currentChannelId, currentChannelId != null, isMuted, isDeafened, isCameraOn, isStreaming);
    }

    public void setDeafen(boolean deaf) {
        this.isDeafened = deaf;
        sendVoiceStateUpdate(currentGuildId, currentChannelId, isMuted, isDeafened, isCameraOn);
        log(deaf ? "[+] Deafened in VC" : "[+] Undeafened in VC");
        notifyVoiceState(currentChannelId, currentChannelId != null, isMuted, isDeafened, isCameraOn, isStreaming);
    }

    public void setCamera(boolean camera) {
        this.isCameraOn = camera;
        sendVoiceStateUpdate(currentGuildId, currentChannelId, isMuted, isDeafened, isCameraOn);
        log(camera ? "[+] Camera turned ON in VC" : "[-] Camera turned OFF in VC");
        notifyVoiceState(currentChannelId, currentChannelId != null, isMuted, isDeafened, isCameraOn, isStreaming);
    }

    public void startScreenShare() {
        if (currentChannelId == null) {
            log("[-] Cannot start screenshare: Not connected to VC.");
            return;
        }
        try {
            isStreaming = true;
            String streamKey = currentGuildId != null
                    ? "guild:" + currentGuildId + ":" + currentChannelId + ":" + botUserId
                    : "call:" + currentChannelId + ":" + botUserId;

            // Opcode 18: STREAM_CREATE
            JSONObject op18 = new JSONObject();
            op18.put("op", 18);
            JSONObject d18 = new JSONObject();
            d18.put("type", currentGuildId != null ? "guild" : "call");
            d18.put("guild_id", currentGuildId);
            d18.put("channel_id", currentChannelId);
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

            log("[+] Screenshare started in VC!");
            notifyVoiceState(currentChannelId, true, isMuted, isDeafened, isCameraOn, true);
        } catch (Exception e) {
            log("[-] Error starting screenshare: " + e.getMessage());
        }
    }

    public void stopScreenShare() {
        if (!isStreaming) return;
        try {
            isStreaming = false;
            String streamKey = currentGuildId != null
                    ? "guild:" + currentGuildId + ":" + currentChannelId + ":" + botUserId
                    : "call:" + currentChannelId + ":" + botUserId;

            // Opcode 19: STREAM_DELETE
            JSONObject op19 = new JSONObject();
            op19.put("op", 19);
            JSONObject d19 = new JSONObject();
            d19.put("stream_key", streamKey);
            op19.put("d", d19);
            sendJson(op19);

            log("[-] Stopped screenshare in VC.");
            notifyVoiceState(currentChannelId, currentChannelId != null, isMuted, isDeafened, isCameraOn, false);
        } catch (Exception e) {
            log("[-] Error stopping screenshare: " + e.getMessage());
        }
    }

    private void sendVoiceStateUpdate(String guildId, String channelId, boolean mute, boolean deaf, boolean video) {
        try {
            JSONObject op4 = new JSONObject();
            op4.put("op", 4);
            JSONObject d = new JSONObject();
            d.put("guild_id", guildId != null ? guildId : JSONObject.NULL);
            d.put("channel_id", channelId != null ? channelId : JSONObject.NULL);
            d.put("self_mute", mute);
            d.put("self_deaf", deaf);
            d.put("self_video", video);
            d.put("flags", 2);
            op4.put("d", d);
            sendJson(op4);
        } catch (Exception e) {
            log("[!] Error sending VoiceStateUpdate: " + e.getMessage());
        }
    }

    private void sendIdentify() {
        try {
            JSONObject identify = new JSONObject();
            identify.put("op", 2);

            JSONObject d = new JSONObject();
            d.put("token", token);
            d.put("capabilities", 0);

            // Valid Discord Client properties for user accounts (selfbots)
            JSONObject props = new JSONObject();
            props.put("os", "Windows");
            props.put("browser", "Discord Client");
            props.put("release_channel", "stable");
            props.put("client_version", "1.0.9210");
            props.put("os_version", "10.0.19044");
            props.put("os_arch", "x64");
            props.put("app_arch", "x64");
            props.put("system_locale", "en-US");
            props.put("has_client_mods", false);
            props.put("client_launch_id", UUID.randomUUID().toString());
            props.put("browser_user_agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) discord/1.0.9210 Chrome/134.0.6998.205 Electron/35.3.0 Safari/537.36");
            props.put("browser_version", "35.3.0");
            props.put("os_sdk_version", "19044");
            props.put("client_build_number", 455964);
            props.put("native_build_number", 69976);
            props.put("client_event_source", JSONObject.NULL);
            props.put("launch_signature", UUID.randomUUID().toString());
            props.put("client_heartbeat_session_id", UUID.randomUUID().toString());
            props.put("client_app_state", "focused");

            d.put("properties", props);
            d.put("compress", false);

            JSONObject clientState = new JSONObject();
            clientState.put("guild_versions", new JSONObject());
            d.put("client_state", clientState);

            JSONObject presence = new JSONObject();
            presence.put("status", "online");
            presence.put("since", 0);
            presence.put("afk", false);
            presence.put("activities", new JSONArray());
            d.put("presence", presence);

            identify.put("d", d);
            sendJson(identify);
            log("[+] Sent Identify payload. Authenticating...");
        } catch (Exception e) {
            log("[!] Error sending Identify: " + e.getMessage());
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
        heartbeatHandler.postDelayed(heartbeatRunnable, (long) (intervalMs * 0.9));
    }

    private void stopHeartbeat() {
        if (heartbeatRunnable != null) {
            heartbeatHandler.removeCallbacks(heartbeatRunnable);
            heartbeatRunnable = null;
        }
    }

    private void sendHeartbeat() {
        try {
            JSONObject hb = new JSONObject();
            hb.put("op", 1);
            hb.put("d", sequenceNumber >= 0 ? sequenceNumber : JSONObject.NULL);
            sendJson(hb);
        } catch (Exception ignored) {}
    }

    private void sendJson(JSONObject json) {
        if (webSocket != null) {
            webSocket.send(json.toString());
        }
    }

    private void log(String msg) {
        Log.d(TAG, msg);
        if (listener != null) {
            mainHandler.post(() -> listener.onLog(msg));
        }
    }

    private void notifyStatus(String status, boolean connected) {
        if (listener != null) {
            mainHandler.post(() -> listener.onStatusChanged(status, connected));
        }
    }

    private void notifyVoiceState(String channelId, boolean inVc, boolean muted, boolean deaf, boolean cam, boolean stream) {
        if (listener != null) {
            mainHandler.post(() -> listener.onVoiceStateChanged(channelId, inVc, muted, deaf, cam, stream));
        }
    }
}
