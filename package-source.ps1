# Packages the sk-utils SOURCE for distribution as sk-utils-src.zip.
# Explicit INCLUDE list staged to a temp dir

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

$ZipPath = Join-Path $ScriptDir "sk-utils-src.zip"
$Stage = Join-Path $env:TEMP "sk-utils-src-stage"

$rootFiles = @(Get-ChildItem -File "*.java" | ForEach-Object { $_.Name }) + @(
  "build.ps1",
  "package-source.ps1",
  "multibox.py",
  "config.properties",
  "README.md",
  "banstick.png"   # mod icon; build.ps1 ships it into the mod zip as mod.png
)

$missing = $rootFiles | Where-Object { -not (Test-Path $_) }
if ($missing) { throw "missing from source tree: $($missing -join ', ')" }

if (Test-Path $Stage) { Remove-Item $Stage -Recurse -Force }
New-Item -ItemType Directory -Path (Join-Path $Stage "modutils") -Force | Out-Null
foreach ($f in $rootFiles) { Copy-Item $f $Stage }
Copy-Item "modutils\*.java" (Join-Path $Stage "modutils")

if (Test-Path $ZipPath) { Remove-Item $ZipPath -Force }
Compress-Archive -Path "$Stage\*" -DestinationPath $ZipPath
Remove-Item $Stage -Recurse -Force

Write-Host "Done! Source package saved to $ZipPath"
