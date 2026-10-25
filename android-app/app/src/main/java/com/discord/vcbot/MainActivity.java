package com.discord.vcbot;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.List;

public class MainActivity extends AppCompatActivity implements DiscordGatewayClient.Listener {

    private EditText editToken, editOwnerId, editChannelId, editPrefix;
    private CheckBox checkAutoDeaf, checkAutoMute;
    private TextView textStatus, textLogs;
    private ScrollView scrollLogs;
    private Button btnStartStop, btnJoinVc, btnLeaveVc;
    private Button btnMute, btnDeaf, btnCamera, btnStream;

    private DiscordVoiceService service;
    private boolean isBound = false;
    private boolean isBotRunning = false;
    private boolean isInVc = false;
    private boolean isMuted = false;
    private boolean isDeafened = true;
    private boolean isCamOn = false;
    private boolean isStreaming = false;

    private SharedPreferences prefs;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            DiscordVoiceService.LocalBinder b = (DiscordVoiceService.LocalBinder) binder;
            service = b.getService();
            isBound = true;
            syncWithService(service);
            appendLog("[+] Connected to Background Service.");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (service != null) {
                service.setUiListener(null);
            }
            service = null;
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("discord_vc_bot_prefs", MODE_PRIVATE);

        initViews();
        loadSavedConfig();
        requestPermissionsIfNeeded();

