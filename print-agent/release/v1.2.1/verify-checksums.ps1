$ErrorActionPreference = 'Stop'

$baseDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$manifestPath = Join-Path $baseDirectory 'SHA256SUMS.txt'
if (-not (Test-Path -LiteralPath $manifestPath)) {
    throw "SHA256SUMS.txt not found: $manifestPath"
}

$checked = 0
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
Write-Host "SHA256 verification passed for $checked files."
