@echo off
:: VC AFK 24/7 Selfbot - Automated Cloud APK Builder via GitHub Actions
cd /d "%~dp0"
echo ==========================================================
echo    VC AFK 24/7 Selfbot - Automated Cloud APK Builder
echo ==========================================================
echo.

:: Fix Windows Git SSL/TLS Schannel handshake bug
git config --global http.sslBackend openssl >nul 2>nul
git config --global http.schannelCheckRevoke false >nul 2>nul

:: Check git
where git >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Git is not installed or not in PATH!
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
    echo [+] Target repository:
    git remote get-url origin
) else (
    echo.
    echo [!] No remote origin configured.
    set /p REPO_URL="Enter GitHub repository URL (default: https://github.com/farxru/vc-afk-selfbot.git): "
    if "%REPO_URL%"=="" set REPO_URL=https://github.com/farxru/vc-afk-selfbot.git
    git remote add origin %REPO_URL%
)

echo.
echo [+] Staging and committing changes...
git add .
git commit -m "build: trigger automated release compilation" >nul 2>nul

echo.
echo [+] Pushing commit to GitHub (origin/main)...
git branch -M main
git push -u origin main

if %ERRORLEVEL% equ 0 (
    echo.
    echo ==========================================================
    echo [SUCCESS] Source code synchronized successfully!
    echo ==========================================================
    echo.
    echo The GitHub Actions cloud runner is compiling your APK:
    echo 1. Open Actions: https://github.com/farxru/vc-afk-selfbot/actions
    echo 2. When the build completes (~60s), your signed release is ready:
    echo    Direct Download: https://github.com/farxru/vc-afk-selfbot/releases/latest
    echo.
) else (
    echo.
    echo [!] Push failed. Verify your network connection and GitHub credentials.
)

pause
