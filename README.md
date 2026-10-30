<div align="center">
  <img src="banner.png" alt="VC AFK Selfbot Banner" width="100%" style="border-radius: 12px; margin-bottom: 16px;">

  # VC AFK &middot; 24/7 Voice Channel Selfbot
  
  **An enterprise-grade, lightweight solution for maintaining 24/7 presence in Discord voice channels directly from your Android smartphone or PC.**
  
  <p>
    <a href="https://github.com/farxru/vc-afk-selfbot/releases/download/latest/DiscordVCBot.apk">
      <img src="https://img.shields.io/badge/Download-Release%20APK-5865F2?style=for-the-badge&logo=android&logoColor=white" alt="Download APK">
    </a>
    <a href="https://github.com/farxru/vc-afk-selfbot/releases/latest">
      <img src="https://img.shields.io/badge/GitHub-Releases-2B2D31?style=for-the-badge&logo=github&logoColor=white" alt="GitHub Releases">
    </a>
    <a href="LICENSE">
      <img src="https://img.shields.io/badge/License-MIT-3BA55D?style=for-the-badge" alt="License">
    </a>
    <img src="https://img.shields.io/badge/Host-On%20Phone%20($0)-FAA61A?style=for-the-badge&logo=googlecloud&logoColor=white" alt="Zero Cost Hosting">
    <img src="https://img.shields.io/badge/Privacy-100%25%20Client--Side-ED4245?style=for-the-badge&logo=shield&logoColor=white" alt="Client Side Privacy">
  </p>
</div>

---

## Executive Overview

Conventional 24/7 Discord bots enforce recurring monthly VPS hosting costs, require power-hungry desktop computers running overnight, or demand uploading private account tokens to untrusted third-party cloud platforms.

**VC AFK eliminates these compromises entirely.** By leveraging a native Android Foreground Service with raw Gateway WebSockets, your phone operates as an autonomous, self-contained 24/7 host. The bot continues running continuously in the background—even with your device locked, screen turned off, or during network transitions.

### Architectural Advantages

| Metric | Traditional Cloud / VPS Bots | VC AFK Phone Hosting |
|---|---|---|
| **Monthly Cost** | $5.00 – $20.00 / month | **$0.00 (Free forever)** |
| **Data Privacy** | Tokens stored on remote cloud servers | **100% Local & Private (`SharedPreferences`)** |
| **Network Security** | Proxied through third-party servers | **Direct TLS connection to Discord Gateway** |
| **RAM Footprint** | 100MB – 300MB (Electron/Browser) | **< 15MB (Raw Event-Driven Socket)** |
| **Uptime Guarantee** | Subject to server reboots & maintenance | **Android Foreground Service (`START_STICKY`)** |
| **Disconnection Handling**| Manual reconnect required | **Automated 1-second reconnect on kick/shift** |

---

## Core Capabilities

* 📱 **Native Android Phone Hosting (Zero-Cost)**: Eliminates VPS bills, cloud infrastructure, and terminal commands. Runs quietly as an official Android Foreground Service.
* 🛡️ **Zero-Trust Client-Side Architecture**: Account tokens never touch external APIs, analytics collectors, or webhooks. Configuration is strictly isolated to your device's private sandbox.
* 🔄 **Instant Auto-Rejoin Protocol**: If an administrator or moderator kicks, disconnects, or moves your account from the voice channel, the internal watchdog re-enters the channel within **1 second**.
* ⚡ **Main Client Seamless Coexistence**: Active on the official Discord mobile or desktop client? VC AFK detects changes in voice state and cleanly synchronizes back to your designated AFK room without collision.
* 🔋 **Battery Optimization Standard**: Built using non-blocking I/O (`OkHttp3` WebSocket engine). Consumes minimal CPU cycles and under 1% battery over a full 24-hour cycle.
* 📡 **Liveness Watchdog**: An automated 15-second heartbeat monitor detects transient packet drops or cell tower handovers, re-establishing session state transparently.
* 🎥 **Virtual Stream & Camera Emulation**: Broadcast simulated camera streams (`.cam` / `.camoff`) and Go Live screenshare streams (`.ss` / `.ssoff`) with zero video encoding overhead.
* 💻 **Cross-Platform Compatibility**: Available as a native Android APK, an unobtrusive Windows System Tray daemon, or a headless Node.js daemon for Linux and macOS.

---

## Installation & Deployment

### Method 1: Android Phone (Primary Deployment)

