@echo off
:: Discord VC Selfbot - Stop Background Bot & Tray
cd /d "%~dp0"
echo Stopping Discord VC Selfbot background process and tray icon...

powershell -NoProfile -ExecutionPolicy Bypass -Command "& { try { Get-CimInstance Win32_Process -Filter \"Name = 'powershell.exe'\" | Where-Object { $_.CommandLine -like '*tray.ps1*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue } } catch {}; try { Get-CimInstance Win32_Process -Filter \"Name = 'node.exe'\" | Where-Object { $_.CommandLine -like '*index.js*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue } } catch {}; Write-Host 'Discord VC Selfbot has been stopped successfully.' -ForegroundColor Green }"

ping 127.0.0.1 -n 2 >nul
exit
