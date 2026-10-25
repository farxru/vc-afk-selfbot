# Headless Termux Runner &middot; Android CLI Deployment

This guide documents the headless CLI runner for users who prefer executing the Node.js daemon directly via the Termux Android environment rather than the native APK.

> [!TIP]
> **Recommended Approach**: For standard mobile usage, install the native Android application (`DiscordVCBot.apk`). The native app provides background foreground service guarantees, lower memory usage (< 15MB), and requires zero terminal commands. See the [main README](../README.md).

---

## Prerequisites
1. **Termux Environment**: Installed on Android 8.0 or newer (download via F-Droid or GitHub releases).
2. **Storage Permissions**: Access granted to read configuration files.
3. **Android Wake Lock**: Enabled to prevent CPU throttling when the screen turns off.

---

## Setup & Installation

### Step 1: Battery Exemption
To maintain 24/7 background uptime in Termux:
1. Open device **Settings ⚙️ ➔ Apps ➔ Termux ➔ Battery**.
2. Select **Unrestricted** (or *Don't optimize*).
3. On Samsung devices, add Termux to **Never sleeping apps**.
4. On Xiaomi / Redmi devices, enable **Autostart** and set battery saver to **No restrictions**.

### Step 2: Environment Initialization
Launch Termux and execute:
```bash
# Grant storage access
termux-setup-storage

# Update repository mirrors and install required packages
pkg update -y && pkg install -y nodejs git

# Navigate to the workspace directory
cd /sdcard/Download/x

# Install project dependencies
npm install --omit=dev
```

### Step 3: Launch with Persistent WakeLock
Run the automated initialization script:
```bash
bash android/start-bot.sh
```

The script acquires an Android `PARTIAL_WAKE_LOCK` via `termux-wake-lock`, launches the Node.js process with auto-restart on network interruptions, and directs output to the console.

---

## Process Management

* **Inspect Logs**: Run `tail -f bot.log` inside the project folder.
* **Stop Instance**: Press `Ctrl + C` inside the active Termux session.
* **Release CPU WakeLock**: Execute `termux-wake-unlock`.
