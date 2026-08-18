# Pixel Toolbox Magisk priv-app module builder.
# Produces a Magisk-installable zip that mounts the APK into /system/priv-app
# and ships the privileged permission allowlist.
$ErrorActionPreference = "Stop"

$root      = "F:\pixel-toolbox-root"
$moduleDir = Join-Path $root "magisk-module"
$srcApk    = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
$version   = "1.0.0"

if (-not (Test-Path $srcApk)) {
    throw "APK not found: $srcApk (run `gradlew assembleDebug` first)"
}

# 1. stage the APK into the priv-app directory
$privAppDir = Join-Path $moduleDir "system\priv-app\PixelToolbox"
if (Test-Path $privAppDir) { Remove-Item $privAppDir -Recurse -Force }
New-Item -ItemType Directory -Path $privAppDir -Force | Out-Null
Copy-Item $srcApk (Join-Path $privAppDir "PixelToolbox.apk") -Force

# 2. build a clean staging tree so zip root = module.prop + system/
$staging = Join-Path $moduleDir "staging"
if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
New-Item -ItemType Directory -Path $staging -Force | Out-Null
Copy-Item (Join-Path $moduleDir "module.prop") $staging -Force
Copy-Item (Join-Path $moduleDir "system") (Join-Path $staging "system") -Recurse -Force

# 3. zip it (manual entry creation to force forward-slash names; both
#    Compress-Archive and .NET CreateFromDirectory keep Windows backslashes)
$zipPath = Join-Path $moduleDir "PixelToolbox-privapp-v$version.zip"
if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($zipPath, 'Create')
try {
    Get-ChildItem -Path $staging -Recurse -File | ForEach-Object {
        $rel = $_.FullName.Substring($staging.Length + 1).Replace('\', '/')
        $entry = $zip.CreateEntry($rel, [System.IO.Compression.CompressionLevel]::Optimal)
        $es = $entry.Open()
        $fs = [System.IO.File]::OpenRead($_.FullName)
        $fs.CopyTo($es)
        $es.Dispose()
        $fs.Dispose()
    }
} finally {
    $zip.Dispose()
}

# 4. cleanup staging
Remove-Item $staging -Recurse -Force

Write-Output "Built: $zipPath"
Get-Item $zipPath | Select-Object FullName, Length | Format-List
