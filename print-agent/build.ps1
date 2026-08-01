$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$source = Join-Path $PSScriptRoot 'src\Program.cs'
$release = Join-Path $PSScriptRoot 'release'
$compiler = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe'
New-Item -ItemType Directory -Force -Path $release | Out-Null
& $compiler /nologo /target:exe /platform:x64 /optimize+ "/out:$release\YulinPrintAgent.exe" /reference:System.Web.Extensions.dll "$source"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Copy-Item -LiteralPath "$PSScriptRoot\vendor\x64\printer.sdk.dll" -Destination "$release\printer.sdk.dll" -Force
Copy-Item -LiteralPath "$PSScriptRoot\run-agent.cmd" -Destination "$release\run-agent.cmd" -Force
Copy-Item -LiteralPath "$PSScriptRoot\setup-agent.cmd" -Destination "$release\setup-agent.cmd" -Force
Copy-Item -LiteralPath "$PSScriptRoot\install-autostart.cmd" -Destination "$release\install-autostart.cmd" -Force
Copy-Item -LiteralPath "$PSScriptRoot\uninstall-autostart.cmd" -Destination "$release\uninstall-autostart.cmd" -Force
