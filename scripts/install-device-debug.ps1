param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)

$apkPath = Join-Path $PSScriptRoot "..\app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path -LiteralPath $apkPath)) { throw "Debug APK not found: $apkPath" }

& $AdbPath -s $Serial reverse tcp:8000 tcp:8000
if ($LASTEXITCODE -ne 0) { throw "adb reverse failed for $Serial" }

Start-Process -FilePath $AdbPath -ArgumentList @('-s', $Serial, 'install', '-r', $apkPath) -WindowStyle Hidden
