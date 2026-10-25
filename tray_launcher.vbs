' Silent launcher for Discord VC Selfbot System Tray
Set objFSO = CreateObject("Scripting.FileSystemObject")
strDir = objFSO.GetParentFolderName(WScript.ScriptFullName)
strPS1 = objFSO.BuildPath(strDir, "tray.ps1")

Set objShell = CreateObject("WScript.Shell")
strCommand = "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File """ & strPS1 & """"
objShell.Run strCommand, 0, False
