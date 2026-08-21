param(
  # Game install to patch against. Overrides every other source; use it when you
  # have more than one install and want a specific one.
  [string]$InstallDir
)

$JavaRelease = "25"
$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

# ~/.sk-utils — the SAME directory the mod reads at runtime (SKConfig/SocketInputState
# use System.getProperty("user.home")), so a config seeded here is the one that loads.
$ConfigDir = Join-Path $HOME ".sk-utils"
$ConfigPath = Join-Path $ConfigDir "config.properties"

function Test-SKInstall([string]$dir) {
  if ([string]::IsNullOrWhiteSpace($dir)) { return $false }
  return (Test-Path (Join-Path $dir "getdown.txt")) -and
         (Test-Path (Join-Path $dir "code\projectx-pcode.jar"))
}

function Get-SKInstallCandidates {
  $c = New-Object System.Collections.Generic.List[string]
  foreach ($base in @($env:LOCALAPPDATA, $env:APPDATA, $HOME)) {
    if ($base) {
      $c.Add((Join-Path $base "Spiral Knights\app"))
      $c.Add((Join-Path $base "Spiral Knights"))
    }
  }
  # Steam: the registry gives the root install; libraryfolders.vdf lists the rest.
  $steamRoots = New-Object System.Collections.Generic.List[string]
  foreach ($key in @('HKCU:\Software\Valve\Steam', 'HKLM:\SOFTWARE\WOW6432Node\Valve\Steam')) {
    try {
      $p = (Get-ItemProperty $key -ErrorAction Stop).SteamPath
      if ($p) { $steamRoots.Add($p.Replace('/', '\')) }
    } catch { }
  }
  foreach ($pf in @(${env:ProgramFiles(x86)}, $env:ProgramFiles)) {
    if ($pf) { $steamRoots.Add((Join-Path $pf "Steam")) }
  }
  $libs = New-Object System.Collections.Generic.List[string]
  foreach ($root in $steamRoots) {
    $libs.Add($root)
    $vdf = Join-Path $root "steamapps\libraryfolders.vdf"
    if (Test-Path $vdf) {
      foreach ($m in [regex]::Matches((Get-Content $vdf -Raw), '"path"\s*"([^"]+)"')) {
        $libs.Add($m.Groups[1].Value.Replace('\\', '\'))
      }
    }
  }
  foreach ($lib in $libs) { $c.Add((Join-Path $lib "steamapps\common\Spiral Knights")) }
  return $c
}

# Resolution order: -InstallDir > SK_INSTALL_DIR env > install_dir= in
# ~/.sk-utils/config.properties > auto-discovery. Nothing is hardcoded to a machine.
$SKInstallDir = $null
$installSource = $null

if ($InstallDir) { $SKInstallDir = $InstallDir; $installSource = "-InstallDir" }
elseif ($env:SK_INSTALL_DIR) { $SKInstallDir = $env:SK_INSTALL_DIR; $installSource = "SK_INSTALL_DIR" }
elseif (Test-Path $ConfigPath) {
  $line = Get-Content $ConfigPath | Where-Object { $_ -match '^\s*install_dir\s*=' } | Select-Object -First 1
  if ($line) {
    $SKInstallDir = ($line -split '=', 2)[1].Trim()
    $installSource = "config.properties install_dir"
  }
}

if ($SKInstallDir) {
  if (-not (Test-SKInstall $SKInstallDir)) {
    throw "$installSource points at '$SKInstallDir', which has no getdown.txt + code\projectx-pcode.jar."
  }
  Write-Host "Game install ($installSource): $SKInstallDir"
} else {
  $found = @(Get-SKInstallCandidates | Select-Object -Unique | Where-Object { Test-SKInstall $_ })
  if ($found.Count -eq 0) {
    throw ("No Spiral Knights install found. Pass one explicitly:`n" +
           "  .\build.ps1 -InstallDir 'C:\path\to\Spiral Knights\app'`n" +
           "or set SK_INSTALL_DIR, or add 'install_dir=...' to $ConfigPath")
  }
  $SKInstallDir = $found[0]
  Write-Host "Game install (auto-detected): $SKInstallDir"
  if ($found.Count -gt 1) {
    Write-Host "  note: $($found.Count) installs found; using the first. Others:"
    $found | Select-Object -Skip 1 | ForEach-Object { Write-Host "    $_" }
    Write-Host "  override with -InstallDir, SK_INSTALL_DIR, or install_dir= in config.properties"
  }
}

$SKCodeDir = Join-Path $SKInstallDir "code"
$OutDir = Join-Path $ScriptDir "out"
$JavassistJar = Join-Path $ScriptDir "javassist.jar"
$ZipPath = Join-Path $ScriptDir "sk-utils-mod.zip"

Write-Host "Reading game version..."
$getdownPath = Join-Path $SKInstallDir "getdown.txt"

$versionLine = Get-Content $getdownPath | Where-Object { $_ -match '^version' } | Select-Object -First 1
$PX_VERSION = ($versionLine -split '=', 2)[1].Trim()

Write-Host "Detected game version: $PX_VERSION"

Write-Host "Cleaning output directory..."
if (Test-Path $OutDir) {
  Remove-Item $OutDir -Recurse -Force
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

if (!(Test-Path $JavassistJar)) {
  Write-Host "Downloading Javassist..."
  Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/org/javassist/javassist/3.29.2-GA/javassist-3.29.2-GA.jar" -OutFile $JavassistJar
}

New-Item -ItemType Directory -Force -Path $ConfigDir | Out-Null

if (!(Test-Path $ConfigPath)) {
  Copy-Item "config.properties" $ConfigPath
  Write-Host "Created $ConfigPath - edit main_account before running the mod."
}

Write-Host "Compiling Patcher..."
javac --release $JavaRelease -cp "$JavassistJar" -d "." "Patcher.java" "modutils\ClassFinder.java" "modutils\MemberFinder.java"

Write-Host "Compiling SKConfig..."
javac --release $JavaRelease -d "$OutDir" "SKConfig.java"

Write-Host "Running Patcher..."
java -cp "$JavassistJar;$OutDir;." Patcher "$SKCodeDir\projectx-pcode.jar" "$OutDir"

Write-Host "Compiling MappingsNames.java..."
javac --release $JavaRelease -cp "$SKCodeDir\*" "$OutDir\MappingsNames.java" -d "$OutDir"

Write-Host "Compiling mod classes..."
javac --release $JavaRelease -cp "$OutDir;$SKCodeDir\*" "Mappings.java" "Reflect.java" "WheelDodge.java" "SocketInputState.java" "SpriteFeeder.java" "MissionStats.java" "PvpAutoQueuer.java" "DamageMeter.java" "RoutineFile.java" "KeyBinds.java" "AuctionBot.java" "AutoJoiner.java" "ForgeTracker.java" -d "$OutDir"
if ($LASTEXITCODE -ne 0) { throw "javac failed (mod classes) - NOT zipping a broken build" }
javac --release $JavaRelease -cp "$OutDir;$SKCodeDir\*" "ForgeAllAdapter.java" -d "$OutDir"
if ($LASTEXITCODE -ne 0) { throw "javac failed (ForgeAllAdapter) - NOT zipping a broken build" }
javac --release $JavaRelease -cp "$OutDir;$SKCodeDir\*" "HeatHudPanel.java" -d "$OutDir"
if ($LASTEXITCODE -ne 0) { throw "javac failed (HeatHudPanel) - NOT zipping a broken build" }

Write-Host "Generating mod.json..."

$json = @"
{
  "mod": {
    "name": "SK Utils Mod (Windows)",
    "description": "SK utility mod.",
    "author": "with love, prince of krogmo",
    "version": "7.0",
    "type": "class",
    "pxVersion": "$PX_VERSION"
  }
}
"@

$ModJsonPath = Join-Path $OutDir "mod.json"
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($ModJsonPath, $json, $Utf8NoBom)

# Mod icon: KnightLauncher's Mod.parseMetadata reads a root-level "mod.png" from the
# zip and shows it as this mod's thumbnail in the launcher's mod list (mod.json has no
# "image" key, so the launcher falls through to mod.png). Ship banstick.png as mod.png.
$IconPath = Join-Path $ScriptDir "banstick.png"
if (Test-Path $IconPath) {
  Copy-Item $IconPath (Join-Path $OutDir "mod.png")
  Write-Host "Mod icon: banstick.png -> mod.png"
} else {
  Write-Host "  note: banstick.png not found - building without a mod icon"
}

Write-Host "Zipping mod..."

if (Test-Path $ZipPath) {
  Remove-Item $ZipPath -Force
}

Compress-Archive -Path "$OutDir\*" -DestinationPath $ZipPath -Force

Write-Host "Done! Mod saved to $ZipPath"