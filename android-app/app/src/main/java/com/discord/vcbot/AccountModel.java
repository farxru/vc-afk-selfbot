package com.discord.vcbot;

public class AccountModel {
    private String id;
    private String token;
    private String nickname;
    private String username;
    private String userId;
    private String status; // "Online", "Connecting", "Stopped", "Error"
    private String errorMessage;
    private boolean inVc;
    private String channelId;
    private boolean isMuted;
    private boolean isDeafened;
    private boolean isCameraOn;
    private boolean isStreaming;

    public AccountModel(String id, String token) {
        this.id = id;
        this.token = token;
        this.nickname = "Account " + id;
        this.username = "Unknown";
        this.userId = "";
        this.status = "Stopped";
        this.errorMessage = null;
        this.inVc = false;
        this.channelId = null;
        this.isMuted = false;
        this.isDeafened = true;
        this.isCameraOn = false;
        this.isStreaming = false;
    }

    public String getId() { return id; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public boolean isInVc() { return inVc; }
    public void setInVc(boolean inVc) { this.inVc = inVc; }

    public String getChannelId() { return channelId; }
    public void setChannelId(String channelId) { this.channelId = channelId; }

    public boolean isMuted() { return isMuted; }
    public void setMuted(boolean muted) { isMuted = muted; }

    public boolean isDeafened() { return isDeafened; }
    public void setDeafened(boolean deafened) { isDeafened = deafened; }

    public boolean isCameraOn() { return isCameraOn; }
    public void setCameraOn(boolean cameraOn) { isCameraOn = cameraOn; }

    public boolean isStreaming() { return isStreaming; }
    public void setStreaming(boolean streaming) { isStreaming = streaming; }
}
