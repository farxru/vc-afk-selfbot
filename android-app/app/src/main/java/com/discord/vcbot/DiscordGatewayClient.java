package com.discord.vcbot;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
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
    private final Handler streamKeepAliveHandler = new Handler(Looper.getMainLooper());
    private Runnable streamKeepAliveRunnable;
    private final Handler watchKeepAliveHandler = new Handler(Looper.getMainLooper());
    private Runnable watchKeepAliveRunnable;
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
        this.targetChannelId = null;
        this.currentGuildId = null;
        stopHeartbeat();
        stopVoiceWatchdog();
        stopStreamKeepAlive();
        stopWatchKeepAlive();
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
                stopWatchingStream();
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
        account.setWatchingStream(false);
        account.setWatchingStreamKey(null);
        account.setWatchingStreamerName(null);
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
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .addHeader("Cache-Control", "no-cache")
                .addHeader("Pragma", "no-cache")
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
                    watchdogHandler.postDelayed(this, 15000);
                }
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

                // Auto-join configured target voice channel ONLY if stayInVc is explicitly enabled
                if (stayInVc && targetChannelId != null && !targetChannelId.isEmpty()) {
                    joinVoiceChannel(null, targetChannelId);
                }
            } catch (Exception e) {
                log("[!] Error parsing READY event: " + e.getMessage());
            }
        } else if ("VOICE_STATE_UPDATE".equals(event)) {
            handleVoiceStateUpdate(d);
        } else if ("STREAM_CREATE".equals(event)) {
            handleStreamCreate(d);
        } else if ("STREAM_DELETE".equals(event)) {
            handleStreamDelete(d);
        }
    }

    private void handleStreamCreate(JSONObject d) {
        if (d == null) return;
        String sKey = d.optString("stream_key", null);
        if (sKey != null && !sKey.isEmpty()) {
            // Keep own stream unpaused
            if (account.isStreaming() && botUserId != null && sKey.contains(botUserId)) {
                try {
                    JSONObject op22 = new JSONObject();
                    op22.put("op", 22);
                    JSONObject d22 = new JSONObject();
                    d22.put("stream_key", sKey);
                    d22.put("paused", false);
                    op22.put("d", d22);
                    sendJson(op22);
                } catch (Exception ignored) {}
            }
        }
    }

    private void handleStreamDelete(JSONObject d) {
        if (d == null) return;
        String sKey = d.optString("stream_key", null);
        if (sKey != null && sKey.equals(account.getWatchingStreamKey())) {
            stopWatchingStream();
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

                    if (stayInVc) {
                        log("[+] In VC #" + chId + (account.isMuted() ? " [Muted]" : "") + (account.isDeafened() ? " [Deaf]" : ""));
                        notifyUpdate();
                    }
                } else if (stayInVc && targetChannelId != null && !targetChannelId.isEmpty()) {
                    // Disconnected or kicked from VC while stayInVc is explicitly enabled
                    final String channelToRejoin = targetChannelId;
                    log("[!] Disconnected from VC. Auto-rejoining in 1s...");
                    account.setInVc(false);
                    account.setChannelId(null);
                    notifyUpdate();
                    if (rejoinRunnable != null) {
                        mainHandler.removeCallbacks(rejoinRunnable);
                    }
                    rejoinRunnable = () -> {
                        rejoinRunnable = null;
                        if (stayInVc && targetChannelId != null) {
                            joinVoiceChannel(currentGuildId, channelToRejoin);
                        }
                    };
                    mainHandler.postDelayed(rejoinRunnable, 1000);
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
        this.targetChannelId = null;
        stopVoiceWatchdog();
        if (rejoinRunnable != null) {
            mainHandler.removeCallbacks(rejoinRunnable);
            rejoinRunnable = null;
        }
        stopScreenShare();
        stopWatchingStream();
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

    public void updatePresence(boolean streaming) {
        try {
            JSONObject d = new JSONObject();
            d.put("since", 0);
            d.put("status", "online");
            d.put("afk", false);
            org.json.JSONArray activities = new org.json.JSONArray();
            if (streaming) {
                JSONObject act = new JSONObject();
                act.put("name", "Screen Share");
                act.put("type", 0);
                act.put("details", "Streaming Desktop (720p 60fps)");
                act.put("state", "Live in Voice Channel");
                activities.put(act);
            }
            d.put("activities", activities);
            JSONObject packet = new JSONObject();
            packet.put("op", 3);
            packet.put("d", d);
            sendJson(packet);
        } catch (Exception ignored) {}
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

            // Opcode 4: Update voice state with self_stream: true
            sendVoiceStateUpdate(currentGuildId, chId, account.isMuted(), account.isDeafened(), account.isCameraOn(), true);

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

            // Opcode 21: STREAM_PING (streamer keep-alive)
            JSONObject op21 = new JSONObject();
            op21.put("op", 21);
            JSONObject d21 = new JSONObject();
            d21.put("stream_key", streamKey);
            op21.put("d", d21);
            sendJson(op21);

            // Humanized Rich Presence activity
            updatePresence(true);

            // Upload initial non-blank visual preview
            uploadStreamPreview(streamKey);

            // Start 12s keep-alive loop
            startStreamKeepAlive(streamKey);

            log("[+] Screenshare started");
            notifyUpdate();
        } catch (Exception e) {
            log("[-] Error starting stream: " + e.getMessage());
        }
    }

    public void stopScreenShare() {
        stopStreamKeepAlive();
        if (!account.isStreaming()) return;
        try {
            account.setStreaming(false);
            String chId = account.getChannelId();
            String streamKey = currentGuildId != null
                    ? "guild:" + currentGuildId + ":" + chId + ":" + botUserId
                    : "call:" + chId + ":" + botUserId;

            // Opcode 22: STREAM_SET_PAUSED (paused: true)
            JSONObject op22 = new JSONObject();
            op22.put("op", 22);
            JSONObject d22 = new JSONObject();
            d22.put("stream_key", streamKey);
            d22.put("paused", true);
            op22.put("d", d22);
            sendJson(op22);

            // Opcode 19: STREAM_DELETE
            JSONObject op19 = new JSONObject();
            op19.put("op", 19);
            JSONObject d19 = new JSONObject();
            d19.put("stream_key", streamKey);
            op19.put("d", d19);
            sendJson(op19);

            // Reset self_stream to false on Opcode 4
            sendVoiceStateUpdate(currentGuildId, chId, account.isMuted(), account.isDeafened(), account.isCameraOn(), false);

            updatePresence(false);

            log("[-] Screenshare stopped");
            notifyUpdate();
        } catch (Exception ignored) {}
    }

    private void startStreamKeepAlive(String streamKey) {
        stopStreamKeepAlive();
        streamKeepAliveRunnable = new Runnable() {
            @Override
            public void run() {
                if (account.isStreaming() && isRunning && streamKey != null) {
                    try {
                        JSONObject op22 = new JSONObject();
                        op22.put("op", 22);
                        JSONObject d22 = new JSONObject();
                        d22.put("stream_key", streamKey);
                        d22.put("paused", false);
                        op22.put("d", d22);
                        sendJson(op22);

                        JSONObject op21 = new JSONObject();
                        op21.put("op", 21);
                        JSONObject d21 = new JSONObject();
                        d21.put("stream_key", streamKey);
                        op21.put("d", d21);
                        sendJson(op21);
                    } catch (Exception ignored) {}

                    uploadStreamPreview(streamKey);
                    streamKeepAliveHandler.postDelayed(this, 12000);
                }
            }
        };
        streamKeepAliveHandler.postDelayed(streamKeepAliveRunnable, 12000);
    }

    private void stopStreamKeepAlive() {
        if (streamKeepAliveRunnable != null) {
            streamKeepAliveHandler.removeCallbacks(streamKeepAliveRunnable);
            streamKeepAliveRunnable = null;
        }
    }

    public void uploadStreamPreview(String streamKey) {
        if (streamKey == null || streamKey.isEmpty()) return;
        new Thread(() -> {
            try {
                String base64Thumbnail = createStreamPreviewBase64(account.getNickname(), account.getChannelId());
                if (base64Thumbnail == null) return;

                JSONObject json = new JSONObject();
                json.put("thumbnail", base64Thumbnail);

                String encodedStreamKey = URLEncoder.encode(streamKey, "UTF-8");
                RequestBody body = RequestBody.create(
                        json.toString(),
                        MediaType.parse("application/json; charset=utf-8")
                );

                Request req = new Request.Builder()
                        .url("https://discord.com/api/v9/streams/" + encodedStreamKey + "/preview")
                        .addHeader("Authorization", account.getToken())
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) discord/1.0.9210 Chrome/134.0.6998.205 Electron/35.3.0 Safari/537.36")
                        .post(body)
                        .build();

                try (Response resp = httpClient.newCall(req).execute()) {
                    // Preview updated
                }
            } catch (Exception ignored) {}
        }).start();
    }

    public String createStreamPreviewBase64(String title, String channelName) {
        try {
            int width = 640;
            int height = 360;
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(bitmap);

            // Discord dark theme gradient background (#1E1F22 to #2B2D31)
            Paint bgPaint = new Paint();
            LinearGradient gradient = new LinearGradient(
                    0, 0, width, height,
                    Color.rgb(0x1E, 0x1F, 0x22),
                    Color.rgb(0x2B, 0x2D, 0x31),
                    Shader.TileMode.CLAMP
            );
            bgPaint.setShader(gradient);
            canvas.drawRect(0, 0, width, height, bgPaint);

            // Draw central card (#313338)
            Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            cardPaint.setColor(Color.rgb(0x31, 0x33, 0x38));
            cardPaint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(40, 40, width - 40, height - 40, 16, 16, cardPaint);

            // Draw red "● LIVE" badge
            Paint liveBadgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            liveBadgePaint.setColor(Color.rgb(0xED, 0x42, 0x45));
            liveBadgePaint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(60, 60, 180, 100, 10, 10, liveBadgePaint);

            Paint liveTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            liveTextPaint.setColor(Color.WHITE);
            liveTextPaint.setTextSize(22);
            liveTextPaint.setFakeBoldText(true);
            canvas.drawText("● LIVE", 82, 88, liveTextPaint);

            // Streamer Title Text
            Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            titlePaint.setColor(Color.WHITE);
            titlePaint.setTextSize(32);
            titlePaint.setFakeBoldText(true);
            String displayTitle = (title != null && !title.isEmpty()) ? title : "Live Screen Share";
            if (displayTitle.length() > 24) displayTitle = displayTitle.substring(0, 24) + "...";
            canvas.drawText(displayTitle, 60, 160, titlePaint);

            // Channel / Subtitle Text
            Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            subPaint.setColor(Color.rgb(0x94, 0x9B, 0xA4));
            subPaint.setTextSize(22);
            String subText = channelName != null ? "Channel: #" + channelName : "Discord Voice Stream";
            canvas.drawText(subText, 60, 205, subPaint);

            // Discord 720p 60fps pill badge
            Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            badgePaint.setColor(Color.rgb(0x58, 0x65, 0xF2));
            badgePaint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(60, 250, 210, 290, 8, 8, badgePaint);

            Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            badgeTextPaint.setColor(Color.WHITE);
            badgeTextPaint.setTextSize(18);
            badgeTextPaint.setFakeBoldText(true);
            canvas.drawText("720p 60FPS", 80, 278, badgeTextPaint);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos);
            byte[] jpegBytes = baos.toByteArray();
            bitmap.recycle();

            return "data:image/jpeg;base64," + Base64.encodeToString(jpegBytes, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Error generating stream preview", e);
            return null;
        }
    }

    public String getStreamKey() {
        String chId = account.getChannelId();
        if (chId == null || botUserId == null) return null;
        return currentGuildId != null
                ? "guild:" + currentGuildId + ":" + chId + ":" + botUserId
                : "call:" + chId + ":" + botUserId;
    }

    public void watchStream(String streamKey, String streamerName) {
        if (streamKey == null || streamKey.trim().isEmpty()) return;
        try {
            account.setWatchingStream(true);
            account.setWatchingStreamKey(streamKey.trim());
            account.setWatchingStreamerName(streamerName != null ? streamerName : "Stream");

            // Opcode 20: STREAM_WATCH
            JSONObject op20 = new JSONObject();
            op20.put("op", 20);
            JSONObject d20 = new JSONObject();
            d20.put("stream_key", streamKey.trim());
            op20.put("d", d20);
            sendJson(op20);

            // Opcode 21: Immediate STREAM_PING to register watcher active state
            JSONObject op21 = new JSONObject();
            op21.put("op", 21);
            JSONObject d21 = new JSONObject();
            d21.put("stream_key", streamKey.trim());
            op21.put("d", d21);
            sendJson(op21);

            startWatchKeepAlive(streamKey.trim());

            log("[+] Watching stream: " + (streamerName != null ? streamerName : streamKey));
            notifyUpdate();
        } catch (Exception e) {
            log("[-] Error watching stream: " + e.getMessage());
        }
    }

    public void stopWatchingStream() {
        stopWatchKeepAlive();
        if (!account.isWatchingStream() && account.getWatchingStreamKey() == null) return;
        try {
            // Opcode 20: STREAM_WATCH with stream_key: null
            JSONObject op20 = new JSONObject();
            op20.put("op", 20);
            JSONObject d20 = new JSONObject();
            d20.put("stream_key", JSONObject.NULL);
            op20.put("d", d20);
            sendJson(op20);

            account.setWatchingStream(false);
            account.setWatchingStreamKey(null);
            account.setWatchingStreamerName(null);
            log("[-] Stopped watching stream");
            notifyUpdate();
        } catch (Exception ignored) {}
    }

    private void startWatchKeepAlive(String streamKey) {
        stopWatchKeepAlive();
        watchKeepAliveRunnable = new Runnable() {
            @Override
            public void run() {
                if (account.isWatchingStream() && isRunning && streamKey != null) {
                    try {
                        JSONObject op21 = new JSONObject();
                        op21.put("op", 21);
                        JSONObject d21 = new JSONObject();
                        d21.put("stream_key", streamKey);
                        op21.put("d", d21);
                        sendJson(op21);
                    } catch (Exception ignored) {}

                    watchKeepAliveHandler.postDelayed(this, 12000);
                }
            }
        };
        watchKeepAliveHandler.postDelayed(watchKeepAliveRunnable, 12000);
    }

    private void stopWatchKeepAlive() {
        if (watchKeepAliveRunnable != null) {
            watchKeepAliveHandler.removeCallbacks(watchKeepAliveRunnable);
            watchKeepAliveRunnable = null;
        }
    }

    private void sendVoiceStateUpdate(String guildId, String channelId, boolean mute, boolean deaf, boolean video, boolean stream) {
        try {
            JSONObject d = new JSONObject();
            d.put("guild_id", (guildId != null && !guildId.equals("null")) ? guildId : JSONObject.NULL);
            d.put("channel_id", (channelId != null && !channelId.equals("null")) ? channelId : JSONObject.NULL);
            d.put("self_mute", mute);
            d.put("self_deaf", deaf);
            d.put("self_video", video);
            d.put("self_stream", stream);
            d.put("flags", 2);

            JSONObject packet = new JSONObject();
            packet.put("op", 4);
            packet.put("d", d);
            sendJson(packet);
        } catch (Exception e) {
            Log.e(TAG, "Error sending VoiceStateUpdate", e);
        }
    }

    private void sendVoiceStateUpdate(String guildId, String channelId, boolean mute, boolean deaf, boolean video) {
        sendVoiceStateUpdate(guildId, channelId, mute, deaf, video, account.isStreaming());
    }

    private void sendIdentify() {
        try {
            JSONObject props = new JSONObject();
            props.put("os", "Windows");
            props.put("browser", "Discord Client");
            props.put("release_channel", "stable");
            props.put("client_version", "1.0.9210");
            props.put("os_version", "10.0.19045");
            props.put("os_arch", "x64");
            props.put("app_arch", "x64");
            props.put("system_locale", "en-US");
            props.put("browser_user_agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) discord/1.0.9210 Chrome/134.0.6998.205 Electron/35.3.0 Safari/537.36");
            props.put("browser_version", "35.3.0");
            props.put("client_build_number", 455964);
            props.put("native_build_number", JSONObject.NULL);
            props.put("client_event_source", JSONObject.NULL);

            JSONObject clientState = new JSONObject();
            clientState.put("guild_versions", new JSONObject());
            clientState.put("highest_last_message_id", "0");
            clientState.put("read_state_version", 0);
            clientState.put("user_guild_settings_version", -1);
            clientState.put("user_settings_version", -1);

            JSONObject presence = new JSONObject();
            presence.put("status", "online");
            presence.put("since", 0);
            presence.put("activities", new org.json.JSONArray());
            presence.put("afk", false);

            JSONObject d = new JSONObject();
            d.put("token", account.getToken());
            d.put("capabilities", 16381);
            d.put("properties", props);
            d.put("presence", presence);
            d.put("compress", false);
            d.put("client_state", clientState);

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
