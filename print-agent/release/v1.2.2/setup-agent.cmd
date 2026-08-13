@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0verify-checksums.ps1"
if errorlevel 1 (
  echo Release verification failed. Setup was not started.
  pause
  exit /b 1
)
YulinPrintAgent.exe setup
pause
