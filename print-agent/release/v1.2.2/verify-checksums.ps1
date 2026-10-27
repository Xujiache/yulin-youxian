$ErrorActionPreference = 'Stop'

$baseDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$manifestPath = Join-Path $baseDirectory 'SHA256SUMS.txt'
if (-not (Test-Path -LiteralPath $manifestPath)) {
    throw "SHA256SUMS.txt not found: $manifestPath"
}

$checked = 0
$manifestNames = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
foreach ($line in [System.IO.File]::ReadAllLines($manifestPath, [System.Text.Encoding]::UTF8)) {
    if ([string]::IsNullOrWhiteSpace($line)) {
        continue
    }
    if ($line -notmatch '^([0-9a-fA-F]{64}) \*(.+)$') {
        throw "Invalid checksum line: $line"
    }

    $expected = $Matches[1].ToLowerInvariant()
    $name = $Matches[2]
    if ($name.Contains('\') -or $name.Contains('/') -or $name.Contains('..')) {
        throw "Unsafe checksum path: $name"
    }
    if ($name -ieq 'SHA256SUMS.txt') {
        throw 'SHA256SUMS.txt must not attest itself.'
    }
    if (-not $manifestNames.Add($name)) {
        throw "Duplicate checksum entry: $name"
    }

    $path = Join-Path $baseDirectory $name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing release file: $name"
    }
    $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expected) {
        throw "Checksum mismatch: $name"
    }
    $checked++
}

if ($checked -eq 0) {
    throw 'Checksum manifest contains no files.'
}

foreach ($requiredName in @('YulinPrintAgent.exe', 'printer.sdk.dll', 'VERSION.txt', 'BUILD-TOOLCHAIN.txt')) {
    if (-not $manifestNames.Contains($requiredName)) {
        throw "Checksum manifest is missing required file: $requiredName"
    }
}

$executableExtensions = @('.exe', '.dll', '.ps1', '.psm1', '.cmd', '.bat', '.com', '.scr', '.vbs', '.js', '.config', '.lnk')
Get-ChildItem -LiteralPath $baseDirectory -File |
    Where-Object {
        $_.Name -ne 'SHA256SUMS.txt' -and
        $_.Extension.ToLowerInvariant() -in $executableExtensions -and
        -not $manifestNames.Contains($_.Name)
    } |
    ForEach-Object {
        throw "Unlisted executable or configuration file found: $($_.Name)"
    }

Write-Host "SHA256 verification passed for $checked files."
