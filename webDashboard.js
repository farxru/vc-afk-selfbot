const http = require('http');
const fs = require('fs');
const path = require('path');

/**
 * Lightweight, zero-dependency Mobile Web Dashboard & Controller.
 * Supports multiple accounts (max 5), account selection with nicknames,
 * live RAM monitoring & auto-clean indicators, and clean Discord UI styling.
 */
function createDashboard(botState, actions, port = 3000) {
  const logHistory = [];
  const MAX_LOGS = 100;

  // Intercept console.log and console.error to broadcast to mobile dashboard
  const origLog = console.log;
  const origErr = console.error;

  function addLog(type, args) {
    const ts = new Date().toLocaleTimeString();
    const text = args.map((a) => (typeof a === 'object' ? JSON.stringify(a) : String(a))).join(' ');
    logHistory.push({ ts, type, text });
    if (logHistory.length > MAX_LOGS) logHistory.shift();
  }

  console.log = function (...args) {
    origLog.apply(console, args);
    addLog('info', args);
  };

  console.error = function (...args) {
    origErr.apply(console, args);
    addLog('error', args);
  };

  const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
  <title>Discord VC Multi-Bot</title>
  <link rel="manifest" href="/manifest.json">
  <meta name="theme-color" content="#1e1f22">
  <meta name="mobile-web-app-capable" content="yes">
  <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
  <link rel="apple-touch-icon" href="/icon.png">
  <style>
    :root {
      --bg: #1e1f22;
      --card-bg: #2b2d31;
      --card-sub: #232428;
      --card-border: rgba(255, 255, 255, 0.08);
      --input-bg: #111214;
      --text: #f2f3f5;
      --muted: #949ba4;
      --blurple: #5865f2;
      --blurple-hover: #4752c4;
      --green: #23a55a;
      --green-hover: #1f934f;
      --red: #f23f43;
      --red-hover: #da373c;
      --yellow: #f0b232;
    }

    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
    body { background: var(--bg); color: var(--text); min-height: 100vh; padding: 14px; max-width: 540px; margin: 0 auto; display: flex; flex-direction: column; }

    /* Header */
    header { display: flex; align-items: center; justify-content: space-between; padding-bottom: 12px; border-bottom: 1px solid var(--card-border); margin-bottom: 12px; }
    .brand { display: flex; align-items: center; gap: 10px; font-weight: 700; font-size: 1.15rem; color: #fff; }
    .brand svg { width: 28px; height: 28px; fill: var(--blurple); }
    .header-pills { display: flex; align-items: center; gap: 6px; }

    .badge { padding: 4px 9px; border-radius: 12px; font-size: 0.72rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.3px; display: inline-flex; align-items: center; gap: 4px; }
    .badge-green { background: rgba(35, 165, 90, 0.15); color: #23a55a; border: 1px solid rgba(35, 165, 90, 0.3); }
    .badge-red { background: rgba(242, 63, 67, 0.15); color: #f23f43; border: 1px solid rgba(242, 63, 67, 0.3); }
    .badge-yellow { background: rgba(240, 178, 50, 0.15); color: #f0b232; border: 1px solid rgba(240, 178, 50, 0.3); }
    .badge-gray { background: rgba(148, 155, 164, 0.15); color: var(--muted); border: 1px solid var(--card-border); }

    /* Navigation Tabs */
    .nav-tabs { display: flex; background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 12px; padding: 4px; gap: 4px; margin-bottom: 14px; }
    .tab-btn { flex: 1; background: transparent; border: none; color: var(--muted); padding: 8px 12px; border-radius: 8px; font-size: 0.84rem; font-weight: 600; cursor: pointer; transition: all 0.2s; display: flex; align-items: center; justify-content: center; gap: 6px; }
    .tab-btn.active { background: var(--blurple); color: #fff; box-shadow: 0 2px 8px rgba(88, 101, 242, 0.35); }
    .tab-count { background: rgba(0, 0, 0, 0.3); padding: 1px 6px; border-radius: 10px; font-size: 0.7rem; }

    /* Cards */
    .card { background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 12px; padding: 14px; margin-bottom: 12px; box-shadow: 0 4px 14px rgba(0,0,0,0.22); }
    .card-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
    .card-title { font-size: 0.78rem; font-weight: 700; color: var(--muted); text-transform: uppercase; letter-spacing: 0.5px; }

    /* Inputs & Buttons */
    .input-group { display: flex; gap: 8px; margin-top: 8px; }
    input[type="text"], input[type="password"] { flex: 1; background: var(--input-bg); border: 1px solid #35373c; color: #fff; padding: 10px 12px; border-radius: 8px; font-size: 0.88rem; outline: none; transition: border-color 0.2s; }
    input[type="text"]:focus, input[type="password"]:focus { border-color: var(--blurple); }
    
    .btn { background: var(--blurple); color: #fff; border: none; padding: 9px 14px; border-radius: 8px; font-weight: 600; font-size: 0.85rem; cursor: pointer; transition: background 0.15s, transform 0.1s, opacity 0.15s; display: inline-flex; align-items: center; justify-content: center; gap: 6px; user-select: none; }
    .btn:active { transform: scale(0.97); }
    .btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .btn-green { background: var(--green); }
    .btn-green:hover { background: var(--green-hover); }
    .btn-red { background: var(--red); }
    .btn-red:hover { background: var(--red-hover); }
    .btn-gray { background: #35373c; color: #dcddde; }
    .btn-gray:hover { background: #3f4248; }
    .btn-outline { background: transparent; border: 1px solid var(--card-border); color: var(--muted); }
    .btn-sm { padding: 5px 10px; font-size: 0.75rem; border-radius: 6px; }

    .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 8px; }
    .grid-3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px; margin-bottom: 12px; }

    /* Stat Cards */
    .stat-card { background: var(--card-sub); border: 1px solid var(--card-border); border-radius: 10px; padding: 10px; text-align: center; }
    .stat-val { font-size: 1.15rem; font-weight: 700; color: #fff; }
    .stat-lbl { font-size: 0.7rem; color: var(--muted); text-transform: uppercase; font-weight: 600; margin-top: 2px; }

    /* Account Selector Section */
    .acc-pills-wrap { display: flex; flex-wrap: wrap; gap: 6px; margin: 8px 0; }
    .acc-pill { background: var(--card-sub); border: 1px solid var(--card-border); border-radius: 20px; padding: 6px 12px; font-size: 0.8rem; font-weight: 600; display: inline-flex; align-items: center; gap: 6px; cursor: pointer; transition: all 0.15s; user-select: none; color: var(--muted); }
    .acc-pill.selected { background: rgba(88, 101, 242, 0.2); border-color: var(--blurple); color: #fff; }
    .acc-pill:active { transform: scale(0.96); }
    .acc-dot { width: 7px; height: 7px; border-radius: 50%; display: inline-block; }
    .acc-dot-green { background: var(--green); box-shadow: 0 0 6px var(--green); }
    .acc-dot-red { background: var(--red); }
    .acc-dot-yellow { background: var(--yellow); }

    /* Account Card in Accounts Tab */
    .acc-card { background: var(--card-sub); border: 1px solid var(--card-border); border-radius: 10px; padding: 12px; margin-bottom: 10px; transition: border-color 0.2s; }
    .acc-card.selected { border-color: var(--blurple); }
    .acc-header { display: flex; align-items: center; gap: 10px; }
    .acc-avatar { width: 38px; height: 38px; border-radius: 50%; background: #35373c; display: flex; align-items: center; justify-content: center; font-weight: 700; color: #fff; font-size: 0.9rem; flex-shrink: 0; overflow: hidden; }
    .acc-avatar img { width: 100%; height: 100%; object-fit: cover; }
    .acc-info { flex: 1; min-width: 0; }
    .acc-name-row { display: flex; align-items: center; gap: 6px; }
    .acc-nickname { font-weight: 700; font-size: 0.92rem; color: #fff; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .acc-tag { font-size: 0.72rem; color: var(--muted); }
    .acc-vc-status { font-size: 0.76rem; color: var(--green); margin-top: 3px; display: flex; align-items: center; gap: 4px; font-weight: 500; }
    .acc-vc-none { color: var(--muted); font-weight: normal; }
    
    .acc-chips { display: flex; flex-wrap: wrap; gap: 4px; margin: 8px 0; }
    .acc-chip { font-size: 0.7rem; padding: 2px 7px; border-radius: 6px; background: rgba(0, 0, 0, 0.25); border: 1px solid var(--card-border); color: #dcddde; }
    .acc-chip-active { background: rgba(35, 165, 90, 0.2); border-color: rgba(35, 165, 90, 0.4); color: #23a55a; font-weight: 600; }

    .acc-actions { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 8px; border-top: 1px solid rgba(255, 255, 255, 0.05); padding-top: 8px; }

    /* Memory / RAM Bar */
    .ram-container { margin-top: 8px; }
    .ram-bar-bg { background: var(--input-bg); border-radius: 6px; height: 10px; overflow: hidden; border: 1px solid var(--card-border); margin: 6px 0; }
    .ram-bar-fill { height: 100%; background: linear-gradient(90deg, var(--green), var(--blurple)); width: 0%; transition: width 0.4s ease, background 0.4s ease; }
    .ram-bar-fill.warning { background: linear-gradient(90deg, var(--yellow), var(--red)); }
    .ram-info { display: flex; justify-content: space-between; font-size: 0.73rem; color: var(--muted); }

    /* Logs */
    .logs-box { background: var(--input-bg); border: 1px solid var(--card-border); border-radius: 8px; padding: 10px; height: 260px; overflow-y: auto; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 0.73rem; line-height: 1.45; display: flex; flex-direction: column; gap: 4px; }
    .log-line { word-break: break-all; }
    .log-ts { color: #72767d; margin-right: 6px; }
    .log-error { color: var(--red); }
    .log-info { color: #dcddde; }

    /* Toast notification */
    .toast { position: fixed; bottom: 20px; left: 50%; transform: translateX(-50%); background: #2f3136; border: 1px solid var(--blurple); color: #fff; padding: 8px 16px; border-radius: 20px; font-size: 0.82rem; font-weight: 600; box-shadow: 0 4px 14px rgba(0,0,0,0.4); z-index: 100; opacity: 0; pointer-events: none; transition: opacity 0.25s ease; }
    .toast.show { opacity: 1; }

    /* Hidden helper */
    .tab-content { display: none; }
    .tab-content.active { display: block; }
  </style>
</head>
<body>

  <!-- Toast -->
  <div id="toast" class="toast">Action completed</div>

  <!-- Header -->
  <header>
    <div class="brand">
      <svg viewBox="0 0 127.14 96.36">
        <path d="M107.7,8.07A105.15,105.15,0,0,0,81.47,0a72.06,72.06,0,0,0-3.36,6.83A97.68,97.68,0,0,0,49,6.83,72.37,72.37,0,0,0,45.64,0,105.89,105.89,0,0,0,19.39,8.09C2.79,32.65-1.71,56.6.54,80.21h0A105.73,105.73,0,0,0,32.71,96.36,77.7,77.7,0,0,0,39.6,85.25a68.42,68.42,0,0,1-10.85-5.18c.91-.66,1.8-1.34,2.66-2a75.57,75.57,0,0,0,64.32,0c.87.71,1.76,1.39,2.66,2a68.68,68.68,0,0,1-10.87,5.19,77,77,0,0,0,6.89,11.1A105.25,105.25,0,0,0,126.6,80.22h0C129.24,52.84,122.09,29.11,107.7,8.07ZM42.45,65.69C36.18,65.69,31,60,31,53s5-12.74,11.43-12.74S54,45.91,53.89,53,48.84,65.69,42.45,65.69Zm42.24,0C78.41,65.69,73.25,60,73.25,53s5-12.74,11.44-12.74S96.23,45.91,96.12,53,91.08,65.69,84.69,65.69Z"/>
      </svg>
      Discord VC
    </div>
    <div class="header-pills">
      <div id="badgeStatus" class="badge badge-green">Ready</div>
      <button id="btnHeaderCleanRam" class="btn btn-outline btn-sm" title="Manually clean RAM">🧹 Clean</button>
    </div>
  </header>

  <!-- Navigation Tabs -->
  <nav class="nav-tabs">
    <button class="tab-btn active" data-tab="tab-control">🎮 Control</button>
    <button class="tab-btn" data-tab="tab-accounts">
      👥 Accounts <span id="navAccCount" class="tab-count">0/5</span>
    </button>
    <button class="tab-btn" data-tab="tab-logs">📜 Logs</button>
  </nav>

  <!-- TAB 1: CONTROL -->
  <div id="tab-control" class="tab-content active">
    <!-- Account Selector -->
    <div class="card">
      <div class="card-header">
        <div class="card-title">Select Accounts to Control</div>
        <div style="display: flex; gap: 6px;">
          <button id="btnSelectAll" class="btn btn-outline btn-sm">Select All</button>
          <button id="btnDeselectAll" class="btn btn-outline btn-sm">Clear</button>
        </div>
      </div>
      <div id="ctrlAccPills" class="acc-pills-wrap">
        <span style="font-size: 0.8rem; color: var(--muted);">No accounts loaded.</span>
      </div>
      <div id="ctrlSelectionSummary" style="font-size: 0.78rem; color: var(--muted); margin-top: 6px;">
        Selected: None
      </div>
    </div>

    <!-- Voice Channel Control -->
    <div class="card">
      <div class="card-title">Join Voice Channel</div>
      <div class="input-group">
        <input type="text" id="inputChannelId" placeholder="Voice Channel ID (e.g. 123456789012345678)">
        <button class="btn btn-green" id="btnJoinVc">Join VC</button>
      </div>
      <div style="margin-top: 8px;">
        <button class="btn btn-red" id="btnLeaveVc" style="width: 100%;">Leave VC (Selected)</button>
      </div>
    </div>

    <!-- Quick Voice & Stream Toggles -->
    <div class="card">
      <div class="card-title">Voice & Stream Toggles (Selected)</div>
      <div class="grid-2">
        <button class="btn btn-gray" id="btnToggleMute">🎙️ Mute</button>
        <button class="btn btn-gray" id="btnToggleDeaf">🎧 Deafen</button>
        <button class="btn btn-gray" id="btnToggleCam">📷 Camera</button>
        <button class="btn btn-gray" id="btnToggleStream">🖥️ Screenshare</button>
      </div>
    </div>

    <!-- RAM Optimizer Card -->
    <div class="card">
      <div class="card-header">
        <div class="card-title">Memory & RAM Optimizer</div>
        <button id="btnCleanRam" class="btn btn-outline btn-sm">🧹 Clean RAM</button>
      </div>
      <div class="ram-container">
        <div class="ram-bar-bg">
          <div id="ramBarFill" class="ram-bar-fill"></div>
        </div>
        <div class="ram-info">
          <span id="ramUsageTxt">RAM: 0.0 MB / 30.0 MB</span>
          <span id="ramAutoCleanTxt">Auto-Clean: Active (<30MB)</span>
        </div>
      </div>
    </div>
  </div>

  <!-- TAB 2: ACCOUNTS -->
  <div id="tab-accounts" class="tab-content">
    <!-- Top Stats -->
    <div class="grid-3">
      <div class="stat-card">
        <div id="statTotalAcc" class="stat-val">0/5</div>
        <div class="stat-lbl">Accounts</div>
      </div>
      <div class="stat-card">
        <div id="statOnlineAcc" class="stat-val">0</div>
        <div class="stat-lbl">Online</div>
      </div>
      <div class="stat-card">
        <div id="statVcAcc" class="stat-val">0</div>
        <div class="stat-lbl">In VC</div>
      </div>
    </div>

    <!-- Add Account Card -->
    <div class="card">
      <div class="card-header">
        <div class="card-title">Add Account (Max 5)</div>
        <span id="txtMaxLimitWarning" style="font-size: 0.72rem; color: var(--muted);"></span>
      </div>
      <div class="input-group">
        <input type="password" id="inputNewToken" placeholder="Paste Discord User Token...">
        <button class="btn btn-sm" id="btnToggleTokenVisibility" type="button" style="background: var(--card-sub); border: 1px solid var(--card-border);">👁️</button>
        <button class="btn btn-green" id="btnAddAccount">Add</button>
      </div>
      <div style="font-size: 0.72rem; color: var(--muted); margin-top: 6px;">
        Tokens are saved safely in config.json. Max 5 accounts allowed.
      </div>
    </div>

    <!-- Accounts List Container -->
    <div class="card">
      <div class="card-header">
        <div class="card-title">Logged-in Accounts</div>
        <div style="display: flex; gap: 6px;">
          <button id="btnSelectAllAccTab" class="btn btn-outline btn-sm">Select All</button>
        </div>
      </div>
      <div id="accountsListContainer">
        <div style="text-align: center; color: var(--muted); padding: 20px 0; font-size: 0.85rem;">
          No accounts added yet. Add one above!
        </div>
      </div>
    </div>
  </div>

  <!-- TAB 3: LOGS -->
  <div id="tab-logs" class="tab-content">
    <div class="card" style="margin-bottom: 0;">
      <div class="card-header">
        <div class="card-title">Live Terminal Logs</div>
        <div style="display: flex; gap: 8px;">
          <button id="btnToggleAutoScroll" class="btn btn-outline btn-sm">Scroll: ON</button>
          <button id="btnClearLogs" class="btn btn-outline btn-sm">Clear</button>
        </div>
      </div>
      <div class="logs-box" id="logsBox"></div>
    </div>
  </div>

  <script>
    // Tab switching
    const tabButtons = document.querySelectorAll('.tab-btn');
    const tabContents = document.querySelectorAll('.tab-content');

    tabButtons.forEach(btn => {
      btn.addEventListener('click', () => {
        const target = btn.dataset.tab;
        tabButtons.forEach(b => b.classList.remove('active'));
        tabContents.forEach(c => c.classList.remove('active'));
        btn.classList.add('active');
        document.getElementById(target).classList.add('active');
      });
    });

    // Toast helper
    function showToast(msg) {
      const toast = document.getElementById('toast');
      toast.textContent = msg;
      toast.classList.add('show');
      setTimeout(() => toast.classList.remove('show'), 2600);
    }

    // Toggle token password visibility
    const inputToken = document.getElementById('inputNewToken');
    const btnToggleVis = document.getElementById('btnToggleTokenVisibility');
    btnToggleVis.addEventListener('click', () => {
      if (inputToken.type === 'password') {
        inputToken.type = 'text';
        btnToggleVis.textContent = '🔒';
      } else {
        inputToken.type = 'password';
        btnToggleVis.textContent = '👁️';
      }
    });

    // State tracking
    let latestData = null;
    let selectedAccountIds = new Set();
    let autoScroll = true;

    // API Poster
    async function apiPost(action, body = {}) {
      try {
        const res = await fetch('/api/action', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ action, ...body })
        });
        const json = await res.json();
        if (json.ok) {
          showToast(json.result || 'Success');
          updateState();
          return json;
        } else {
          showToast('Error: ' + (json.error || 'Failed'));
        }
      } catch (err) {
        showToast('Network error: ' + err.message);
      }
    }

    // Account selection helpers
    function toggleAccountSelection(id) {
      if (selectedAccountIds.has(id)) {
        selectedAccountIds.delete(id);
      } else {
        selectedAccountIds.add(id);
      }
      renderSelections();
    }

    function selectAllAccounts() {
      if (!latestData?.accounts) return;
      latestData.accounts.forEach(a => selectedAccountIds.add(a.id));
      renderSelections();
    }

    function deselectAllAccounts() {
      selectedAccountIds.clear();
      renderSelections();
    }

    document.getElementById('btnSelectAll').addEventListener('click', selectAllAccounts);
    document.getElementById('btnDeselectAll').addEventListener('click', deselectAllAccounts);
    document.getElementById('btnSelectAllAccTab').addEventListener('click', selectAllAccounts);

    // Join VC button
    document.getElementById('btnJoinVc').addEventListener('click', () => {
      const channelId = document.getElementById('inputChannelId').value.trim();
      if (!channelId) return alert('Please enter a voice channel ID');
      const targetIds = Array.from(selectedAccountIds);
      if (targetIds.length === 0) return alert('Please select at least one account');
      apiPost('joinVc', { channelId, accountIds: targetIds });
    });

    // Leave VC button
    document.getElementById('btnLeaveVc').addEventListener('click', () => {
      const targetIds = Array.from(selectedAccountIds);
      if (targetIds.length === 0) return alert('Please select at least one account');
      apiPost('leaveVc', { accountIds: targetIds });
    });

    // Voice control buttons
    document.getElementById('btnToggleMute').addEventListener('click', () => {
      const targetIds = Array.from(selectedAccountIds);
      apiPost('toggleMute', { accountIds: targetIds });
    });

    document.getElementById('btnToggleDeaf').addEventListener('click', () => {
      const targetIds = Array.from(selectedAccountIds);
      apiPost('toggleDeaf', { accountIds: targetIds });
    });

    document.getElementById('btnToggleCam').addEventListener('click', () => {
      const targetIds = Array.from(selectedAccountIds);
      apiPost('toggleCam', { accountIds: targetIds });
    });

    document.getElementById('btnToggleStream').addEventListener('click', () => {
      const targetIds = Array.from(selectedAccountIds);
      apiPost('toggleStream', { accountIds: targetIds });
    });

    // RAM Clean buttons
    const triggerCleanRam = () => apiPost('cleanRam');
    document.getElementById('btnCleanRam').addEventListener('click', triggerCleanRam);
    document.getElementById('btnHeaderCleanRam').addEventListener('click', triggerCleanRam);

    // Add Account button
    document.getElementById('btnAddAccount').addEventListener('click', () => {
      const token = inputToken.value.trim();
      if (!token) return alert('Please paste a Discord user token');
      apiPost('addAccount', { token }).then(res => {
        if (res && res.ok) {
          inputToken.value = '';
        }
      });
    });

    // Logs controls
    document.getElementById('btnClearLogs').addEventListener('click', () => {
      document.getElementById('logsBox').innerHTML = '';
    });

    const btnAutoScroll = document.getElementById('btnToggleAutoScroll');
    btnAutoScroll.addEventListener('click', () => {
      autoScroll = !autoScroll;
      btnAutoScroll.textContent = 'Scroll: ' + (autoScroll ? 'ON' : 'OFF');
    });

    // Render selection pills & summary
    function renderSelections() {
      if (!latestData?.accounts) return;
      const pillsContainer = document.getElementById('ctrlAccPills');
      const summary = document.getElementById('ctrlSelectionSummary');

      if (latestData.accounts.length === 0) {
        pillsContainer.innerHTML = '<span style="font-size: 0.8rem; color: var(--muted);">No accounts added yet.</span>';
        summary.textContent = 'Selected: None';
        return;
      }

      pillsContainer.innerHTML = latestData.accounts.map(acc => {
        const isSelected = selectedAccountIds.has(acc.id);
        const dotColor = acc.status === 'online' ? 'acc-dot-green' : (acc.status === 'error' ? 'acc-dot-red' : 'acc-dot-yellow');
        return \`<div class="acc-pill \${isSelected ? 'selected' : ''}" onclick="toggleAccountSelection('\${acc.id}')">
          <span class="acc-dot \${dotColor}"></span>
          <span>\${escapeHtml(acc.nickname)}</span>
          \${acc.voice.inVc ? '<span style="font-size: 0.68rem; color: var(--green);">🔊</span>' : ''}
        </div>\`;
      }).join('');

      const selectedNames = latestData.accounts
        .filter(a => selectedAccountIds.has(a.id))
        .map(a => a.nickname);

      if (selectedNames.length > 0) {
        summary.innerHTML = 'Selected (' + selectedNames.length + '): <b style="color: #fff;">' + selectedNames.map(escapeHtml).join(', ') + '</b>';
      } else {
        summary.textContent = 'Selected: None (Select account pills above to control)';
      }

      // Also sync checkbox states in Accounts tab
      latestData.accounts.forEach(acc => {
        const chk = document.getElementById('chk_acc_' + acc.id);
        if (chk) chk.checked = selectedAccountIds.has(acc.id);
        const card = document.getElementById('card_acc_' + acc.id);
        if (card) {
          if (selectedAccountIds.has(acc.id)) card.classList.add('selected');
          else card.classList.remove('selected');
        }
      });
    }

    function escapeHtml(str) {
      if (!str) return '';
      return String(str).replace(/[&<>"']/g, m => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[m]);
    }

    // Refresh state from server
    async function updateState() {
      try {
        const res = await fetch('/api/status');
        const data = await res.json();
        latestData = data;

        // Header status badge
        const badge = document.getElementById('badgeStatus');
        if (data.stats.loggedInCount > 0) {
          badge.className = 'badge badge-green';
          badge.textContent = data.stats.loggedInCount + ' Online';
        } else {
          badge.className = 'badge badge-yellow';
          badge.textContent = 'Standby';
        }

        // Nav counts
        document.getElementById('navAccCount').textContent = data.stats.totalAccounts + '/' + data.stats.maxAccounts;

        // Top stats
        document.getElementById('statTotalAcc').textContent = data.stats.totalAccounts + '/' + data.stats.maxAccounts;
        document.getElementById('statOnlineAcc').textContent = data.stats.loggedInCount;
        document.getElementById('statVcAcc').textContent = data.stats.activeVcCount;

        // Max limit enforcement on Add button
        const btnAdd = document.getElementById('btnAddAccount');
        const warning = document.getElementById('txtMaxLimitWarning');
        if (data.stats.totalAccounts >= data.stats.maxAccounts) {
          btnAdd.disabled = true;
          warning.textContent = 'Max limit (5) reached';
          warning.style.color = 'var(--red)';
        } else {
          btnAdd.disabled = false;
          warning.textContent = '';
        }

        // RAM Bar
        const ramUsed = data.memory.heapUsedMb;
        const ramLimit = data.memory.limitMb;
        const ramPercent = Math.min(100, Math.round((ramUsed / ramLimit) * 100));
        const ramFill = document.getElementById('ramBarFill');
        ramFill.style.width = ramPercent + '%';
        if (ramPercent >= 90) ramFill.classList.add('warning');
        else ramFill.classList.remove('warning');

        document.getElementById('ramUsageTxt').textContent = 'Heap: ' + ramUsed + ' MB / ' + ramLimit + ' MB (RSS: ' + data.memory.rssMb + ' MB)';
        document.getElementById('ramAutoCleanTxt').textContent = 'Cleaned: ' + data.memory.cleanedCount + 'x (<30MB)';

        // Ensure selection default: if nothing selected yet and accounts exist, select all online
        if (selectedAccountIds.size === 0 && data.accounts.length > 0) {
          data.accounts.forEach(a => {
            if (a.status === 'online') selectedAccountIds.add(a.id);
          });
        }

        // Update selected toggle buttons (Red when active, normal/gray when inactive)
        const selectedAccs = data.accounts.filter(a => selectedAccountIds.has(a.id));
        const anyMuted = selectedAccs.some(a => a.voice.isMuted);
        const anyDeaf = selectedAccs.some(a => a.voice.isDeafened);
        const anyCam = selectedAccs.some(a => a.voice.isCamOn);
        const anyStream = selectedAccs.some(a => a.voice.isStreaming);

        const btnMute = document.getElementById('btnToggleMute');
        if (btnMute) {
          btnMute.className = 'btn ' + (anyMuted ? 'btn-red' : 'btn-gray');
          btnMute.textContent = anyMuted ? '🎙️ Muted' : '🎙️ Mute';
        }

        const btnDeaf = document.getElementById('btnToggleDeaf');
        if (btnDeaf) {
          btnDeaf.className = 'btn ' + (anyDeaf ? 'btn-red' : 'btn-gray');
          btnDeaf.textContent = anyDeaf ? '🎧 Deafened' : '🎧 Deafen';
        }

        const btnCam = document.getElementById('btnToggleCam');
        if (btnCam) {
          btnCam.className = 'btn ' + (anyCam ? 'btn-red' : 'btn-gray');
          btnCam.textContent = anyCam ? '📷 Cam On' : '📷 Cam Off';
        }

        const btnStream = document.getElementById('btnToggleStream');
        if (btnStream) {
          btnStream.className = 'btn ' + (anyStream ? 'btn-red' : 'btn-gray');
          btnStream.textContent = anyStream ? '🖥️ Stream On' : '🖥️ Screenshare';
        }

        // Render Selection Pills on Control Tab
        renderSelections();

        // Render Accounts List in Accounts Tab
        renderAccountsList(data.accounts);

        // Update logs
        const logsBox = document.getElementById('logsBox');
        if (data.logs && data.logs.length) {
          logsBox.innerHTML = data.logs.map(l =>
            \`<div class="log-line \${l.type === 'error' ? 'log-error' : 'log-info'}"><span class="log-ts">[\${l.ts}]</span>\${escapeHtml(l.text)}</div>\`
          ).join('');
          if (autoScroll) {
            logsBox.scrollTop = logsBox.scrollHeight;
          }
        }
      } catch (err) {}
    }

    // Render Accounts List in Accounts Tab
    function renderAccountsList(accounts) {
      const container = document.getElementById('accountsListContainer');
      if (!accounts || accounts.length === 0) {
        container.innerHTML = '<div style="text-align: center; color: var(--muted); padding: 20px 0; font-size: 0.85rem;">No accounts added yet. Add one above!</div>';
        return;
      }

      container.innerHTML = accounts.map(acc => {
        const isSelected = selectedAccountIds.has(acc.id);
        const statusBadge = acc.status === 'online'
          ? '<span class="badge badge-green">ONLINE</span>'
          : (acc.status === 'error' ? '<span class="badge badge-red">ERROR</span>' : '<span class="badge badge-yellow">CONNECTING</span>');

        const avatarHtml = acc.avatarUrl
          ? \`<img src="\${acc.avatarUrl}" alt="Avatar">\`
          : \`<span>\${(acc.nickname || 'A').charAt(0).toUpperCase()}</span>\`;

        return \`
        <div class="acc-card \${isSelected ? 'selected' : ''}" id="card_acc_\${acc.id}">
          <div class="acc-header">
            <input type="checkbox" id="chk_acc_\${acc.id}" \${isSelected ? 'checked' : ''} onchange="toggleAccountSelection('\${acc.id}')" style="width: 16px; height: 16px; accent-color: var(--blurple); cursor: pointer;">
            <div class="acc-avatar">\${avatarHtml}</div>
            <div class="acc-info">
              <div class="acc-name-row">
                <span class="acc-nickname">\${escapeHtml(acc.nickname)}</span>
                \${statusBadge}
              </div>
              <div class="acc-tag">@\${escapeHtml(acc.username)} &bull; \${acc.id}</div>
              <div class="acc-vc-status \${acc.voice.inVc ? '' : 'acc-vc-none'}">
                \${acc.voice.inVc ? '🔊 In VC: ' + escapeHtml(acc.voice.channelName) : '⚪ Not in voice'}
              </div>
            </div>
          </div>

          \${acc.errorMessage ? \`<div style="font-size: 0.72rem; color: var(--red); margin-top: 6px;">\${escapeHtml(acc.errorMessage)}</div>\` : ''}

          <!-- Quick chips -->
          <div class="acc-chips">
            <span class="acc-chip \${acc.voice.isMuted ? 'acc-chip-active' : ''}">\${acc.voice.isMuted ? '🎙️ Muted' : '🎙️ Unmuted'}</span>
            <span class="acc-chip \${acc.voice.isDeafened ? 'acc-chip-active' : ''}">\${acc.voice.isDeafened ? '🎧 Deafened' : '🎧 Undeaf'}</span>
            <span class="acc-chip \${acc.voice.isCamOn ? 'acc-chip-active' : ''}">\${acc.voice.isCamOn ? '📷 Cam ON' : '📷 Cam OFF'}</span>
            <span class="acc-chip \${acc.voice.isStreaming ? 'acc-chip-active' : ''}">\${acc.voice.isStreaming ? '🖥️ Streaming' : '🖥️ No Stream'}</span>
          </div>

          <!-- Actions per account (Red when active, normal/gray when inactive) -->
          <div class="acc-actions">
            <button class="btn \${acc.voice.isMuted ? 'btn-red' : 'btn-gray'} btn-sm" onclick="apiPost('toggleMute', { accountId: '\${acc.id}' })">🎙️</button>
            <button class="btn \${acc.voice.isDeafened ? 'btn-red' : 'btn-gray'} btn-sm" onclick="apiPost('toggleDeaf', { accountId: '\${acc.id}' })">🎧</button>
            <button class="btn \${acc.voice.isCamOn ? 'btn-red' : 'btn-gray'} btn-sm" onclick="apiPost('toggleCam', { accountId: '\${acc.id}' })">📷</button>
            <button class="btn \${acc.voice.isStreaming ? 'btn-red' : 'btn-gray'} btn-sm" onclick="apiPost('toggleStream', { accountId: '\${acc.id}' })">🖥️</button>
            \${acc.voice.inVc ? \`<button class="btn btn-red btn-sm" onclick="apiPost('leaveVc', { accountId: '\${acc.id}' })">Leave VC</button>\` : ''}
            <button class="btn btn-red btn-sm" style="margin-left: auto;" onclick="if(confirm('Remove account \${escapeHtml(acc.nickname)}?')) apiPost('removeAccount', { accountId: '\${acc.id}' })">🗑️ Remove</button>
          </div>
        </div>\`;
      }).join('');
    }

    setInterval(updateState, 1800);
    updateState();
  </script>
</body>
</html>`;

  const server = http.createServer((req, res) => {
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

    if (url.pathname === '/' || url.pathname === '/index.html') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      return res.end(html);
    }

    if (url.pathname === '/manifest.json') {
      const manifest = {
        name: 'Discord VC Multi-Bot',
        short_name: 'VC Bot',
        start_url: '/',
        display: 'standalone',
        background_color: '#1e1f22',
        theme_color: '#1e1f22',
        icons: [
          {
            src: '/icon.png',
            sizes: '192x192',
            type: 'image/png',
          },
        ],
      };
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify(manifest));
    }

    if (url.pathname === '/icon.png') {
      const iconPath = path.join(__dirname, 'stream.png');
      if (fs.existsSync(iconPath)) {
        res.writeHead(200, { 'Content-Type': 'image/png' });
        return fs.createReadStream(iconPath).pipe(res);
      }
      res.writeHead(404);
      return res.end();
    }

    if (url.pathname === '/api/status') {
      const state = botState();
      state.logs = logHistory.slice(-50);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify(state));
    }

    if (url.pathname === '/api/action' && req.method === 'POST') {
      let body = '';
      req.on('data', (chunk) => {
        body += chunk;
      });
      req.on('end', async () => {
        try {
          const data = JSON.parse(body || '{}');
          const result = await actions(data.action, data);
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: true, result }));
        } catch (err) {
          res.writeHead(500, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: false, error: err.message }));
        }
      });
      return;
    }

    res.writeHead(404);
    res.end('Not Found');
  });

  server.listen(port, '0.0.0.0', () => {
    console.log(`[+] Mobile Web App / PWA Dashboard live at: http://localhost:${port}`);
  });

  return server;
}

module.exports = { createDashboard };
