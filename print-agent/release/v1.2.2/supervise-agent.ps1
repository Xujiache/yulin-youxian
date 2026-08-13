$ErrorActionPreference = 'Stop'

$baseDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$executable = Join-Path $baseDirectory 'YulinPrintAgent.exe'
$verifier = Join-Path $baseDirectory 'verify-checksums.ps1'
$logPath = Join-Path $baseDirectory 'supervisor.log'
$maxLogBytes = 2MB
$retainedLogs = 5

function Rotate-Log {
    if (-not (Test-Path -LiteralPath $logPath)) {
        return
    }
    if ((Get-Item -LiteralPath $logPath).Length -lt $maxLogBytes) {
        return
    }

    $oldest = "$logPath.$retainedLogs"
    if (Test-Path -LiteralPath $oldest) {
        Remove-Item -LiteralPath $oldest -Force
    }
    for ($index = $retainedLogs - 1; $index -ge 1; $index--) {
        $source = "$logPath.$index"
        $destination = "$logPath.$($index + 1)"
        if (Test-Path -LiteralPath $source) {
            Move-Item -LiteralPath $source -Destination $destination -Force
        }
    }
    Move-Item -LiteralPath $logPath -Destination "$logPath.1" -Force
}

function Write-SupervisorLog([string] $Message) {
    try {
        Rotate-Log
        $line = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
        Add-Content -LiteralPath $logPath -Value $line -Encoding UTF8
    }
    catch {
        # A logging failure must not stop supervision.
    }
}

Set-Location -LiteralPath $baseDirectory
$retrySeconds = 5

while ($true) {
    $startedAt = Get-Date
    $exitCode = -1
    try {
        if (-not (Test-Path -LiteralPath $verifier)) {
            throw "verify-checksums.ps1 not found: $verifier"
        }
        & $verifier
        if (-not (Test-Path -LiteralPath $executable)) {
            throw "YulinPrintAgent.exe not found: $executable"
        }
        Write-SupervisorLog 'starting print agent'
        & $executable run
        $exitCode = $LASTEXITCODE
    }
    catch {
        Write-SupervisorLog "failed to start or supervise agent: $($_.Exception.Message)"
    }

    $runSeconds = [int]((Get-Date) - $startedAt).TotalSeconds
    $currentDelay = $retrySeconds
    Write-SupervisorLog "agent exited with code $exitCode after ${runSeconds}s; retrying in ${currentDelay}s"
    Start-Sleep -Seconds $currentDelay

    if ($runSeconds -ge 300) {
        $retrySeconds = 5
    }
    else {
        $retrySeconds = [Math]::Min(60, [Math]::Max(5, $retrySeconds * 2))
    }
}
