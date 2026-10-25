# Building & Compiling Discord VC Bot (Android)

Comprehensive guide for compiling and signing the native Android application (`DiscordVCBot.apk`), covering automated cloud CI/CD, Android Studio, and standalone Gradle builds.

---

## Table of Contents
1. [Technical Specifications](#technical-specifications)
2. [Method 1: Automated Cloud Build via GitHub Actions (Recommended)](#method-1-automated-cloud-build-via-github-actions-recommended)
3. [Method 2: Local Compilation via Android Studio](#method-2-local-compilation-via-android-studio)
4. [Method 3: Headless CLI Compilation via Gradle](#method-3-headless-cli-compilation-via-gradle)
5. [Cryptographic Signing & Integrity](#cryptographic-signing--integrity)
6. [Android Device Deployment (24/7 Setup)](#android-device-deployment-247-setup)
7. [Troubleshooting & Verification](#troubleshooting--verification)

---

## Technical Specifications

| Property | Value |
|---|---|
| **Application ID** | `com.discord.vcbot` |
| **Minimum SDK** | Android 8.0 (API Level 26) |
| **Target SDK** | Android 14 (API Level 34) |
| **Language** | Java 17 |
| **Build System** | Gradle 8.2.0 / Android Gradle Plugin 8.2.0 |
| **Core Libraries** | OkHttp 4.12.0 (WebSocket), Google Gson 2.10.1 |
| **Background Engine** | Android Foreground Service (`START_STICKY`) + Partial WakeLock |
| **Signing Schema** | APK Signature Scheme v1 (JAR) & v2 (Full APK) |

---

## Method 1: Automated Cloud Build via GitHub Actions (Recommended)

The repository includes a production-ready GitHub Actions workflow (`.github/workflows/build-apk.yml`) that builds, signs, and packages the release APK inside an isolated Ubuntu runner with zero local setup.

### Workflow Execution

1. **Push to Your Repository**:
   Ensure your local changes are pushed to your GitHub repository:
   ```bash
   git add .
   git commit -m "feat: trigger release build"
   git push origin main
   ```
   *(Alternatively, run the automated script [`build_apk_github.bat`](build_apk_github.bat)).*

2. **Monitor Build Progress**:
   * Navigate to the **Actions** tab on your GitHub repository:  
     `https://github.com/<your-username>/vc-afk-selfbot/actions`
   * Select the running workflow: **Build Discord VC Bot APK**.
   * Compilation typically completes in under 90 seconds.

3. **Retrieve the Artifact**:
   * Once completed, go to **Releases** (`https://github.com/<your-username>/vc-afk-selfbot/releases/latest`).
   * Download **`DiscordVCBot.apk`**.
   * The signed APK is also available under the workflow run's **Artifacts** section as `DiscordVCBot-Release-APK`.

---

## Method 2: Local Compilation via Android Studio

For active development, debugging, or UI customization:

### Prerequisites
* [Android Studio Hedgehog (2023.1.1)](https://developer.android.com/studio) or newer.
* Android SDK Platform 34 and Build-Tools 34.0.0.
* JDK 17 (bundled with modern Android Studio).

### Step-by-Step

1. Launch Android Studio and select **File ➔ Open**.
2. Select the `android-app` subfolder in this repository:
   ```
   d:/download of chrome/x/android-app/
   ```
3. Allow Gradle to sync dependencies and index the project.
4. To build a debug APK:
   * Select **Build ➔ Build Bundle(s) / APK(s) ➔ Build APK(s)**.
   * Artifact output: `android-app/app/build/outputs/apk/debug/app-debug.apk`.
5. To build a signed release APK:
   * Select **Build ➔ Generate Signed Bundle / APK**.
   * Choose **APK**, select or generate your keystore, and select **Release** with both **V1** and **V2** signatures checked.
   * Artifact output: `android-app/app/build/outputs/apk/release/app-release.apk`.

---

## Method 3: Headless CLI Compilation via Gradle

If building in CI/CD environments or via terminal without Android Studio:

### Prerequisites
* Java Development Kit (JDK) 17 installed and `JAVA_HOME` configured.
* Android SDK command-line tools installed with `ANDROID_HOME` configured.

### Commands

1. Navigate to the Android project root:
   ```bash
   cd android-app
   ```

2. Compile the debug APK:
   * **Linux / macOS**:
     ```bash
     chmod +x gradlew
     ./gradlew assembleDebug
     ```
   * **Windows**:
     ```powershell
     .\gradlew.bat assembleDebug
     ```

3. Compile the release APK:
   ```powershell
   .\gradlew.bat assembleRelease
   ```
   The compiled APK will be generated at:
   `android-app/app/build/outputs/apk/release/app-release-unsigned.apk` (or signed if configured).

---

## Cryptographic Signing & Integrity

To guarantee system integrity, verify origin, and ensure smooth installation through Google Play Protect, the APK must be signed with valid cryptographic keys.

### Manual Keystore Generation
If signing manually, generate a 2048-bit RSA key:
```bash
keytool -genkey -v -keystore release.keystore -alias vcbot -keyalg RSA -keysize 2048 -validity 10000 -storepass vcbotpassword -keypass vcbotpassword -dname "CN=VCBot, OU=Mobile, O=OpenSource, L=Global, S=Global, C=US"
```

### Signing with `apksigner`
```bash
zipalign -v -p 4 app-release-unsigned.apk app-release-aligned.apk
apksigner sign --ks release.keystore --ks-key-alias vcbot --ks-pass pass:vcbotpassword --key-pass pass:vcbotpassword --out DiscordVCBot.apk app-release-aligned.apk
```

### Verification
Verify signatures with:
```bash
apksigner verify --verbose DiscordVCBot.apk
```
Expected output:
```
Verifies
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): false
```

---

## Android Device Deployment (24/7 Setup)

Once you have `DiscordVCBot.apk`:

### 1. Installation
* Sideload the APK onto your device (via USB, cloud download, or direct browser download).
* Open the downloaded file and select **Install**.
* If prompted, grant permission to *Install from Unknown Sources* for your file manager or browser.

### 2. Critical Battery Configuration
Modern Android distributions employ aggressive background process management. To ensure continuous 24/7 operation without process suspension:

> [!IMPORTANT]
> **Disable Battery Optimization**:
> 1. Open device **Settings ⚙️ ➔ Apps ➔ VC AFK Bot**.
> 2. Select **Battery** (or *App Battery Usage*).
> 3. Switch from *Optimized* to **Unrestricted** (or *Don't optimize* / *No restrictions*).

* **Samsung One UI**: **Settings ➔ Battery ➔ Background usage limits ➔ Never sleeping apps** ➔ Add **VC AFK Bot**.
* **Xiaomi / HyperOS / MIUI**: App Info ➔ Enable **Autostart** ➔ Set Battery Saver to **No restrictions**.
* **OnePlus / OxygenOS**: App Info ➔ Battery ➔ Allow background activity + Allow auto-launch.

### 3. Service Activation
1. Launch **VC AFK Bot**.
2. Input your Discord User Token, User ID, and target Voice Channel ID.
3. Tap **START 24/7 BOT (BACKGROUND)**.
4. A persistent notification titled **VC AFK Bot 24/7** will lock into the notification tray, confirming active background persistence.

---

## Troubleshooting & Verification

### Build Fails with `JAVA_HOME is not set`
Ensure JDK 17 is installed:
```powershell
java -version
```
Set the environment variable:
```powershell
[System.Environment]::SetEnvironmentVariable('JAVA_HOME', 'C:\Program Files\Java\jdk-17', 'Machine')
```

### Google Play Protect Warning During Install
Play Protect periodically flags sideloaded APKs created with newly generated certificates:
1. Tap **More details**.
2. Select **Install anyway**.
3. *Note*: The application contains zero trackers, analytics, or native binaries. All source code is fully transparent in the `android-app/` directory.

### Disconnections on Network Switch
The background service includes an integrated 15-second heartbeat watchdog and network listener. If switching from Wi-Fi to cellular data, reconnection triggers automatically within 1 to 3 seconds.
