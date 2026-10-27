$ErrorActionPreference = 'Stop'

$taskName = 'YulinYouxianPrintAgent'
$baseDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$supervisor = Join-Path $baseDirectory 'supervise-agent.ps1'
$powerShell = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
$currentUser = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name

if (-not (Test-Path -LiteralPath $supervisor)) {
    throw "supervise-agent.ps1 not found: $supervisor"
}

$arguments = '-NoProfile -ExecutionPolicy Bypass -File "{0}"' -f $supervisor
$action = New-ScheduledTaskAction `
    -Execute $powerShell `
    -Argument $arguments `
    -WorkingDirectory $baseDirectory
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $currentUser
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -MultipleInstances IgnoreNew `
    -RestartCount 999 `
    -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit ([TimeSpan]::Zero)
$principal = New-ScheduledTaskPrincipal `
    -UserId $currentUser `
    -LogonType Interactive `
    -RunLevel Limited

Register-ScheduledTask `
    -TaskName $taskName `
    -Action $action `
    -Trigger $trigger `
    -Settings $settings `
    -Principal $principal `
    -Force | Out-Null
Start-ScheduledTask -TaskName $taskName

Write-Host "Print agent autostart installed for $currentUser."
Write-Host 'The same Windows user is required because the access key is protected with DPAPI.'
