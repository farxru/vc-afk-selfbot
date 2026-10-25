@echo off
:: Discord VC Selfbot - Start in Background & Minimize to System Tray
cd /d "%~dp0"

:: Check if Node is installed
where node >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Node.js is not found in PATH!
    echo Please install Node.js from https://nodejs.org/
    pause
    exit /b 1
)

:: Check if config.json exists
if not exist "%~dp0config.json" (
    echo [ERROR] config.json was not found!
    pause
    exit /b 1
)

:: Launch silently in background minimizing to system tray
if exist "%~dp0tray_launcher.vbs" (
    start "" wscript.exe "%~dp0tray_launcher.vbs"
) else (
    start "" powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0tray.ps1"
)

exit