        // Check if service is already running in background and connect immediately
        checkAndBindService();
    }

    @Override
    protected void onStart() {
        super.onStart();
        checkAndBindService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (service != null && isBound) {
            syncWithService(service);
        } else {
            checkAndBindService();
        }
    }

    @Override
    protected void onPause() {
        saveConfig();
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (isBound) {
            if (service != null) {
                service.setUiListener(null);
            }
            try {
                unbindService(serviceConnection);
            } catch (Exception ignored) {}
            isBound = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (isBound) {
            if (service != null) {
                service.setUiListener(null);
            }
            try {
                unbindService(serviceConnection);
            } catch (Exception ignored) {}
            isBound = false;
        }
        super.onDestroy();
    }

    private void checkAndBindService() {
        DiscordVoiceService running = DiscordVoiceService.getInstance();
        if (running != null) {
            syncWithService(running);
            Intent serviceIntent = new Intent(this, DiscordVoiceService.class);
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        } else if (prefs.getBoolean("bot_started", false)) {
            Intent serviceIntent = new Intent(this, DiscordVoiceService.class);
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        }
    }

    private void syncWithService(DiscordVoiceService svc) {
        if (svc == null) return;
        this.service = svc;
        this.isBound = true;
        svc.setUiListener(this);
        restoreLogsFromService(svc);

        boolean running = svc.isBotRunning();
        this.isBotRunning = running;
        this.isInVc = svc.isInVc();
        this.isMuted = svc.isMuted();
        this.isDeafened = svc.isDeafened();
        this.isCamOn = svc.isCameraOn();
        this.isStreaming = svc.isStreaming();

        runOnUiThread(() -> {
            if (running) {
                btnStartStop.setText("STOP 24/7 BOT");
                btnStartStop.setBackgroundColor(0xFFED4245); // Discord Red
            } else {
                btnStartStop.setText("START 24/7 BOT (BACKGROUND)");
                btnStartStop.setBackgroundColor(0xFF5865F2); // Discord Blurple
            }

            btnMute.setText(isMuted ? "UNMUTE" : "MUTE");
            btnDeaf.setText(isDeafened ? "UNDEAFEN" : "DEAFEN");
            btnCamera.setText(isCamOn ? "CAM OFF" : "CAM ON");
            btnStream.setText(isStreaming ? "STOP STREAM" : "SCREENSHARE");

            if (svc.isInVc()) {
                textStatus.setText("🟢 In VC: " + svc.getCurrentChannel());
                textStatus.setTextColor(0xFF3BA55D);
            } else if (svc.isConnected()) {
                textStatus.setText("Status: " + svc.getCurrentStatus());
                textStatus.setTextColor(0xFF3BA55D);
            } else if (running) {
                textStatus.setText("⚡ Status: " + svc.getCurrentStatus());
                textStatus.setTextColor(0xFFFAA61A);
            } else {
                textStatus.setText("🔴 Status: Stopped");
                textStatus.setTextColor(0xFFED4245);
            }
        });
    }

    private void restoreLogsFromService(DiscordVoiceService svc) {
        if (svc == null) return;
        List<String> logs = svc.getLogHistory();
        if (logs.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        for (String line : logs) {
            sb.append(line).append("\n");
        }
        final String allLogs = sb.toString();
        runOnUiThread(() -> {
            textLogs.setText(allLogs);
            scrollLogs.post(() -> scrollLogs.fullScroll(View.FOCUS_DOWN));
        });
    }

    private DiscordVoiceService getActiveService() {
        if (service != null) return service;
        return DiscordVoiceService.getInstance();
    }

    private void initViews() {
        editToken = findViewById(R.id.editToken);
        editOwnerId = findViewById(R.id.editOwnerId);
        editChannelId = findViewById(R.id.editChannelId);
        editPrefix = findViewById(R.id.editPrefix);
        checkAutoDeaf = findViewById(R.id.checkAutoDeaf);
        checkAutoMute = findViewById(R.id.checkAutoMute);
        textStatus = findViewById(R.id.textStatus);
        textLogs = findViewById(R.id.textLogs);
        scrollLogs = findViewById(R.id.scrollLogs);

        btnStartStop = findViewById(R.id.btnStartStop);
        btnJoinVc = findViewById(R.id.btnJoinVc);
        btnLeaveVc = findViewById(R.id.btnLeaveVc);
        btnMute = findViewById(R.id.btnMute);
        btnDeaf = findViewById(R.id.btnDeaf);
        btnCamera = findViewById(R.id.btnCamera);
        btnStream = findViewById(R.id.btnStream);

        btnStartStop.setOnClickListener(v -> toggleBotService());
        btnJoinVc.setOnClickListener(v -> joinVcClicked());
        btnLeaveVc.setOnClickListener(v -> leaveVcClicked());
        btnMute.setOnClickListener(v -> toggleMute());
        btnDeaf.setOnClickListener(v -> toggleDeaf());
        btnCamera.setOnClickListener(v -> toggleCamera());
        btnStream.setOnClickListener(v -> toggleStream());

        findViewById(R.id.btnClearLogs).setOnClickListener(v -> {
            textLogs.setText("");
            DiscordVoiceService svc = getActiveService();
            if (svc != null) {
                svc.clearLogHistory();
            }
        });
    }

    private void loadSavedConfig() {
        // Fresh defaults for everyone: no pre-set token or personal ID hardcoded
        editToken.setText(prefs.getString("token", ""));
        editOwnerId.setText(prefs.getString("ownerId", ""));
        editPrefix.setText(prefs.getString("prefix", "."));
        editChannelId.setText(prefs.getString("channelId", ""));
        checkAutoDeaf.setChecked(prefs.getBoolean("autoDeaf", true));
        checkAutoMute.setChecked(prefs.getBoolean("autoMute", false));
    }

    private void saveConfig() {
        prefs.edit()
                .putString("token", editToken.getText().toString().trim())
                .putString("ownerId", editOwnerId.getText().toString().trim())
                .putString("prefix", editPrefix.getText().toString().trim())
                .putString("channelId", editChannelId.getText().toString().trim())
                .putBoolean("autoDeaf", checkAutoDeaf.isChecked())
                .putBoolean("autoMute", checkAutoMute.isChecked())
                .apply();
    }

    private void toggleBotService() {
        saveConfig();
        if (!isBotRunning) {
            String token = editToken.getText().toString().trim();
            String ownerId = editOwnerId.getText().toString().trim();
            String prefix = editPrefix.getText().toString().trim();
            String channelId = editChannelId.getText().toString().trim();

            if (token.isEmpty() || ownerId.isEmpty()) {
                Toast.makeText(this, "Please enter your Token and Owner ID!", Toast.LENGTH_SHORT).show();
                return;
            }

            prefs.edit().putBoolean("bot_started", true).apply();

            Intent serviceIntent = new Intent(this, DiscordVoiceService.class);
            serviceIntent.setAction(DiscordVoiceService.ACTION_START);
            serviceIntent.putExtra("token", token);
            serviceIntent.putExtra("ownerId", ownerId);
            serviceIntent.putExtra("prefix", prefix);
            serviceIntent.putExtra("channelId", channelId);
            serviceIntent.putExtra("autoDeaf", checkAutoDeaf.isChecked());
            serviceIntent.putExtra("autoMute", checkAutoMute.isChecked());

            ContextCompat.startForegroundService(this, serviceIntent);
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

            isBotRunning = true;
            btnStartStop.setText("STOP 24/7 BOT");
            btnStartStop.setBackgroundColor(0xFFED4245); // Discord Red
            textStatus.setText("⚡ Connecting to Discord Gateway...");
            textStatus.setTextColor(0xFFFAA61A);
            appendLog("[+] Starting 24/7 Background Service...");
        } else {
            prefs.edit().putBoolean("bot_started", false).apply();
            if (isBound) {
                if (service != null) service.setUiListener(null);
                try {
                    unbindService(serviceConnection);
                } catch (Exception ignored) {}
                isBound = false;
            }
            Intent stopIntent = new Intent(this, DiscordVoiceService.class);
            stopService(stopIntent);

            isBotRunning = false;
            isInVc = false;
            btnStartStop.setText("START 24/7 BOT (BACKGROUND)");
            btnStartStop.setBackgroundColor(0xFF5865F2); // Discord Blurple
            textStatus.setText("🔴 Status: Stopped");
            textStatus.setTextColor(0xFFED4245);
            appendLog("[-] 24/7 Service stopped.");
        }
    }

    private void joinVcClicked() {
        String chId = editChannelId.getText().toString().trim();
        if (chId.isEmpty()) {
            Toast.makeText(this, "Enter Voice Channel ID!", Toast.LENGTH_SHORT).show();
            return;
        }
        saveConfig();
        DiscordVoiceService svc = getActiveService();
        if (svc != null && svc.getClient() != null) {
            appendLog("[+] Joining Voice Channel: " + chId + "...");
            svc.getClient().joinVoiceChannel(null, chId);
        } else {
            Toast.makeText(this, "Tap START 24/7 BOT first!", Toast.LENGTH_SHORT).show();
        }
    }

    private void leaveVcClicked() {
        DiscordVoiceService svc = getActiveService();
        if (svc != null && svc.getClient() != null) {
            appendLog("[-] Leaving Voice Channel...");
            svc.getClient().leaveVoiceChannel();
        }
    }

    private void toggleMute() {
        DiscordVoiceService svc = getActiveService();
        if (svc != null && svc.getClient() != null) {
            svc.getClient().setMute(!isMuted);
        }
    }

    private void toggleDeaf() {
        DiscordVoiceService svc = getActiveService();
        if (svc != null && svc.getClient() != null) {
            svc.getClient().setDeafen(!isDeafened);
        }
    }

    private void toggleCamera() {
        DiscordVoiceService svc = getActiveService();
        if (svc != null && svc.getClient() != null) {
            svc.getClient().setCamera(!isCamOn);
        }
    }

    private void toggleStream() {
        DiscordVoiceService svc = getActiveService();
        if (svc != null && svc.getClient() != null) {
            if (isStreaming) {
                svc.getClient().stopScreenShare();
            } else {
                svc.getClient().startScreenShare();
            }
        }
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(
                        this,
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        101
                );
            }
        }
        requestBatteryOptimization();
    }

    private void requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                try {
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        }
    }

    private void appendLog(String msg) {
        runOnUiThread(() -> {
            textLogs.append(msg + "\n");
            scrollLogs.post(() -> scrollLogs.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    public void onLog(String message) {
        appendLog(message);
    }

    @Override
    public void onStatusChanged(String status, boolean connected) {
        runOnUiThread(() -> {
            textStatus.setText("Status: " + status);
            textStatus.setTextColor(connected ? 0xFF3BA55D : 0xFFFAA61A);
            if (connected) {
                isBotRunning = true;
                btnStartStop.setText("STOP 24/7 BOT");
                btnStartStop.setBackgroundColor(0xFFED4245);
            } else if ("Stopped".equalsIgnoreCase(status)) {
                isBotRunning = false;
                btnStartStop.setText("START 24/7 BOT (BACKGROUND)");
                btnStartStop.setBackgroundColor(0xFF5865F2);
            }
        });
    }

    @Override
    public void onVoiceStateChanged(String channelId, boolean inVc, boolean muted, boolean deafened, boolean camera, boolean streaming) {
        this.isInVc = inVc;
        this.isMuted = muted;
        this.isDeafened = deafened;
        this.isCamOn = camera;
        this.isStreaming = streaming;

        runOnUiThread(() -> {
            btnMute.setText(muted ? "UNMUTE" : "MUTE");
            btnDeaf.setText(deafened ? "UNDEAFEN" : "DEAFEN");
            btnCamera.setText(camera ? "CAM OFF" : "CAM ON");
            btnStream.setText(streaming ? "STOP STREAM" : "SCREENSHARE");
            if (inVc) {
                textStatus.setText("🟢 In VC: " + channelId);
                textStatus.setTextColor(0xFF3BA55D);
            }
        });
    }
}
