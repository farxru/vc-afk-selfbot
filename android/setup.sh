#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# One-Step Setup Script for Android Phone
# Run this inside Termux to set up everything automatically!
# ==============================================================================

echo "=================================================="
echo " Setting up Discord VC Selfbot on Android (Termux)"
echo "=================================================="

# 1. Acquire wake lock immediately so screen off does not freeze installation
if command -v termux-wake-lock >/dev/null 2>&1; then
    echo "[+] Enabling Termux Wake Lock..."
    termux-wake-lock
fi

# 2. Install Node.js without interactive prompts hanging
if ! command -v node >/dev/null 2>&1; then
    echo "[1/3] Installing Node.js..."
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -y
    apt-get install -y -o Dpkg::Options::="--force-confold" nodejs-lts git
else
    echo "[1/3] Node.js is already installed ($(node -v)). Skipping apt update."
fi

# 3. Request storage permission if needed to access /sdcard/
if [ ! -d "$HOME/storage" ]; then
    echo "[2/3] Requesting storage access (please accept prompt if shown)..."
    termux-setup-storage >/dev/null 2>&1
fi

# 4. Navigate to project directory
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR" || exit 1

# 5. Fast npm install
if [ ! -d "node_modules" ]; then
    echo "[3/3] Installing dependencies..."
    npm install --no-audit --no-fund
else
    echo "[3/3] Dependencies already present. Ready!"
fi

echo "=================================================="
echo " Setup complete! Starting the bot now..."
echo "=================================================="

bash android/start-bot.sh
