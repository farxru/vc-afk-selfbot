# Discord VC Selfbot - Windows System Tray Runner
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

# Mutex to ensure single instance
$mutexName = "Global\DiscordVcSelfbotTrayMutex"
$createdNew = $false
$mutex = New-Object System.Threading.Mutex($true, $mutexName, [ref]$createdNew)
if (-not $createdNew) {
    [System.Windows.Forms.MessageBox]::Show("Discord VC Selfbot is already running in the System Tray!", "Already Running", [System.Windows.Forms.MessageBoxButtons]::OK, [System.Windows.Forms.MessageBoxIcon]::Information)
    exit
}

$global:nodeProcess = $null
$logPath = Join-Path $ScriptDir "bot.log"

function Write-TrayLog([string]$msg) {
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    "[$ts] $msg" | Out-File -FilePath $logPath -Append -Encoding utf8
}

function Start-BotProcess {
    Stop-BotProcess

    Write-TrayLog "[System Tray] Starting bot process..."

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "cmd.exe"
    $psi.Arguments = "/c node index.js >> `"$logPath`" 2>&1"
    $psi.WorkingDirectory = $ScriptDir
    $psi.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
    $psi.CreateNoWindow = $true
    $psi.UseShellExecute = $false

    try {
        $global:nodeProcess = [System.Diagnostics.Process]::Start($psi)
        if ($statusItem) {
            $statusItem.Text = "[Running] Discord VC Selfbot"
        }
        if ($notifyIcon) {
            $notifyIcon.Text = "Discord VC Selfbot (Running)"
        }
    } catch {
        Write-TrayLog "[System Tray] Failed to start node: $_"
        if ($statusItem) {
            $statusItem.Text = "[Failed] Bot failed to start"
        }
    }
}

function Stop-BotProcess {
    if ($global:nodeProcess -and -not $global:nodeProcess.HasExited) {
        Write-TrayLog "[System Tray] Stopping bot process..."
        try {
            $cmd = "taskkill /pid $($global:nodeProcess.Id) /T /F"
            cmd.exe /c $cmd | Out-Null
            $global:nodeProcess.WaitForExit(2000)
        } catch {}
    }
    try {
        Get-CimInstance Win32_Process -Filter "Name = 'node.exe'" | Where-Object { $_.CommandLine -like "*index.js*" } | ForEach-Object {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        }
    } catch {}

    if ($statusItem) {
        $statusItem.Text = "[Stopped] Discord VC Selfbot"
    }
    if ($notifyIcon) {
        $notifyIcon.Text = "Discord VC Selfbot (Stopped)"
    }
}

function Restart-BotProcess {
    Write-TrayLog "[System Tray] Restarting bot..."
    Stop-BotProcess
    Start-Sleep -Milliseconds 800
    Start-BotProcess
    $notifyIcon.ShowBalloonTip(2000, "Discord VC Selfbot", "Bot restarted successfully.", [System.Windows.Forms.ToolTipIcon]::Info)
}

function Open-LiveLogs {
    if (-not (Test-Path $logPath)) {
        "--- Discord VC Selfbot Log Initialized ---" | Out-File -FilePath $logPath -Encoding utf8
    }
    $psArgs = @("-NoExit", "-Command", "& { `$host.UI.RawUI.WindowTitle = 'Discord VC Selfbot - Live Logs'; Write-Host 'Tailing bot.log (Press Ctrl+C to close)...' -ForegroundColor Cyan; Get-Content -LiteralPath '$logPath' -Wait -Tail 40 }")
    Start-Process powershell -ArgumentList $psArgs
}

function Open-Dashboard {
    Start-Process "http://localhost:3000"
}

# Create System Tray NotifyIcon
$notifyIcon = New-Object System.Windows.Forms.NotifyIcon
$icoPath = Join-Path $ScriptDir "app.ico"
if (Test-Path $icoPath) {
    try {
        $notifyIcon.Icon = New-Object System.Drawing.Icon($icoPath)
    } catch {
        $notifyIcon.Icon = [System.Drawing.SystemIcons]::Application
    }
} else {
    $notifyIcon.Icon = [System.Drawing.SystemIcons]::Application
}
$notifyIcon.Text = "Discord VC Selfbot (Running)"
$notifyIcon.Visible = $true

# Context Menu
$contextMenu = New-Object System.Windows.Forms.ContextMenuStrip

$headerItem = New-Object System.Windows.Forms.ToolStripMenuItem
$headerItem.Text = "Discord VC Selfbot"
$headerItem.Enabled = $false
$headerFont = New-Object System.Drawing.Font($headerItem.Font.FontFamily, $headerItem.Font.Size, [System.Drawing.FontStyle]::Bold)
$headerItem.Font = $headerFont
$contextMenu.Items.Add($headerItem) | Out-Null

$statusItem = New-Object System.Windows.Forms.ToolStripMenuItem
$statusItem.Text = "[Running] Discord VC Selfbot"
$statusItem.Enabled = $false
$contextMenu.Items.Add($statusItem) | Out-Null

$contextMenu.Items.Add((New-Object System.Windows.Forms.ToolStripSeparator)) | Out-Null

$dashItem = New-Object System.Windows.Forms.ToolStripMenuItem
$dashItem.Text = "Open Mobile Dashboard"
$dashItem.Add_Click({ Open-Dashboard })
$contextMenu.Items.Add($dashItem) | Out-Null

$logsItem = New-Object System.Windows.Forms.ToolStripMenuItem
$logsItem.Text = "View Live Logs"
$logsItem.Add_Click({ Open-LiveLogs })
$contextMenu.Items.Add($logsItem) | Out-Null

$configItem = New-Object System.Windows.Forms.ToolStripMenuItem
$configItem.Text = "Edit config.json"
$configItem.Add_Click({
    $confPath = Join-Path $ScriptDir "config.json"
    Start-Process notepad.exe -ArgumentList "`"$confPath`""
})
$contextMenu.Items.Add($configItem) | Out-Null

$restartItem = New-Object System.Windows.Forms.ToolStripMenuItem
$restartItem.Text = "Restart Bot"
$restartItem.Add_Click({ Restart-BotProcess })
$contextMenu.Items.Add($restartItem) | Out-Null

$contextMenu.Items.Add((New-Object System.Windows.Forms.ToolStripSeparator)) | Out-Null

$exitItem = New-Object System.Windows.Forms.ToolStripMenuItem
$exitItem.Text = "Stop && Exit"
$exitItem.Add_Click({
    Stop-BotProcess
    $notifyIcon.Visible = $false
    $notifyIcon.Dispose()
    [System.Windows.Forms.Application]::Exit()
})
$contextMenu.Items.Add($exitItem) | Out-Null

$notifyIcon.ContextMenuStrip = $contextMenu

# Double click tray icon opens live logs
$notifyIcon.Add_DoubleClick({ Open-LiveLogs })

# Start the bot process
Start-BotProcess

# Show notification balloon
$notifyIcon.ShowBalloonTip(3000, "Discord VC Selfbot", "Bot is running in the background!`nMinimized to system tray. Right-click icon to manage.", [System.Windows.Forms.ToolTipIcon]::Info)

# Run the WinForms application loop
[System.Windows.Forms.Application]::Run()

# Cleanup on exit
Stop-BotProcess
try {
    $mutex.ReleaseMutex()
    $mutex.Dispose()
} catch {}
