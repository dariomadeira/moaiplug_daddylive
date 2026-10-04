# PowerShell build script para moaiplug_daddylive en Windows
param(
    [switch]$Verify = $false,
    [int]$Workers = 10,
    [int]$Timeout = 20,
    [switch]$ForceVerify = $false
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
if (-not $Root) { $Root = Get-Location }

$Version = "0.4.2"
$DataDir = Join-Path $Root "data"
New-Item -ItemType Directory -Force -Path $DataDir | Out-Null

# 1. Detectar Android SDK
$Sdk = $env:ANDROID_HOME
if (-not $Sdk -or -not (Test-Path $Sdk)) {
    $Sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
if (-not (Test-Path $Sdk)) {
    Write-Error "No se encontró Android SDK en ANDROID_HOME ni en $env:LOCALAPPDATA\Android\Sdk"
    exit 1
}

# Plataforma más reciente
$Platforms = Get-ChildItem (Join-Path $Sdk "platforms") | Where-Object { Test-Path (Join-Path $_.FullName "android.jar") } | Sort-Object Name
if (-not $Platforms) {
    Write-Error "No se encontró android.jar en $Sdk\platforms"
    exit 1
}
$AndroidJar = (Join-Path $Platforms[-1].FullName "android.jar")

# Build tools (d8)
$BuildTools = Get-ChildItem (Join-Path $Sdk "build-tools") | Sort-Object Name
if (-not $BuildTools) {
    Write-Error "No se encontró d8 en $Sdk\build-tools"
    exit 1
}
$D8 = Join-Path $BuildTools[-1].FullName "d8.bat"

# Detectar JDK y jar
$JarCmd = Get-Command "jar" -ErrorAction SilentlyContinue
$JarPath = $null
if ($JarCmd) {
    $JarPath = $JarCmd.Source
} else {
    $JavaHomes = @("C:\Program Files\Java\jdk-17", "C:\Program Files\Java\jdk-21", "C:\Program Files\Java\jdk-11", $env:JAVA_HOME)
    foreach ($jh in $JavaHomes) {
        if ($jh -and (Test-Path (Join-Path $jh "bin\jar.exe"))) {
            $JarPath = Join-Path $jh "bin\jar.exe"
            break
        }
    }
}
if (-not $JarPath) {
    Write-Error "No se encontró jar.exe. Verifica tu instalación de Java JDK."
    exit 1
}

# Detectar comando de Python 3
$PythonCmd = "py"
$PythonArgs = @("-3")
$pyTest = & py -3 -c "import sys; print(sys.version_info.major)" 2>$null
if ($pyTest -ne "3") {
    $PythonCmd = "python"
    $PythonArgs = @()
    $pyTest2 = & python -c "import sys; print(sys.version_info.major)" 2>$null
    if ($pyTest2 -ne "3") {
        Write-Error "Se requiere Python 3 para generar el catálogo."
        exit 1
    }
}

Write-Host ">> Limpiando build/" -ForegroundColor Cyan
Remove-Item -Recurse -Force (Join-Path $Root "build") -ErrorAction SilentlyContinue
$BuildContract = Join-Path $Root "build\contract"
$BuildPlugin = Join-Path $Root "build\plugin"
$BuildOut = Join-Path $Root "build\out"
New-Item -ItemType Directory -Force -Path $BuildContract, $BuildPlugin, $BuildOut | Out-Null

Write-Host ">> Compilando stubs del contrato" -ForegroundColor Cyan
& javac -source 8 -target 8 -d $BuildContract (Get-ChildItem (Join-Path $Root "src\contract\java\com\infomak\moai\contract\*.java")).FullName

Write-Host ">> Compilando plugin (contra contrato + android.jar)" -ForegroundColor Cyan
$Classpath = "$BuildContract;$AndroidJar"
& javac -source 8 -target 8 -cp $Classpath -d $BuildPlugin (Get-ChildItem (Join-Path $Root "src\plugin\java\com\infomak\moai\daddylive\*.java")).FullName

Write-Host ">> Empaquetando plugin.jar" -ForegroundColor Cyan
$PluginJar = Join-Path $Root "build\plugin.jar"
& $JarPath cf $PluginJar -C $BuildPlugin .

Write-Host ">> d8 -> dex" -ForegroundColor Cyan
& $D8 --min-api 21 --lib $AndroidJar --classpath $BuildContract --output $BuildOut $PluginJar

$DexFile = Join-Path $Root "plugin.dex"
Copy-Item (Join-Path $BuildOut "classes.dex") $DexFile -Force
$DexSize = (Get-Item $DexFile).Length
Write-Host ">> plugin.dex: $DexSize bytes" -ForegroundColor Green

$Sha256 = (Get-FileHash -Algorithm SHA256 $DexFile).Hash.ToLower()
Write-Host ">> sha256: $Sha256" -ForegroundColor Green

$VerifiedOnlineJson = Join-Path $DataDir "verified_java_online.json"
$VerifiedJson = Join-Path $DataDir "verified_java.json"
$CanalesJson = Join-Path $DataDir "canales.json"

if ($Verify -or ($ForceVerify)) {
    Write-Host ">> Verificando canales con $Workers workers (BatchVerify multihilo en Java)..." -ForegroundColor Cyan
    $BatchCp = "$BuildPlugin;$BuildContract"
    & java -cp $BatchCp com.infomak.moai.daddylive.BatchVerify --workers $Workers --out $VerifiedJson
    
    if (-not (Test-Path $VerifiedOnlineJson)) {
        Write-Error "ERROR: no se generó $VerifiedOnlineJson"
        exit 1
    }
}

if ((Test-Path $VerifiedOnlineJson) -and ((Get-Item $VerifiedOnlineJson).Length -gt 10)) {
    Write-Host ">> Usando canales online verificados existentes ($VerifiedOnlineJson)..." -ForegroundColor Cyan
    & $PythonCmd @PythonArgs (Join-Path $Root "scripts\build_catalog.py") --out $CanalesJson --online-only $VerifiedOnlineJson
} else {
    Write-Host ">> Generando catálogo desde /api/channels (sin filtro online)..." -ForegroundColor Yellow
    & $PythonCmd @PythonArgs (Join-Path $Root "scripts\build_catalog.py") --out $CanalesJson
}

Write-Host ">> Enriqueciendo canales con logos..." -ForegroundColor Cyan
& $PythonCmd @PythonArgs (Join-Path $Root "scripts\generate_logos.py") --input $CanalesJson --output $CanalesJson

$CanalesRaw = Get-Content $CanalesJson -Raw -Encoding UTF8
$ManifestFile = Join-Path $Root "manifest.json"
$ManifestHeader = @"
{
  "id": "moai_daddylive",
  "tag": "Daddy",
  "nombre": "Moai Daddylive",
  "version": "$Version",
  "minContrato": 1,
  "maxContrato": 1,
  "clase": "com.infomak.moai.daddylive.DaddylivePlugin",
  "sha256": "$Sha256",
  "canalInicial": "1",
  "canales": 
"@

$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($ManifestFile, ($ManifestHeader + $CanalesRaw + "`n}"), $Utf8NoBom)
$ManifestSize = (Get-Item $ManifestFile).Length
Write-Host ">> manifest.json generado: $ManifestSize bytes (UTF-8 sin BOM)" -ForegroundColor Green
Write-Host "OK - Build completado con éxito." -ForegroundColor Green
