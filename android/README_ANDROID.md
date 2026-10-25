# 📱 Running Discord VC Selfbot 24/7 on Android Phone

This guide explains how to install and run your Discord VC Selfbot on your Android phone in the **background 24/7**, even when your phone screen is off and locked.

---

## 📦 What's Included

- **`termux.apk`** *(and `termux-arm64.apk`)*: The official Termux APK pre-downloaded in this folder, ready to install on your phone.
- **`start-bot.sh`**: The background runner script that acquires Android wake-lock (so Android never sleeps), starts Node.js, and auto-restarts if Wi-Fi drops.
- **`setup.sh`**: Automated setup script that installs Node.js and dependencies in one go.

---

## 🚀 Quick Setup (3 Steps)

### Step 1: Install the APK on your Phone
1. Transfer **[`android/termux.apk`](file:///d:/download%20of%20chrome/x/android/termux.apk)** to your phone (via USB cable, Google Drive, WhatsApp, or Quick Share).
2. On your phone, tap `termux.apk` and tap **Install** *(allow "Install from Unknown Sources" if prompted)*.
3. Also transfer this entire project folder (`x`) to your phone's storage (for example, into your phone's `Download` folder).

---

### Step 2: Allow Background Running (Crucial for 24/7 Uptime)
Android will kill any background app after 5–10 minutes to save battery unless you give it permission:

1. Open your phone's **Settings** ⚙️.
2. Go to **Apps** ➔ **Termux** ➔ **Battery** (or "App Battery Usage").
3. Set it to **"Unrestricted"** (or **"Don't optimize"** / **"No restrictions"**).
4. *(Samsung users)*: Settings ➔ Battery ➔ Background usage limits ➔ Add Termux to **"Never sleeping apps"**.
5. *(Xiaomi / Redmi / POCO users)*: App info ➔ Enable **"Autostart"** + Battery saver: **"No restrictions"**.

---

### Step 3: Run the Bot
1. Open the **Termux** app on your phone.
2. Grant storage permission so Termux can read your files:
   ```bash
   termux-setup-storage
   ```
   *(Tap "Allow" on the Android permission popup)*.

3. Navigate to where you copied the folder (usually in Download):
   ```bash
   cd /sdcard/Download/x
   ```
   *(If you placed it elsewhere, adjust the path, e.g. `cd /sdcard/x`)*.

4. Run the startup script:
   ```bash
   bash android/start-bot.sh
   ```

5. **That's it!**
   - Termux will automatically install Node.js and dependencies if needed.
   - It will lock the CPU awake with `termux-wake-lock` (you will see a persistent Termux notification in your status bar).
   - The bot will log in and stay in VC 24/7, even when you lock your phone or turn off the screen!

---

## 🛑 How to Stop or Manage

- **To view bot status**: Just pull down your Android notification shade or open Termux.
- **To stop the bot**: In Termux, press `Ctrl + C`.
- **To release the background lock**: Run `termux-wake-unlock`.
- **To restart**: Run `bash android/start-bot.sh` again.

---

## ⚡ Optional: Run Automatically When Phone Reboots
If you want the bot to automatically start every time you restart your phone:
1. Download **Termux:Boot APK** from F-Droid.
2. Inside Termux:
   ```bash
   mkdir -p ~/.termux/boot
   echo "cd /sdcard/Download/x && bash android/start-bot.sh" > ~/.termux/boot/start-discord-bot.sh
   chmod +x ~/.termux/boot/start-discord-bot.sh
   ```
Now whenever your phone boots up, your Discord VC bot starts instantly in the background!
