package com.discord.vcbot;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements DiscordVoiceService.UiListener {

    private TextView textHeaderRam;
    private Button btnTabControl, btnTabAccounts, btnTabLogs;
    private View layoutTabControl, layoutTabAccounts, layoutTabLogs;

    // Master Status & Control Views
    private TextView textMasterStatus;
    private Button btnMasterStartStop;

    // Control Tab Views
    private LinearLayout containerSelectionPills;
    private TextView textSelectionSummary;
    private Button btnSelectAll, btnDeselectAll;
    private EditText editChannelId;
    private Button btnJoinVcSelected, btnLeaveVcSelected;
    private Button btnToggleMuteSelected, btnToggleDeafSelected, btnToggleCamSelected, btnToggleStreamSelected;
    private TextView textRamStatus;
    private Button btnCleanRam;

    // Accounts Tab Views
    private TextView textStatTotal, textStatOnline, textStatInVc;
    private EditText editNewToken;
    private Button btnToggleTokenVisibility, btnAddAccount;
    private TextView textMaxLimitWarning;
    private LinearLayout containerAccountsList;

    // Logs Tab Views
    private TextView textLogs;
    private ScrollView scrollLogs;
    private Button btnClearLogs;

    private DiscordVoiceService service;
    private boolean isBound = false;
    private SharedPreferences prefs;

    private final Set<String> selectedAccountIds = new HashSet<>();
    private final Handler ramUpdateHandler = new Handler(Looper.getMainLooper());
    private Runnable ramUpdateRunnable;
    private boolean isTokenVisible = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            DiscordVoiceService.LocalBinder b = (DiscordVoiceService.LocalBinder) binder;
            service = b.getService();
            isBound = true;
            service.setUiListener(MainActivity.this);
            updateMasterStatusUi(true);
            refreshAllUi();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (service != null) {
                service.setUiListener(null);
            }
            service = null;
            isBound = false;
            updateMasterStatusUi(false);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("discord_vc_bot_prefs", MODE_PRIVATE);

        initViews();
        setupTabs();
        setupActions();

        boolean botEnabled = prefs.getBoolean("pref_bot_enabled", true);
        updateMasterStatusUi(botEnabled);
        if (botEnabled) {
            startServiceAndBind();
        }
        startRamUiTimer();
    }

    private void initViews() {
        textHeaderRam = findViewById(R.id.textHeaderRam);
        textMasterStatus = findViewById(R.id.textMasterStatus);
        btnMasterStartStop = findViewById(R.id.btnMasterStartStop);

        btnTabControl = findViewById(R.id.btnTabControl);
        btnTabAccounts = findViewById(R.id.btnTabAccounts);
        btnTabLogs = findViewById(R.id.btnTabLogs);

        layoutTabControl = findViewById(R.id.layoutTabControl);
        layoutTabAccounts = findViewById(R.id.layoutTabAccounts);
        layoutTabLogs = findViewById(R.id.layoutTabLogs);

        containerSelectionPills = findViewById(R.id.containerSelectionPills);
        textSelectionSummary = findViewById(R.id.textSelectionSummary);
        btnSelectAll = findViewById(R.id.btnSelectAll);
        btnDeselectAll = findViewById(R.id.btnDeselectAll);

        editChannelId = findViewById(R.id.editChannelId);
        btnJoinVcSelected = findViewById(R.id.btnJoinVcSelected);
        btnLeaveVcSelected = findViewById(R.id.btnLeaveVcSelected);

        btnToggleMuteSelected = findViewById(R.id.btnToggleMuteSelected);
        btnToggleDeafSelected = findViewById(R.id.btnToggleDeafSelected);
        btnToggleCamSelected = findViewById(R.id.btnToggleCamSelected);
        btnToggleStreamSelected = findViewById(R.id.btnToggleStreamSelected);

        textRamStatus = findViewById(R.id.textRamStatus);
        btnCleanRam = findViewById(R.id.btnCleanRam);

        textStatTotal = findViewById(R.id.textStatTotal);
        textStatOnline = findViewById(R.id.textStatOnline);
        textStatInVc = findViewById(R.id.textStatInVc);

        editNewToken = findViewById(R.id.editNewToken);
        btnToggleTokenVisibility = findViewById(R.id.btnToggleTokenVisibility);
        btnAddAccount = findViewById(R.id.btnAddAccount);
        textMaxLimitWarning = findViewById(R.id.textMaxLimitWarning);
        containerAccountsList = findViewById(R.id.containerAccountsList);

        textLogs = findViewById(R.id.textLogs);
        scrollLogs = findViewById(R.id.scrollLogs);
        btnClearLogs = findViewById(R.id.btnClearLogs);

        // Load saved channel ID
        String savedChannel = prefs.getString("pref_channel_id", "");
        if (!savedChannel.isEmpty()) {
            editChannelId.setText(savedChannel);
        }
    }

    private void setupTabs() {
        btnTabControl.setOnClickListener(v -> switchTab(0));
        btnTabAccounts.setOnClickListener(v -> switchTab(1));
        btnTabLogs.setOnClickListener(v -> switchTab(2));
    }

    private void switchTab(int tabIndex) {
        layoutTabControl.setVisibility(tabIndex == 0 ? View.VISIBLE : View.GONE);
        layoutTabAccounts.setVisibility(tabIndex == 1 ? View.VISIBLE : View.GONE);
        layoutTabLogs.setVisibility(tabIndex == 2 ? View.VISIBLE : View.GONE);

        btnTabControl.setBackgroundResource(tabIndex == 0 ? R.drawable.btn_blurple : android.R.color.transparent);
        btnTabControl.setTextColor(tabIndex == 0 ? Color.WHITE : Color.parseColor("#949BA4"));

        btnTabAccounts.setBackgroundResource(tabIndex == 1 ? R.drawable.btn_blurple : android.R.color.transparent);
        btnTabAccounts.setTextColor(tabIndex == 1 ? Color.WHITE : Color.parseColor("#949BA4"));

        btnTabLogs.setBackgroundResource(tabIndex == 2 ? R.drawable.btn_blurple : android.R.color.transparent);
        btnTabLogs.setTextColor(tabIndex == 2 ? Color.WHITE : Color.parseColor("#949BA4"));
    }

    private void setupActions() {
        // Master Bot Toggle
        btnMasterStartStop.setOnClickListener(v -> toggleMasterBotState());

        // Toggle Token Visibility
        btnToggleTokenVisibility.setOnClickListener(v -> {
            isTokenVisible = !isTokenVisible;
            if (isTokenVisible) {
                editNewToken.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                btnToggleTokenVisibility.setText("🔒");
            } else {
                editNewToken.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                btnToggleTokenVisibility.setText("👁️");
            }
            editNewToken.setSelection(editNewToken.getText().length());
        });

        // Add Account
        btnAddAccount.setOnClickListener(v -> {
            String token = editNewToken.getText().toString();
            if (token == null || token.trim().isEmpty()) {
                Toast.makeText(this, "Please paste a Discord user token", Toast.LENGTH_SHORT).show();
                return;
            }
            if (service != null) {
                String chId = editChannelId.getText().toString().trim();
                DiscordVoiceService.AddAccountResult res = service.addAccount(
                        token,
                        chId.isEmpty() ? null : chId,
                        true,
                        false
                );
                switch (res) {
                    case SUCCESS:
                        editNewToken.setText("");
                        Toast.makeText(this, "Account added successfully!", Toast.LENGTH_SHORT).show();
                        break;
                    case ALREADY_EXISTS:
                        Toast.makeText(this, "This account token is already added!", Toast.LENGTH_LONG).show();
                        break;
                    case LIMIT_REACHED:
                        Toast.makeText(this, "Limit reached: Maximum 5 accounts allowed!", Toast.LENGTH_LONG).show();
                        break;
                    case EMPTY_TOKEN:
                        Toast.makeText(this, "Token is invalid or empty!", Toast.LENGTH_SHORT).show();
                        break;
                }
            } else {
                Toast.makeText(this, "Service is initializing, please retry...", Toast.LENGTH_SHORT).show();
            }
        });

        // Selection buttons
        btnSelectAll.setOnClickListener(v -> {
            if (service != null) {
                for (AccountModel a : service.getAccountsList()) {
                    selectedAccountIds.add(a.getId());
                }
                refreshSelectionViews(service.getAccountsList());
            }
        });

        btnDeselectAll.setOnClickListener(v -> {
            selectedAccountIds.clear();
            if (service != null) {
                refreshSelectionViews(service.getAccountsList());
            }
        });

        // Join VC Selected
        btnJoinVcSelected.setOnClickListener(v -> {
            String chId = editChannelId.getText().toString().trim();
            if (chId.isEmpty()) {
                Toast.makeText(this, "Please enter a Voice Channel ID", Toast.LENGTH_SHORT).show();
                return;
            }
            if (selectedAccountIds.isEmpty()) {
                Toast.makeText(this, "Please select at least one account", Toast.LENGTH_SHORT).show();
                return;
            }
            if (service != null) {
                service.joinVc(new ArrayList<>(selectedAccountIds), chId);
                Toast.makeText(this, "Joining VC on " + selectedAccountIds.size() + " account(s)...", Toast.LENGTH_SHORT).show();
            }
        });

        // Leave VC Selected
        btnLeaveVcSelected.setOnClickListener(v -> {
            if (selectedAccountIds.isEmpty()) {
                Toast.makeText(this, "Please select at least one account", Toast.LENGTH_SHORT).show();
                return;
            }
            if (service != null) {
                service.leaveVc(new ArrayList<>(selectedAccountIds));
                Toast.makeText(this, "Leaving VC for " + selectedAccountIds.size() + " account(s)", Toast.LENGTH_SHORT).show();
            }
        });

        // Quick Toggles for Selected Accounts
        btnToggleMuteSelected.setOnClickListener(v -> {
            if (service != null && !selectedAccountIds.isEmpty()) {
                service.toggleMute(new ArrayList<>(selectedAccountIds));
            }
        });

        btnToggleDeafSelected.setOnClickListener(v -> {
            if (service != null && !selectedAccountIds.isEmpty()) {
                service.toggleDeaf(new ArrayList<>(selectedAccountIds));
            }
        });

        btnToggleCamSelected.setOnClickListener(v -> {
            if (service != null && !selectedAccountIds.isEmpty()) {
                service.toggleCamera(new ArrayList<>(selectedAccountIds));
            }
        });

        btnToggleStreamSelected.setOnClickListener(v -> {
            if (service != null && !selectedAccountIds.isEmpty()) {
                service.toggleStream(new ArrayList<>(selectedAccountIds));
            }
        });

        // RAM Cleaner Button
        btnCleanRam.setOnClickListener(v -> {
            if (service != null) {
                service.cleanRam("Manual Trigger");
                Toast.makeText(this, "RAM cleaned!", Toast.LENGTH_SHORT).show();
            }
        });

        // Clear Logs
        btnClearLogs.setOnClickListener(v -> {
            if (service != null) {
                service.clearLogHistory();
            }
            textLogs.setText("");
        });
    }

    private void toggleMasterBotState() {
        boolean isRunning = DiscordVoiceService.isRunning() || isBound || (service != null);
        if (isRunning) {
            // STOP BOT COMPLETELY
            prefs.edit().putBoolean("pref_bot_enabled", false).remove("pref_channel_id").apply();
            if (service != null) {
                service.stopBotCompletely();
            }
            if (isBound) {
                try {
                    unbindService(serviceConnection);
                } catch (Exception ignored) {}
                isBound = false;
            }
            service = null;
            updateMasterStatusUi(false);
            Toast.makeText(this, "Bot stopped completely. No auto-rejoining.", Toast.LENGTH_SHORT).show();
        } else {
            // START BOT
            prefs.edit().putBoolean("pref_bot_enabled", true).apply();
            updateMasterStatusUi(true);
            startServiceAndBind();
            Toast.makeText(this, "Starting bot service...", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateMasterStatusUi(boolean active) {
        if (textMasterStatus == null || btnMasterStartStop == null) return;
        if (active) {
            textMasterStatus.setText("🟢 BOT ACTIVE");
            textMasterStatus.setTextColor(Color.parseColor("#23A55A"));
            btnMasterStartStop.setText("🛑 STOP BOT");
            btnMasterStartStop.setBackgroundResource(R.drawable.btn_red);
        } else {
            textMasterStatus.setText("🔴 BOT STOPPED");
            textMasterStatus.setTextColor(Color.parseColor("#F23F43"));
            btnMasterStartStop.setText("▶️ START BOT");
            btnMasterStartStop.setBackgroundResource(R.drawable.btn_green);
        }
    }

    private void startServiceAndBind() {
        Intent serviceIntent = new Intent(this, DiscordVoiceService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent);
        } else {
            startService(serviceIntent);
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void refreshAllUi() {
        if (service == null) return;
        List<AccountModel> accounts = service.getAccountsList();

        if (selectedAccountIds.isEmpty() && !accounts.isEmpty()) {
            for (AccountModel a : accounts) {
                selectedAccountIds.add(a.getId());
            }
        }

        refreshSelectionViews(accounts);
        refreshAccountsList(accounts);
        updateSelectedToggleButtons(accounts);

        List<String> logs = service.getLogHistory();
        StringBuilder sb = new StringBuilder();
        for (String line : logs) {
            sb.append(line).append("\n");
        }
        textLogs.setText(sb.toString());
        scrollLogs.post(() -> scrollLogs.fullScroll(View.FOCUS_DOWN));
    }

    private void updateSelectedToggleButtons(List<AccountModel> accounts) {
        boolean anyMuted = false;
        boolean anyDeaf = false;
        boolean anyCam = false;
        boolean anyStream = false;

        for (AccountModel a : accounts) {
            if (selectedAccountIds.contains(a.getId())) {
                if (a.isMuted()) anyMuted = true;
                if (a.isDeafened()) anyDeaf = true;
                if (a.isCameraOn()) anyCam = true;
                if (a.isStreaming()) anyStream = true;
            }
        }

        // Red when muted, normal (gray) when unmuted
        btnToggleMuteSelected.setBackgroundResource(anyMuted ? R.drawable.btn_red : R.drawable.btn_gray);
        btnToggleMuteSelected.setText(anyMuted ? "🎙️ MUTED" : "🎙️ UNMUTED");

        // Red when deafened, normal (gray) when undeaf
        btnToggleDeafSelected.setBackgroundResource(anyDeaf ? R.drawable.btn_red : R.drawable.btn_gray);
        btnToggleDeafSelected.setText(anyDeaf ? "🎧 DEAFENED" : "🎧 UNDEAF");

        // Red when cam on, normal (gray) when cam off
        btnToggleCamSelected.setBackgroundResource(anyCam ? R.drawable.btn_red : R.drawable.btn_gray);
        btnToggleCamSelected.setText(anyCam ? "📷 CAM ON" : "📷 CAM OFF");

        // Red when stream on, normal (gray) when stream off
        btnToggleStreamSelected.setBackgroundResource(anyStream ? R.drawable.btn_red : R.drawable.btn_gray);
        btnToggleStreamSelected.setText(anyStream ? "🖥️ STREAM ON" : "🖥️ STREAM OFF");
    }

    private void refreshSelectionViews(List<AccountModel> accounts) {
        containerSelectionPills.removeAllViews();
        List<String> selectedNicknames = new ArrayList<>();

        if (accounts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No accounts added. Open Accounts tab to add tokens.");
            empty.setTextColor(Color.parseColor("#949BA4"));
            empty.setTextSize(11);
            containerSelectionPills.addView(empty);
            textSelectionSummary.setText("Selected: None");
            updateSelectedToggleButtons(accounts);
            return;
        }

        for (AccountModel acc : accounts) {
            boolean isChecked = selectedAccountIds.contains(acc.getId());
            if (isChecked) {
                selectedNicknames.add(acc.getNickname());
            }

            CheckBox cb = new CheckBox(this);
            String label = acc.getNickname() + " (@" + acc.getUsername() + ")" + (acc.isInVc() ? " 🔊" : "");
            cb.setText(label);
            cb.setTextColor(Color.WHITE);
            cb.setTextSize(12);
            cb.setChecked(isChecked);
            cb.setOnCheckedChangeListener((buttonView, checked) -> {
                if (checked) {
                    selectedAccountIds.add(acc.getId());
                } else {
                    selectedAccountIds.remove(acc.getId());
                }
                updateSelectionSummaryText();
                if (service != null) {
                    updateSelectedToggleButtons(service.getAccountsList());
                }
            });
            containerSelectionPills.addView(cb);
        }

        updateSelectionSummary(selectedNicknames);
        updateSelectedToggleButtons(accounts);
    }

    private void updateSelectionSummaryText() {
        if (service == null) return;
        List<String> selectedNicknames = new ArrayList<>();
        List<AccountModel> accounts = service.getAccountsList();
        for (AccountModel a : accounts) {
            if (selectedAccountIds.contains(a.getId())) {
                selectedNicknames.add(a.getNickname());
            }
        }
        updateSelectionSummary(selectedNicknames);
        updateSelectedToggleButtons(accounts);
    }

    private void updateSelectionSummary(List<String> names) {
        if (names.isEmpty()) {
            textSelectionSummary.setText("Selected: None (Check accounts above)");
        } else {
            textSelectionSummary.setText("Selected (" + names.size() + "): " + TextUtils.join(", ", names));
        }
    }

    private void refreshAccountsList(List<AccountModel> accounts) {
        containerAccountsList.removeAllViews();

        if (accounts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No accounts configured yet. Add your Discord token above!");
            empty.setTextColor(Color.parseColor("#949BA4"));
            empty.setTextSize(11);
            empty.setPadding(0, 16, 0, 16);
            containerAccountsList.addView(empty);
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        int padCard = (int) (12 * density);
        int marginCard = (int) (10 * density);

        for (AccountModel acc : accounts) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.stat_card_bg);
            card.setPadding(padCard, padCard, padCard, padCard);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, marginCard);
            card.setLayoutParams(lp);

            // Row 1: Nickname + Status Badge
            LinearLayout topRow = new LinearLayout(this);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView txtNick = new TextView(this);
            txtNick.setText(acc.getNickname());
            txtNick.setTextColor(Color.WHITE);
            txtNick.setTextSize(14);
            txtNick.setTypeface(null, Typeface.BOLD);
            LinearLayout.LayoutParams lpNick = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            txtNick.setLayoutParams(lpNick);
            topRow.addView(txtNick);

            TextView txtBadge = new TextView(this);
            String st = acc.getStatus();
            txtBadge.setText(st.toUpperCase());
            txtBadge.setTextSize(10);
            txtBadge.setTypeface(null, Typeface.BOLD);
            txtBadge.setPadding(8, 2, 8, 2);
            if ("Online".equalsIgnoreCase(st)) {
                txtBadge.setTextColor(Color.parseColor("#23A55A"));
            } else if ("Error".equalsIgnoreCase(st)) {
                txtBadge.setTextColor(Color.parseColor("#F23F43"));
            } else {
                txtBadge.setTextColor(Color.parseColor("#F0B232"));
            }
            topRow.addView(txtBadge);
            card.addView(topRow);

            // Row 2: @Username and VC status
            TextView txtSub = new TextView(this);
            String vcTxt = acc.isInVc() ? ("🔊 In VC: #" + acc.getChannelId()) : "⚪ Not in voice";
            txtSub.setText("@" + acc.getUsername() + " • " + vcTxt);
            txtSub.setTextColor(acc.isInVc() ? Color.parseColor("#23A55A") : Color.parseColor("#949BA4"));
            txtSub.setTextSize(11);
            txtSub.setPadding(0, 4, 0, 8);
            card.addView(txtSub);

            // Row 3: Action buttons (Red when active, normal/gray when inactive)
            LinearLayout actionsRow = new LinearLayout(this);
            actionsRow.setOrientation(LinearLayout.HORIZONTAL);

            Button btnMute = createSmallButton(acc.isMuted() ? "🎙️ Muted" : "🎙️ Normal", v -> {
                if (service != null) service.toggleMute(Collections.singletonList(acc.getId()));
            });
            btnMute.setBackgroundResource(acc.isMuted() ? R.drawable.btn_red : R.drawable.btn_gray);

            Button btnDeaf = createSmallButton(acc.isDeafened() ? "🎧 Deaf" : "🎧 Undeaf", v -> {
                if (service != null) service.toggleDeaf(Collections.singletonList(acc.getId()));
            });
            btnDeaf.setBackgroundResource(acc.isDeafened() ? R.drawable.btn_red : R.drawable.btn_gray);

            Button btnCam = createSmallButton(acc.isCameraOn() ? "📷 Cam ON" : "📷 Cam OFF", v -> {
                if (service != null) service.toggleCamera(Collections.singletonList(acc.getId()));
            });
            btnCam.setBackgroundResource(acc.isCameraOn() ? R.drawable.btn_red : R.drawable.btn_gray);

            Button btnStream = createSmallButton(acc.isStreaming() ? "🖥️ Live" : "🖥️ Stream", v -> {
                if (service != null) service.toggleStream(Collections.singletonList(acc.getId()));
            });
            btnStream.setBackgroundResource(acc.isStreaming() ? R.drawable.btn_red : R.drawable.btn_gray);

            actionsRow.addView(btnMute);
            actionsRow.addView(btnDeaf);
            actionsRow.addView(btnCam);
            actionsRow.addView(btnStream);

            if (acc.isInVc()) {
                Button btnLeave = createSmallButton("Leave", v -> {
                    if (service != null) service.leaveVc(Collections.singletonList(acc.getId()));
                });
                btnLeave.setBackgroundResource(R.drawable.btn_red);
                actionsRow.addView(btnLeave);
            }

            Button btnRemove = createSmallButton("🗑️", v -> {
                new AlertDialog.Builder(this)
                        .setTitle("Remove Account")
                        .setMessage("Are you sure you want to remove " + acc.getNickname() + "?")
                        .setPositiveButton("Remove", (dialog, which) -> {
                            if (service != null) service.removeAccount(acc.getId());
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
            btnRemove.setBackgroundResource(R.drawable.btn_red);
            LinearLayout.LayoutParams lpRemove = (LinearLayout.LayoutParams) btnRemove.getLayoutParams();
            lpRemove.setMargins((int) (8 * density), 0, 0, 0);
            actionsRow.addView(btnRemove);

            card.addView(actionsRow);
            containerAccountsList.addView(card);
        }
    }

    private Button createSmallButton(String text, View.OnClickListener listener) {
        float density = getResources().getDisplayMetrics().density;
        int h = (int) (38 * density);
        int m = (int) (2 * density);

        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11);
        b.setTextColor(Color.WHITE);
        b.setBackgroundResource(R.drawable.btn_gray);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, h, 1f);
        lp.setMargins(m, 0, m, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void startRamUiTimer() {
        ramUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                long usedBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                long usedMb = usedBytes / (1024 * 1024);
                String ramStr = "RAM: " + usedMb + "MB / 30MB";
                textHeaderRam.setText(ramStr);
                textRamStatus.setText("RAM: " + usedMb + " MB / 30 MB (Auto-Clean Active)");

                if (usedMb >= 26) {
                    textHeaderRam.setTextColor(Color.parseColor("#F23F43"));
                } else {
                    textHeaderRam.setTextColor(Color.parseColor("#23A55A"));
                }
                ramUpdateHandler.postDelayed(this, 2000);
            }
        };
        ramUpdateHandler.postDelayed(ramUpdateRunnable, 2000);
    }

    @Override
    public void onAccountsChanged(List<AccountModel> accounts) {
        runOnUiThread(() -> {
            refreshSelectionViews(accounts);
            refreshAccountsList(accounts);
            updateSelectedToggleButtons(accounts);
        });
    }

    @Override
    public void onLog(String message) {
        runOnUiThread(() -> {
            textLogs.append(message + "\n");
            scrollLogs.post(() -> scrollLogs.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    public void onStatsChanged(int total, int online, int inVc) {
        runOnUiThread(() -> {
            textStatTotal.setText(total + "/5");
            textStatOnline.setText(String.valueOf(online));
            textStatInVc.setText(String.valueOf(inVc));
            btnTabAccounts.setText("👥 ACCOUNTS (" + total + "/5)");

            if (total >= DiscordVoiceService.MAX_ACCOUNTS) {
                btnAddAccount.setEnabled(false);
                textMaxLimitWarning.setText("Maximum limit (5) accounts reached.");
                textMaxLimitWarning.setTextColor(Color.parseColor("#F23F43"));
            } else {
                btnAddAccount.setEnabled(true);
                textMaxLimitWarning.setText("Tokens are stored locally on your device. Max limit 5 accounts.");
                textMaxLimitWarning.setTextColor(Color.parseColor("#72767D"));
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ramUpdateRunnable != null) {
            ramUpdateHandler.removeCallbacks(ramUpdateRunnable);
        }
        if (isBound) {
            if (service != null) {
                service.setUiListener(null);
            }
            unbindService(serviceConnection);
            isBound = false;
        }
    }
}
