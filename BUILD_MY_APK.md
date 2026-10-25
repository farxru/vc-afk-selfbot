# 📱 1-Click Automated Cloud APK Builder

Compile your own signed **`DiscordVCBot.apk`** for **FREE** in the cloud in ~60 seconds using GitHub Actions.

---

## ⚡ Step-by-Step Instructions

### Step 1: Create or Fork the Repository
1. Go to [https://github.com/new](https://github.com/new) and log in.
2. Enter repository name: **`vc-afk-selfbot`**.
3. Leave checkboxes unchecked and click **Create repository**.
4. Copy your repository URL (e.g. `https://github.com/farxru/vc-afk-selfbot.git`).

---

### Step 2: Run the Automated Pusher
1. In this project folder, double-click **[`build_apk_github.bat`](build_apk_github.bat)**.
2. When prompted, press **Enter** (it defaults to `https://github.com/farxru/vc-afk-selfbot.git`).
3. The script commits your changes and pushes to GitHub.

---

### Step 3: Download the Compiled APK
1. Open your repository on GitHub:
   👉 **[https://github.com/farxru/vc-afk-selfbot/actions](https://github.com/farxru/vc-afk-selfbot/actions)**
2. Click the **"Actions"** tab.
3. You will see **"Build Discord VC Bot APK"** workflow running.
4. When finished (green checkmark ✅ in ~60s), click on the workflow run.
5. Under **Artifacts**, download **`DiscordVCBot-APK`** (or go to the **Releases** tab to download `DiscordVCBot.apk`).

---

## 📲 How to Run 24/7 on Your Phone (Phone Hosting)

1. **Install the APK**:
   * Tap `DiscordVCBot.apk` on your phone and tap **Install** *(allow "Install from Unknown Sources" if prompted)*.

2. **Disable Android Battery Restrictions** *(Crucial for 24/7 phone hosting)*:
   * **Settings** ⚙️ ➔ **Apps** ➔ **VC AFK Bot** ➔ **Battery** ➔ Set to **"Unrestricted"** (or *"Don't optimize"* / *"No restrictions"*).
   * This ensures Android never sleeps or kills your bot while hosting in the background.

3. **Start Hosting the Bot**:
   * Open **VC AFK Bot** on your phone.
   * Paste your Discord User Token and Discord User ID *(saved privately on your phone)*.
   * Enter your target Voice Channel ID.
   * Tap **"START 24/7 BOT (BACKGROUND)"**.
   * A persistent foreground notification appears in your status bar:
     > **VC AFK Bot 24/7** &middot; *🟢 In VC: #channel-name*
   * **Your phone is now hosting the bot 24/7!** You can lock your phone screen, put it in your pocket, or use other apps, and your bot will stay in the voice channel continuously.
