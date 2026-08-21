# 骑手端 Windows 应急出包：跑测试 + 打 release + 校验签名 + 输出产物路径。
# 这不是正式 OTA 入口。生产发布用 Linux 本机 scripts/rider-publish.sh。
#
#   pwsh scripts/rider-ship.ps1              # 自动挑当日序号
#   pwsh scripts/rider-ship.ps1 -Sequence 7  # 指定序号
#   pwsh scripts/rider-ship.ps1 -Arm64Only   # 只打 arm64，体积减到约 70MB 好走微信
#   pwsh scripts/rider-ship.ps1 -SkipTests   # 只在赶时间且刚跑过测试时用
#
# versionName=YYYY.MM.DD.N，versionCode=YYMMDDNN，日期按 Asia/Shanghai，
# 与本机 rider-publish.sh 对齐。不要再产出 0.1.0。
[CmdletBinding()]
param(
    [int]$Sequence = 0,
    [switch]$Arm64Only,
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$app = Join-Path $repo 'rider-android'

$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_SDK_ROOT = 'C:\Users\Administrator\AppData\Local\Android\Sdk'
$env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
$adb = Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe'

$apkPath = Join-Path $app 'app\build\outputs\apk\release\app-release.apk'
$buildTools = (Get-ChildItem (Join-Path $env:ANDROID_SDK_ROOT 'build-tools') -Directory |
    Sort-Object Name -Descending | Select-Object -First 1).FullName
$aapt2 = Join-Path $buildTools 'aapt2.exe'

function Get-ApkVersionCode([string]$path) {
    if (-not (Test-Path $path)) { return 0 }
    $line = & $aapt2 dump badging $path 2>$null | Select-String "versionCode='(\d+)'"
    if ($line) { return [int]$line.Matches[0].Groups[1].Value }
    return 0
}

# 已发出去的最高 versionCode。记在工作区外，构建产物被覆盖或 clean 掉都不影响。
$stateFile = Join-Path $env:USERPROFILE '.yulin\rider-last-version'

# versionCode 是上海时区 YYMMDD + 两位当日序号，必须单调递增。
# versionName 与本机脚本相同：YYYY.MM.DD.N。
#
# 三个来源取最大，缺一不可：
#   - 这个状态文件：唯一可靠的「已发出去的最高版本」；
#   - 磁盘上的 release 包：状态文件丢了时的兜底，但它会被下一次构建覆盖，不能单独依赖；
#   - 设备上已装的：往往是 debug 包（不带显式版本），只看它可能算出比已发版本还小的号。
$tz = $null
try { $tz = [TimeZoneInfo]::FindSystemTimeZoneById('China Standard Time') } catch { $tz = $null }
if (-not $tz) {
    try { $tz = [TimeZoneInfo]::FindSystemTimeZoneById('Asia/Shanghai') } catch { $tz = $null }
}
$now = if ($tz) { [TimeZoneInfo]::ConvertTimeFromUtc([DateTime]::UtcNow, $tz) } else { Get-Date }
$today = [int]$now.ToString('yyMMdd')
if ($Sequence -le 0) {
    $known = @(0, (Get-ApkVersionCode $apkPath))
    if (Test-Path $stateFile) {
        $saved = 0
        if ([int]::TryParse((Get-Content $stateFile -Raw).Trim(), [ref]$saved)) { $known += $saved }
    }
    if (Test-Path $adb) {
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $line = & $adb shell dumpsys package com.yulin.rider 2>$null |
            Select-String 'versionCode=(\d+)' | Select-Object -First 1
        $ErrorActionPreference = $prevEap
        if ($line) { $known += [int]$line.Matches[0].Groups[1].Value }
    }
    $highest = ($known | Measure-Object -Maximum).Maximum
    $Sequence = if ([math]::Floor($highest / 100) -eq $today) { ($highest % 100) + 1 } else { 1 }
}
if ($Sequence -lt 1 -or $Sequence -gt 99) { throw "当日序号越界：$Sequence" }
$versionCode = $today * 100 + $Sequence
$versionName = $now.ToString('yyyy.MM.dd') + ".$Sequence"

$tasks = @()
if (-not $SkipTests) { $tasks += 'testDebugUnitTest' }
$tasks += 'verifyReleaseSignature'          # 它自带 assembleRelease 并校验签名
$gradleArgs = $tasks + @(
    "-PRIDER_BUILD_SEQUENCE=$Sequence",
    "-PRIDER_VERSION_CODE=$versionCode",
    "-PRIDER_VERSION_NAME=$versionName",
    '--console=plain'
)
if ($Arm64Only) { $gradleArgs += '-PRIDER_ABI=arm64' }

# 日志写临时目录：build/preview 下的文件常被别的进程占着，写进去会中断构建
$log = Join-Path $env:TEMP "rider-ship-$(Get-Date -Format 'HHmmss').log"
Write-Host "[ship] 应急出包 $versionName ($versionCode)，任务：$($tasks -join ' ')" -ForegroundColor Cyan

Push-Location $app
try {
    # Gradle 会把 SDK XML 版本提示写到 stderr。PowerShell 5.1 在 Stop 模式下
    # 会把 NativeCommandError 当成终止异常，构建刚启动就被掐掉。
    $argLine = ($gradleArgs | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
    cmd /c "gradlew.bat $argLine > `"$log`" 2>&1"
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($code -ne 0) {
    Write-Host "[ship] 构建失败，关键报错：" -ForegroundColor Red
    Get-Content $log | Where-Object { $_ -match '^e: |FAILURE|error:|FAILED' } | Select-Object -First 15
    Write-Host "[ship] 完整日志：$log"
    exit 1
}

$apk = Get-Item $apkPath
$badging = & $aapt2 dump badging $apk.FullName 2>$null

# 记下这一版，下次接着往上走
$built = Get-ApkVersionCode $apk.FullName
New-Item -ItemType Directory -Force -Path (Split-Path $stateFile) | Out-Null
Set-Content -Path $stateFile -Value $built -NoNewline
$version = ($badging | Select-String "^package").Line
$abis = ($badging | Select-String 'native-code').Line
$sha = (Get-FileHash $apk.FullName -Algorithm SHA256).Hash

Write-Host ''
Write-Host '[ship] 打包完成，签名校验通过' -ForegroundColor Green
Write-Host ("  " + $version)
Write-Host ("  " + $abis)
Write-Host ("  大小     : {0:N1} MB" -f ($apk.Length / 1MB))
Write-Host ("  SHA-256  : " + $sha)
Write-Host ''
Write-Host $apk.FullName
