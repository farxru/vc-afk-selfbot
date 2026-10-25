#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# Discord VC Selfbot - Android 24/7 Background Runner
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$SCRIPT_DIR" || exit 1

echo "=================================================="
echo "  Discord VC Selfbot - Android 24/7 Runner"
echo "=================================================="

# 1. Acquire Android Wake Lock (Prevents Android from killing bot in sleep mode)
if command -v termux-wake-lock >/dev/null 2>&1; then
    echo "[+] Acquiring Termux Wake Lock (Background Mode)..."
    termux-wake-lock
fi

# 2. Check if Node.js is installed
if ! command -v node >/dev/null 2>&1; then
    echo "[!] Node.js not detected. Installing nodejs-lts..."
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -y
    apt-get install -y -o Dpkg::Options::="--force-confold" nodejs-lts
fi

# 3. Check dependencies
if [ ! -d "node_modules" ]; then
    echo "[+] Installing bot dependencies..."
    npm install --no-audit --no-fund
fi

# 4. Check configuration
if [ ! -f "config.json" ]; then
    echo "[ERROR] config.json not found in $SCRIPT_DIR!"
    exit 1
fi

echo "[+] Starting Discord VC Selfbot..."
echo "[i] Bot will run 24/7 in background. Press Ctrl+C to stop."
echo "=================================================="

# 5. Continuous 24/7 loop with auto-restart on network drop
while true; do
    node index.js
    EXIT_CODE=$?
    if [ $EXIT_CODE -eq 0 ]; then
        echo "[i] Bot exited cleanly."
        break
    fi
    echo "[!] Bot disconnected (code $EXIT_CODE). Auto-reconnecting in 3 seconds..."
    sleep 3
done
