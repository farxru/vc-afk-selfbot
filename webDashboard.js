const http = require('http');
const fs = require('fs');
const path = require('path');

/**
 * Lightweight, zero-dependency Mobile Web Dashboard & Controller.
 * Enables phone access with "Add to Home Screen" (PWA) support.
 */
function createDashboard(botState, actions, port = 3000) {
  const logHistory = [];
  const MAX_LOGS = 200;

  // Intercept console.log and console.error to broadcast to mobile dashboard
  const origLog = console.log;
  const origErr = console.error;

  function addLog(type, args) {
    const ts = new Date().toLocaleTimeString();
    const text = args.map(a => (typeof a === 'object' ? JSON.stringify(a) : String(a))).join(' ');
    const entry = { ts, type, text };
    logHistory.push(entry);
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
  <title>Discord VC Bot</title>
  <link rel="manifest" href="/manifest.json">
  <meta name="theme-color" content="#202225">
  <meta name="mobile-web-app-capable" content="yes">
  <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
  <link rel="apple-touch-icon" href="/icon.png">
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
    body { background: #202225; color: #dcddde; display: flex; flex-direction: column; min-height: 100vh; padding: 16px; max-width: 480px; margin: 0 auto; }
    header { display: flex; align-items: center; justify-content: space-between; padding-bottom: 16px; border-bottom: 1px solid #2f3136; margin-bottom: 16px; }
    .brand { display: flex; align-items: center; gap: 10px; font-weight: 700; font-size: 1.15rem; color: #fff; }
    .brand svg { width: 28px; height: 28px; fill: #5865f2; }
    .badge { padding: 4px 10px; border-radius: 20px; font-size: 0.75rem; font-weight: 600; text-transform: uppercase; }
    .badge-online { background: rgba(59, 165, 93, 0.2); color: #3ba55d; border: 1px solid #3ba55d; }
    .badge-offline { background: rgba(237, 66, 69, 0.2); color: #ed4245; border: 1px solid #ed4245; }

    .card { background: #2f3136; border-radius: 12px; padding: 16px; margin-bottom: 14px; box-shadow: 0 4px 12px rgba(0,0,0,0.25); }
    .card-title { font-size: 0.8rem; font-weight: 700; color: #b9bbbe; text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 10px; }
    
    .status-row { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; font-size: 0.9rem; }
    .status-val { font-weight: 600; color: #fff; }
    
    .input-group { display: flex; gap: 8px; margin-top: 10px; }
    input[type="text"] { flex: 1; background: #202225; border: 1px solid #40444b; color: #fff; padding: 10px 12px; border-radius: 8px; font-size: 0.9rem; outline: none; transition: border-color 0.2s; }
    input[type="text"]:focus { border-color: #5865f2; }
    
    .btn { background: #5865f2; color: #fff; border: none; padding: 10px 16px; border-radius: 8px; font-weight: 600; font-size: 0.9rem; cursor: pointer; transition: filter 0.2s, transform 0.1s; display: inline-flex; align-items: center; justify-content: center; gap: 6px; user-select: none; }
    .btn:active { transform: scale(0.97); }
    .btn-green { background: #3ba55d; }
    .btn-red { background: #ed4245; }
    .btn-gray { background: #4f545c; }
    .btn-active { border: 2px solid #fff; }

    .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 10px; }

    .logs-box { background: #18191c; border-radius: 8px; padding: 10px; height: 180px; overflow-y: auto; font-family: monospace; font-size: 0.75rem; line-height: 1.4; display: flex; flex-direction: column; gap: 4px; }
    .log-line { word-break: break-all; }
    .log-ts { color: #72767d; margin-right: 6px; }
    .log-error { color: #ed4245; }
    .log-info { color: #dcddde; }

    .install-banner { background: linear-gradient(135deg, #5865f2, #4752c4); color: white; padding: 12px; border-radius: 10px; margin-bottom: 14px; display: none; align-items: center; justify-content: space-between; }
    .install-banner p { font-size: 0.85rem; font-weight: 500; }
  </style>
</head>
<body>
  <div id="installBanner" class="install-banner">
    <p>📲 Install App to Home Screen</p>
    <button class="btn btn-green" id="btnInstall" style="padding: 6px 12px; font-size: 0.8rem;">Install</button>
  </div>

  <header>
    <div class="brand">
      <svg viewBox="0 0 127.14 96.36">
        <path d="M107.7,8.07A105.15,105.15,0,0,0,81.47,0a72.06,72.06,0,0,0-3.36,6.83A97.68,97.68,0,0,0,49,6.83,72.37,72.37,0,0,0,45.64,0,105.89,105.89,0,0,0,19.39,8.09C2.79,32.65-1.71,56.6.54,80.21h0A105.73,105.73,0,0,0,32.71,96.36,77.7,77.7,0,0,0,39.6,85.25a68.42,68.42,0,0,1-10.85-5.18c.91-.66,1.8-1.34,2.66-2a75.57,75.57,0,0,0,64.32,0c.87.71,1.76,1.39,2.66,2a68.68,68.68,0,0,1-10.87,5.19,77,77,0,0,0,6.89,11.1A105.25,105.25,0,0,0,126.6,80.22h0C129.24,52.84,122.09,29.11,107.7,8.07ZM42.45,65.69C36.18,65.69,31,60,31,53s5-12.74,11.43-12.74S54,45.91,53.89,53,48.84,65.69,42.45,65.69Zm42.24,0C78.41,65.69,73.25,60,73.25,53s5-12.74,11.44-12.74S96.23,45.91,96.12,53,91.08,65.69,84.69,65.69Z"/>
      </svg>
      Discord VC Bot
    </div>
    <div id="statusBadge" class="badge badge-online">Connecting...</div>
  </header>

  <!-- Status Card -->
  <div class="card">
    <div class="card-title">Live Presence</div>
    <div class="status-row">
      <span>Account:</span>
      <span id="txtUser" class="status-val">Loading...</span>
    </div>
    <div class="status-row">
      <span>Active VC:</span>
      <span id="txtChannel" class="status-val">None</span>
    </div>
    <div class="status-row">
      <span>Uptime:</span>
      <span id="txtUptime" class="status-val">0s</span>
    </div>
  </div>

  <!-- Voice Control Card -->
  <div class="card">
    <div class="card-title">Join Voice Channel</div>
    <div class="input-group">
      <input type="text" id="inputChannelId" placeholder="Voice Channel ID (e.g. 123456789)">
      <button class="btn btn-green" id="btnJoin">Join</button>
    </div>
    <div style="margin-top: 10px;">
      <button class="btn btn-red" id="btnLeave" style="width: 100%;">Leave Voice Channel</button>
    </div>
  </div>

  <!-- Quick Toggles -->
  <div class="card">
    <div class="card-title">Voice & Stream Toggles</div>
    <div class="grid-2">
      <button class="btn btn-gray" id="btnMute">🎙️ Mute</button>
      <button class="btn btn-gray" id="btnDeaf">🎧 Deafen</button>
      <button class="btn btn-gray" id="btnCam">📷 Camera</button>
      <button class="btn btn-gray" id="btnStream">🖥️ Screenshare</button>
    </div>
  </div>

  <!-- Console Logs Card -->
  <div class="card" style="flex: 1; display: flex; flex-direction: column;">
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;">
      <div class="card-title" style="margin-bottom: 0;">Live Logs</div>
      <button id="btnClearLogs" style="background: none; border: none; color: #5865f2; font-size: 0.75rem; cursor: pointer;">Clear</button>
    </div>
    <div class="logs-box" id="logsBox"></div>
  </div>

  <script>
    let deferredPrompt;
    window.addEventListener('beforeinstallprompt', (e) => {
      e.preventDefault();
      deferredPrompt = e;
      document.getElementById('installBanner').style.display = 'flex';
    });

    document.getElementById('btnInstall').addEventListener('click', async () => {
      if (deferredPrompt) {
        deferredPrompt.prompt();
        const { outcome } = await deferredPrompt.userChoice;
        if (outcome === 'accepted') {
          document.getElementById('installBanner').style.display = 'none';
        }
        deferredPrompt = null;
      }
    });

    // API calls
    async function apiPost(action, body = {}) {
      try {
        const res = await fetch('/api/action', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ action, ...body })
        });
        return await res.json();
      } catch (err) {
        console.error('API Error:', err);
      }
    }

    document.getElementById('btnJoin').addEventListener('click', () => {
      const channelId = document.getElementById('inputChannelId').value.trim();
      if (!channelId) return alert('Enter channel ID');
      apiPost('joinVc', { channelId });
    });

    document.getElementById('btnLeave').addEventListener('click', () => apiPost('leaveVc'));
    document.getElementById('btnMute').addEventListener('click', () => apiPost('toggleMute'));
    document.getElementById('btnDeaf').addEventListener('click', () => apiPost('toggleDeaf'));
    document.getElementById('btnCam').addEventListener('click', () => apiPost('toggleCam'));
    document.getElementById('btnStream').addEventListener('click', () => apiPost('toggleStream'));
    document.getElementById('btnClearLogs').addEventListener('click', () => {
      document.getElementById('logsBox').innerHTML = '';
    });

    // Refresh state every 2 seconds
    async function updateState() {
      try {
        const res = await fetch('/api/status');
        const data = await res.json();

        const badge = document.getElementById('statusBadge');
        if (data.online) {
          badge.className = 'badge badge-online';
          badge.textContent = 'ONLINE';
        } else {
          badge.className = 'badge badge-offline';
          badge.textContent = 'OFFLINE';
        }

        document.getElementById('txtUser').textContent = data.user || 'Not logged in';
        document.getElementById('txtChannel').textContent = data.channel ? data.channel : 'Not in VC';
        document.getElementById('txtUptime').textContent = data.uptime + 's';

        // Update button states
        const btnMute = document.getElementById('btnMute');
        btnMute.textContent = data.isMuted ? '🎙️ Unmute' : '🎙️ Mute';
        btnMute.className = 'btn ' + (data.isMuted ? 'btn-red' : 'btn-gray');

        const btnDeaf = document.getElementById('btnDeaf');
        btnDeaf.textContent = data.isDeafened ? '🎧 Undeafen' : '🎧 Deafen';
        btnDeaf.className = 'btn ' + (data.isDeafened ? 'btn-red' : 'btn-gray');

        const btnCam = document.getElementById('btnCam');
        btnCam.textContent = data.isCamOn ? '📷 Cam Off' : '📷 Cam On';
        btnCam.className = 'btn ' + (data.isCamOn ? 'btn-green' : 'btn-gray');

        const btnStream = document.getElementById('btnStream');
        btnStream.textContent = data.isStreaming ? '🖥️ Stop Stream' : '🖥️ Screenshare';
        btnStream.className = 'btn ' + (data.isStreaming ? 'btn-green' : 'btn-gray');

        // Update logs
        const logsBox = document.getElementById('logsBox');
        if (data.logs && data.logs.length) {
          logsBox.innerHTML = data.logs.map(l => 
            \`<div class="log-line \${l.type === 'error' ? 'log-error' : 'log-info'}"><span class="log-ts">[\${l.ts}]</span>\${l.text}</div>\`
          ).join('');
          logsBox.scrollTop = logsBox.scrollHeight;
        }
      } catch (err) {}
    }

    setInterval(updateState, 2000);
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
        name: 'Discord VC Bot',
        short_name: 'VC Bot',
        start_url: '/',
        display: 'standalone',
        background_color: '#202225',
        theme_color: '#202225',
        icons: [
          {
            src: '/icon.png',
            sizes: '192x192',
            type: 'image/png'
          }
        ]
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
      req.on('data', chunk => { body += chunk; });
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
