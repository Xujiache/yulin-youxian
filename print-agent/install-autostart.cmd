@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$p='%~dp0YulinPrintAgent.exe'; $a=New-ScheduledTaskAction -Execute $p; $t=New-ScheduledTaskTrigger -AtLogOn; Register-ScheduledTask -TaskName 'YulinYouxianPrintAgent' -Action $a -Trigger $t -Force | Out-Null"
if errorlevel 1 exit /b 1
echo Print agent autostart is enabled.
