# Discord VC Bot &middot; Android Native Client

Native Android client module for the **VC AFK Selfbot**, implementing persistent Discord Gateway WebSocket connectivity via an Android Foreground Service.

---

## Architectural Overview

The Android application is designed specifically for battery-efficient, autonomous background execution on mobile hardware:

* **`DiscordVoiceService`**: Manages the application lifecycle as an Android Foreground Service (`START_STICKY`) backed by a CPU `PARTIAL_WAKE_LOCK`. This maintains network activity while allowing the display and GPU to fully power down.
* **`DiscordGatewayClient`**: Implements Discord Gateway v9 protocol over TLS 1.3 using `OkHttp3`. Handles Opcode 2 (Identify), Opcode 1 (Heartbeat), Opcode 4 (Voice State Update), Opcode 18 (Stream Create), and Opcode 19 (Stream Set Paused).
* **Self-Healing State Watcher**: Evaluates connection state every 15 seconds. If an admin disconnects the bot or if voice state changes, the client reasserts the designated target voice channel within 1,000 milliseconds.
* **Console Streaming Buffer**: Maintains an in-memory rolling log buffer (300 lines) that synchronizes dynamically with `MainActivity` whenever the UI is opened.

---

## Technical Specifications

| Component | Target / Value |
|---|---|
| **Package ID** | `com.discord.vcbot` |
| **Minimum Android Version** | Android 8.0 Oreo (API Level 26) |
| **Target Android Version** | Android 14 Upside Down Cake (API Level 34) |
| **Java Compliance** | Java 17 |
| **Networking Stack** | OkHttp 4.12.0 / WebSocket |
| **Serialization** | Google Gson 2.10.1 |
| **Android Permissions** | `INTERNET`, `WAKE_LOCK`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS` |

---

## Project Structure

```
android-app/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/discord/vcbot/
│   │       │   ├── MainActivity.java           # Primary configuration & UI controller
│   │       │   ├── DiscordVoiceService.java    # Background foreground service & notification manager
│   │       │   └── DiscordGatewayClient.java   # Discord WebSocket gateway implementation
│   │       ├── res/
│   │       │   ├── layout/activity_main.xml    # Discord Dark Theme user interface
│   │       │   ├── mipmap-*/ic_launcher.png    # High-resolution application launcher assets
│   │       │   └── values/                     # Colors, themes, and string definitions
│   │       └── AndroidManifest.xml             # Security-hardened permissions and service definitions
│   └── build.gradle                            # App-level build and signing configurations
├── build.gradle                                # Root project dependencies
└── settings.gradle                             # Project module bindings
```

---

## Building Locally

### Using Android Studio
1. Launch Android Studio.
2. Select **Open**, and navigate to this `android-app` directory.
3. Allow Gradle to synchronize dependencies.
4. Select **Build ➔ Build Bundle(s) / APK(s) ➔ Build APK(s)** to generate `app-debug.apk`.

### Using Command Line
```powershell
# From the android-app directory
.\gradlew.bat assembleRelease
```

---

## Security Compliance
* **Encrypted Sandbox**: Account tokens are stored locally inside `SharedPreferences` with `MODE_PRIVATE`.
* **Zero Backup**: `android:allowBackup="false"` prevents extraction via ADB backup.
* **No Cleartext Traffic**: `android:usesCleartextTraffic="false"` strictly enforces HTTPS and WSS protocols.