<div align="center">
  <img src="icon.png" width="104" height="104" alt="VC AFK Application Icon" style="border-radius: 22px; box-shadow: 0 4px 18px rgba(88, 101, 242, 0.35); margin-bottom: 12px;">
  <br>
  
  [![Download Android Release APK](https://img.shields.io/badge/Download%20Release%20APK-DiscordVCBot.apk-5865F2?style=for-the-badge&logo=android&logoColor=white)](https://github.com/farxru/vc-afk-selfbot/releases/download/latest/DiscordVCBot.apk)
  &nbsp;&nbsp;
  [![View Release Notes](https://img.shields.io/badge/All%20Releases-GitHub%20Releases-2B2D31?style=for-the-badge&logo=github&logoColor=white)](https://github.com/farxru/vc-afk-selfbot/releases/latest)
</div>

#### Step 1: Install the Application
1. Download **[`DiscordVCBot.apk`](https://github.com/farxru/vc-afk-selfbot/releases/download/latest/DiscordVCBot.apk)** directly to your Android device.
2. Tap the downloaded file to install. If prompted by Android, grant permission to *Install from this source*.
3. The application is pre-signed with V1 & V2 APK signatures and complies with Google Play Protect standards.

#### Step 2: Configure Android Battery Exemption (Critical for 24/7 Stability)
To prevent Android's aggressive battery manager from suspending the process during sleep:

> [!IMPORTANT]
> **Disable Battery Optimization**:
> * **Standard Android**: Navigate to **Settings ⚙️ ➔ Apps ➔ VC AFK Bot ➔ Battery** ➔ Select **Unrestricted** (or *Don't optimize*).
> * **Samsung One UI**: **Settings ➔ Battery ➔ Background usage limits ➔ Never sleeping apps** ➔ Add **VC AFK Bot**.
> * **Xiaomi / Redmi / POCO**: App Info ➔ Enable **Autostart** ➔ Set Battery Saver to **No restrictions**.
> * **OnePlus / Realme / OPPO**: App Info ➔ Battery ➔ Enable **Allow background activity** and **Allow auto-launch**.

#### Step 3: Connect & Initialize
1. Launch **VC AFK Bot**.
2. Enter your **Account Token** and your **Discord User ID**. *(Saved encrypted and locally on your phone)*.
3. Enter your **Target Voice Channel ID** *(e.g. `123456789012345678`)*.
4. Tap **START 24/7 BOT (BACKGROUND)**.
5. A persistent status notification appears:
   ```
   VC AFK Bot 24/7 • In VC: #general (Connected)
   ```
6. **Deployment Complete**: You can now lock your device, place it in your pocket, or navigate to other apps. The service will maintain your voice channel presence uninterrupted.

---

### Method 2: Windows System Tray (PC Deployment)

If you prefer operating from a Windows desktop or workstation:

```bash
# Clone the repository
git clone https://github.com/farxru/vc-afk-selfbot.git
cd vc-afk-selfbot

# Install dependencies
npm install
```

Configure your parameters in [`config.json`](config.json):
```json
{
  "tokens": [
    "YOUR_DISCORD_USER_TOKEN_1",
    "YOUR_DISCORD_USER_TOKEN_2"
  ],
  "selfDeaf": true,
  "selfMute": false,
  "defaultStreamImage": "stream.png",
  "dashboardPort": 3000
}
```
*(You can also add, remove, and manage tokens dynamically from the Web Dashboard without editing files! Max 5 accounts supported).*

Run **[`start.bat`](start.bat)**:
* Launches silently into the **Windows System Tray** and starts the Web Dashboard on `http://localhost:3000`.
* Ultra-low RAM footprint strictly maintained under **30MB**, with automated memory garbage collection.
* Right-click the tray icon to open the Web Dashboard, tail logs, or terminate the process.
* To stop at any time, execute **[`stop.bat`](stop.bat)**.

---

## Web Dashboard & Multi-Account Management

All controls are handled through the local, zero-leak Web Dashboard (`http://localhost:3000`). Discord message commands have been retired to ensure 100% account safety, ban prevention, and silent operation.

* 👥 **Accounts Tab**: View all connected accounts, real-time online/offline status, active VC presence, and manage up to 5 tokens.
* 🏷️ **Account Nickname Selection**: Select any individual account or multi-select multiple accounts using their Discord nicknames.
* 🔊 **Synchronized Voice Actions**: Join or leave voice channels simultaneously across all selected accounts with built-in gateway anti-glitch throttling.
* 🎙️ **Voice Toggles**: Toggle Mute, Deafen, Camera, or Screenshare across selected accounts with a single click.
* 🧹 **Active RAM Cleaner**: Real-time memory gauge keeping footprint under 30MB with automatic cleanup.

---

## Security & Privacy Architecture

Security, transparency, and account isolation are foundational to this project:

```
┌────────────────────────────────────────────────────────┐
│                   YOUR ANDROID PHONE                   │
│                                                        │
│   ┌────────────────────┐      ┌────────────────────┐   │
│   │ Local Private Prefs│      │ Foreground Service │   │
│   │ (Encrypted Token)  │      │  (WakeLock Active) │   │
│   └─────────┬──────────┘      └─────────┬──────────┘   │
│             │                           │              │
│             └─────────────┬─────────────┘              │
│                           │ Direct TLS 1.3             │
│                           ▼                            │
└───────────────────────────┼────────────────────────────┘
                            │ (No Intermediate Servers)
                            ▼
              ┌───────────────────────────┐
              │    DISCORD OFFICIAL API   │
              │  (wss://gateway.discord.gg)│
              └───────────────────────────┘
```

* **No Intermediate Infrastructure**: Connections flow directly from your client to official Discord endpoints (`gateway.discord.gg` / `discord.com/api`).
* **Hardware-Level Sandboxing**: User credentials never leave Android's protected application sandbox (`/data/data/com.discord.vcbot/shared_prefs/`).
* **Strict Transport Security**: All network communications require TLS 1.3 encryption. Cleartext HTTP transmission is disabled at the manifest level.
* **Minimal Permission Profile**: The application requests only the baseline operating permissions required for sustained background connectivity (`INTERNET`, `WAKE_LOCK`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`). It requests no access to contacts, storage, camera, or microphone.

---

## Building from Source

To compile and sign the Android application yourself, refer to our detailed technical manual:

📘 **[Compilation & Build Guide (BUILD_MY_APK.md)](BUILD_MY_APK.md)**

Supports automated cloud compilation via GitHub Actions CI/CD as well as local compilation using Android Studio or Gradle CLI.

---

## License

This project is licensed under the **MIT License**. See [`LICENSE`](LICENSE) for complete terms.

<div align="center">
  <sub>Engineered for reliable, secure, and permanent 24/7 Discord uptime. Free and open source.</sub>
</div>
