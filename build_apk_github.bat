@echo off
:: Discord VC Bot - 1-Click Cloud APK Builder via GitHub Actions
cd /d "%~dp0"
echo ==========================================================
echo    VC AFK 24/7 Selfbot - 1-Click Cloud APK Builder
echo ==========================================================
echo.

:: Fix Windows Git SSL/TLS Schannel handshake bug
git config --global http.sslBackend openssl >nul 2>nul
git config --global http.schannelCheckRevoke false >nul 2>nul

:: Check git
where git >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Git is not installed on this PC!
    echo Please install Git from https://git-scm.com/
    pause
    exit /b 1
)

:: Initialize git if not done
if not exist ".git" (
    echo [+] Initializing local Git repository...
    git init -b main
)

:: Configure remote origin cleanly
git remote get-url origin >nul 2>nul
if %ERRORLEVEL% equ 0 (
    echo [+] Connected to repository:
    git remote get-url origin
) else (
    echo.
    echo [!] No GitHub repository connected yet.
    set /p REPO_URL="Paste your GitHub repository URL (default: https://github.com/farxru/vc-afk-selfbot.git): "
    if "%REPO_URL%"=="" set REPO_URL=https://github.com/farxru/vc-afk-selfbot.git
    git remote add origin %REPO_URL%
)

echo.
echo [+] Adding any modified files...
git add .
git commit -m "Release: VC AFK 24/7 Selfbot (Android + Node.js)" >nul 2>nul

echo.
echo [+] Pushing to GitHub (origin/main)...
git branch -M main
git push -u origin main

if %ERRORLEVEL% equ 0 (
    echo.
    echo ==========================================================
    echo [SUCCESS] Code is synced with GitHub!
    echo ==========================================================
    echo.
    echo Next step to get your APK:
    echo 1. Open your repository: https://github.com/farxru/vc-afk-selfbot/actions
    echo 2. Check the "Actions" tab.
    echo 3. When "Build Discord VC Bot APK" finishes,
    echo    download "DiscordVCBot-APK" (.apk) directly to your phone!
    echo.
) else (
    echo.
    echo [!] Push failed. Please check your GitHub token or network connection.
)

pause
