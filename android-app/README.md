# 📱 Discord VC Bot - Native Android App

A native Android mobile application with a Discord Dark Theme GUI, Foreground Service, WakeLock, and direct Discord Gateway WebSocket client to keep your Discord account inside a Voice Channel 24/7 in the background.

---

## ✨ Features

- **Native Mobile UI**: No terminal commands! Tap buttons to Join VC, Leave VC, Mute, Deafen, Camera, and Screenshare.
- **24/7 Background Running**: Powered by an Android Foreground Service (`START_STICKY`) + `PARTIAL_WAKE_LOCK`. It stays connected even when your phone is locked or screen is off.
- **Persistent Status Bar Notification**: Controls right from your phone's notification drawer.
- **Live Console**: Live log output of Discord Gateway heartbeats, packets, and commands.
- **Auto-Reconnect**: Automatically reconnects if your phone switches between Wi-Fi and mobile data.

---

## 🛠️ How to Build the APK

### Method 1: Free 1-Click Cloud Build (No Android Studio Needed!)
1. Push this folder to a GitHub repository (public or private).
2. Go to the **Actions** tab on your GitHub repository.
3. The workflow **"Build Discord VC Bot APK"** will automatically compile the APK!
4. Download the `DiscordVCBot-Debug-APK` artifact and install `app-debug.apk` directly on your phone!

### Method 2: Android Studio (Local Build)
1. Open [**Android Studio**](https://developer.android.com/studio).
2. Click **Open** and select the `android-app` folder.
3. Let Gradle sync.
4. Click **Build** ➔ **Build Bundle(s) / APK(s)** ➔ **Build APK(s)**.
5. Android Studio will output the `.apk` file ready to transfer to your phone!
