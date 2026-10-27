$ErrorActionPreference = 'Stop'
$taskName = 'YulinYouxianPrintAgent'

$task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
if ($null -eq $task) {
    Write-Host 'Print agent autostart is not installed.'
    exit 0
}

Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
Write-Host 'Print agent autostart was removed.'
