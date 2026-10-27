param(
    [string] $Version = '1.2.1',
    [string] $OutputDirectory
)

$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'src\Program.cs'
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $PSScriptRoot "release\v$Version"
}
$release = [System.IO.Path]::GetFullPath($OutputDirectory)
$compiler = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe'

if (-not (Test-Path -LiteralPath $compiler)) {
    throw "C# compiler not found: $compiler"
}
if (-not (Select-String -LiteralPath $source -SimpleMatch "private const string Version = `"$Version`";" -Quiet)) {
    throw "Build version $Version does not match Program.cs"
}
if ((Test-Path -LiteralPath $release) -and (Get-ChildItem -LiteralPath $release -Force | Select-Object -First 1)) {
    throw "Output directory is not empty and will not be overwritten: $release"
}

New-Item -ItemType Directory -Force -Path $release | Out-Null
& $compiler `
    /nologo `
    /target:exe `
    /platform:x64 `
    /optimize+ `
    "/out:$release\YulinPrintAgent.exe" `
    /reference:System.Web.Extensions.dll `
    /reference:System.Security.dll `
    "$source"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$PSScriptRoot\normalize-binary.ps1" `
    -ExecutablePath "$release\YulinPrintAgent.exe" `
    -SourcePath $source

Copy-Item -LiteralPath "$PSScriptRoot\vendor\x64\printer.sdk.dll" -Destination "$release\printer.sdk.dll" -Force
Copy-Item -LiteralPath "$PSScriptRoot\run-agent.cmd" -Destination "$release\run-agent.cmd" -Force
Copy-Item -LiteralPath "$PSScriptRoot\setup-agent.cmd" -Destination "$release\setup-agent.cmd" -Force
Copy-Item -LiteralPath "$PSScriptRoot\install-autostart.cmd" -Destination "$release\install-autostart.cmd" -Force
Copy-Item -LiteralPath "$PSScriptRoot\uninstall-autostart.cmd" -Destination "$release\uninstall-autostart.cmd" -Force
Copy-Item -LiteralPath "$PSScriptRoot\supervise-agent.ps1" -Destination "$release\supervise-agent.ps1" -Force
Copy-Item -LiteralPath "$PSScriptRoot\install-autostart.ps1" -Destination "$release\install-autostart.ps1" -Force
Copy-Item -LiteralPath "$PSScriptRoot\uninstall-autostart.ps1" -Destination "$release\uninstall-autostart.ps1" -Force
Copy-Item -LiteralPath "$PSScriptRoot\verify-checksums.ps1" -Destination "$release\verify-checksums.ps1" -Force
Get-ChildItem -LiteralPath $PSScriptRoot -File |
    Where-Object { $_.Extension -in @('.md', '.txt') } |
    ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $release $_.Name) -Force
    }

[System.IO.File]::WriteAllText(
    (Join-Path $release 'VERSION.txt'),
    "$Version`r`n",
    (New-Object System.Text.UTF8Encoding($false))
)

$manifestPath = Join-Path $release 'SHA256SUMS.txt'
$manifestLines = Get-ChildItem -LiteralPath $release -File |
    Where-Object { $_.Name -ne 'SHA256SUMS.txt' } |
    Sort-Object -Property Name |
    ForEach-Object {
        $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash *$($_.Name)"
    }
[System.IO.File]::WriteAllLines(
    $manifestPath,
    [string[]] $manifestLines,
    (New-Object System.Text.UTF8Encoding($false))
)

Write-Host "Print agent $Version built at $release"
Write-Host "SHA256 manifest: $manifestPath"
