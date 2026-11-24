# Packs the NBT Evo build into what goes onto the head unit, like `make nbt-dist`, with Windows' own tools only:
#   dist\drivelog-bridge-nbt\       the folder to copy to the root of a USB stick
#   dist\drivelog-bridge-nbt.tar    the same as one archive
# Both hold MD5SUMS, install.sh, remove.sh and opt/hmi/ID5/data/ro/Plugins/DrivelogBridge/. Called by build_nbt.bat.
param([Parameter(Mandatory = $true)][string]$Root)
$ErrorActionPreference = 'Stop'

$name = 'DrivelogBridge'
$pluginPath = "opt/hmi/ID5/data/ro/Plugins/$name"
$files = @(
    'build\nbt\libDrivelogBridge.so',
    "$name.manifest", "$name.xml", "${name}_ALL_Texts.zip", "${name}_ALL_Images.zip"
)
foreach ($f in $files) {
    if (-not (Test-Path (Join-Path $Root $f))) { throw "$f is missing" }
}

$dist = Join-Path $Root 'dist'
$stage = Join-Path $dist 'drivelog-bridge-nbt'
$tar = Join-Path $dist 'drivelog-bridge-nbt.tar'
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
if (Test-Path $tar) { Remove-Item -Force $tar }
$pluginDir = Join-Path $stage ($pluginPath -replace '/', '\')
New-Item -ItemType Directory -Force $pluginDir | Out-Null
foreach ($f in $files) { Copy-Item (Join-Path $Root $f) $pluginDir }

# The scripts run under the head unit's sh: LF line endings whatever git did to them on Windows.
$utf8 = New-Object System.Text.UTF8Encoding $false
foreach ($s in 'install.sh', 'remove.sh') {
    $text = [System.IO.File]::ReadAllText((Join-Path $Root "install\$s")) -replace "`r`n", "`n"
    [System.IO.File]::WriteAllText((Join-Path $stage $s), $text, $utf8)
}

# MD5SUMS as md5sum writes it ("<hash>  <path>", LF), checked by install.sh with md5sum -c when the unit has md5sum.
$lines = foreach ($f in Get-ChildItem $pluginDir | Sort-Object Name) {
    $hash = (Get-FileHash -Algorithm MD5 $f.FullName).Hash.ToLowerInvariant()
    "$hash  $pluginPath/$($f.Name)"
}
[System.IO.File]::WriteAllText((Join-Path $stage 'MD5SUMS'), (($lines -join "`n") + "`n"), $utf8)

$bsdtar = Join-Path $env:SystemRoot 'System32\tar.exe'
if (-not (Test-Path $bsdtar)) { throw "$bsdtar not found (Windows 10 1803 or newer has it)" }
& $bsdtar -cf $tar -C $stage MD5SUMS install.sh remove.sh opt
if ($LASTEXITCODE -ne 0) { throw "tar failed ($LASTEXITCODE)" }

Write-Output "[DONE] $stage"
Write-Output "[DONE] $tar"
