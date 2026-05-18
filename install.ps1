# Azora Language Installer for Windows
# Requires: PowerShell 5.1+, JDK 17+
#
# Two modes of operation:
#   1. From source  - when run from the git repository (gradlew.bat present)
#   2. From archive - when run from an extracted distribution (bin\ and lib\ present)

$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "  Azora Language Installer" -ForegroundColor Cyan
Write-Host "  ========================" -ForegroundColor Cyan
Write-Host ""

$InstallDir = if ($env:AZORA_HOME) { $env:AZORA_HOME } else { "$env:USERPROFILE\.azoralang" }
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# ---------------------------------------------------------------------------
# Detect mode
# ---------------------------------------------------------------------------
if (Test-Path "$ScriptDir\gradlew.bat") {
    $Mode = "source"
    Write-Host "  Mode:    Build from source"
} elseif ((Test-Path "$ScriptDir\lib") -and (Test-Path "$ScriptDir\bin")) {
    $Mode = "archive"
    Write-Host "  Mode:    Install from distribution archive"
} else {
    Write-Host "  Error: Cannot determine installation mode." -ForegroundColor Red
    Write-Host ""
    Write-Host "  Run this script from either:"
    Write-Host "    - The azora-lang git repository  (contains gradlew.bat)"
    Write-Host "    - An extracted release archive    (contains bin\ and lib\)"
    exit 1
}

Write-Host "  Target:  $InstallDir"
Write-Host ""

# ---------------------------------------------------------------------------
# Check Java
# ---------------------------------------------------------------------------
try {
    $javaVersionOutput = (java -version 2>&1 | Select-Object -First 1).ToString()
    if ($javaVersionOutput -match '"(\d+)') {
        $javaVersion = [int]$Matches[1]
    } else {
        throw "Cannot parse Java version"
    }
    if ($javaVersion -lt 17) {
        Write-Host "  Error: Java $javaVersion detected, but Azora requires JDK 17+." -ForegroundColor Red
        exit 1
    }
    Write-Host "  Java:     $javaVersion"
} catch {
    Write-Host "  Error: Java is not installed or not in PATH." -ForegroundColor Red
    Write-Host "  Azora requires JDK 17 or later."
    Write-Host ""
    Write-Host "  Download from: https://adoptium.net/"
    Write-Host "  Or install with: winget install EclipseAdoptium.Temurin.17.JDK"
    exit 1
}

# ---------------------------------------------------------------------------
# Build from source (if applicable)
# ---------------------------------------------------------------------------
if ($Mode -eq "source") {
    Write-Host ""
    Write-Host "  Building Azora (this may take a minute)..."
    & "$ScriptDir\gradlew.bat" :app:installDist :build-tool:installDist -q
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  Build failed." -ForegroundColor Red
        exit 1
    }
    $DistBase = "$ScriptDir\app\build\install\azora"
    $BtDistBase = "$ScriptDir\build-tool\build\install\azora-build"
    $InternalDir = "$ScriptDir\Internal"
} else {
    $DistBase = $ScriptDir
    $BtDistBase = $ScriptDir
    $InternalDir = "$ScriptDir\Internal"
}

# ---------------------------------------------------------------------------
# Clean previous installation
# ---------------------------------------------------------------------------
if (Test-Path $InstallDir) {
    Write-Host "  Removing previous installation..."
    Remove-Item -Recurse -Force $InstallDir
}

New-Item -ItemType Directory -Force -Path "$InstallDir\bin" | Out-Null
New-Item -ItemType Directory -Force -Path "$InstallDir\lib" | Out-Null

# ---------------------------------------------------------------------------
# Copy JARs (skip non-Windows platform JARs)
# ---------------------------------------------------------------------------
Write-Host "  Copying libraries..."

function Copy-Jars($SrcDir) {
    Get-ChildItem "$SrcDir\*.jar" -ErrorAction SilentlyContinue | ForEach-Object {
        $name = $_.Name

        # Skip platform-specific JARs not for Windows
        if ($name -match '(android|ios|linux|macosx)-') {
            return
        }

        # Deduplicate
        if (-not (Test-Path "$InstallDir\lib\$name")) {
            Copy-Item $_.FullName "$InstallDir\lib\"
        }
    }
}

if ($Mode -eq "source") {
    Copy-Jars "$DistBase\lib"
    Copy-Jars "$BtDistBase\lib"
} else {
    Copy-Jars "$DistBase\lib"
}

# ---------------------------------------------------------------------------
# Copy Internal directory (stdlib, engine, tests)
# ---------------------------------------------------------------------------
if (Test-Path $InternalDir) {
    Write-Host "  Copying standard library..."
    Copy-Item -Recurse -Force $InternalDir "$InstallDir\Internal"
    Remove-Item -Recurse -Force "$InstallDir\Internal\Std\docs\node_modules" -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force "$InstallDir\Internal\Std\docs\dist" -ErrorAction SilentlyContinue
}

# ---------------------------------------------------------------------------
# Write VERSION
# ---------------------------------------------------------------------------
$Version = "unknown"
$BuildConfigPath = "$ScriptDir\build-config\src\commonMain\kotlin\dev\azora\lang\BuildConfig.kt"
if (Test-Path $BuildConfigPath) {
    $match = Select-String -Path $BuildConfigPath -Pattern 'const val VERSION\s*=\s*"([^"]+)"'
    if ($match) { $Version = $match.Matches[0].Groups[1].Value }
}
if (Test-Path "$ScriptDir\VERSION") {
    $Version = (Get-Content "$ScriptDir\VERSION" -First 1).Trim()
}
Set-Content "$InstallDir\VERSION" $Version

# ---------------------------------------------------------------------------
# Create wrapper batch scripts
# ---------------------------------------------------------------------------
$azoraCmd = @'
@echo off
setlocal enabledelayedexpansion

set "AZORA_HOME=%USERPROFILE%\.azoralang"

if not exist "%AZORA_HOME%" (
    echo Error: Azora is not installed. Expected installation at %AZORA_HOME% >&2
    exit /b 1
)

set "CLASSPATH="
for %%j in ("%AZORA_HOME%\lib\*.jar") do (
    if defined CLASSPATH (
        set "CLASSPATH=!CLASSPATH!;%%j"
    ) else (
        set "CLASSPATH=%%j"
    )
)

java -cp "%CLASSPATH%" -Dazora.home="%AZORA_HOME%" -Dazora.internal="%AZORA_HOME%\Internal" %AZORA_JAVA_OPTS% dev.azora.lang.MainKt %*
'@

$azoraBuildCmd = @'
@echo off
setlocal enabledelayedexpansion

set "AZORA_HOME=%USERPROFILE%\.azoralang"

if not exist "%AZORA_HOME%" (
    echo Error: Azora is not installed. Expected installation at %AZORA_HOME% >&2
    exit /b 1
)

set "CLASSPATH="
for %%j in ("%AZORA_HOME%\lib\*.jar") do (
    if defined CLASSPATH (
        set "CLASSPATH=!CLASSPATH!;%%j"
    ) else (
        set "CLASSPATH=%%j"
    )
)

java -cp "%CLASSPATH%" -Dazora.home="%AZORA_HOME%" -Dazora.internal="%AZORA_HOME%\Internal" %AZORA_JAVA_OPTS% dev.azora.build.MainKt %*
'@

Set-Content -Path "$InstallDir\bin\azora.bat" -Value $azoraCmd -Encoding ASCII
Set-Content -Path "$InstallDir\bin\azora-build.bat" -Value $azoraBuildCmd -Encoding ASCII

# ---------------------------------------------------------------------------
# Add to PATH
# ---------------------------------------------------------------------------
$BinPath = "$InstallDir\bin"
$UserPath = [Environment]::GetEnvironmentVariable("Path", "User")

if ($UserPath -and $UserPath.Split(';') -contains $BinPath) {
    Write-Host "  PATH already contains $BinPath"
} else {
    Write-Host "  Adding $BinPath to user PATH..."
    if ($UserPath) {
        [Environment]::SetEnvironmentVariable("Path", "$BinPath;$UserPath", "User")
    } else {
        [Environment]::SetEnvironmentVariable("Path", $BinPath, "User")
    }
    $env:Path = "$BinPath;$env:Path"
}

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
$LibSize = "{0:N1} MB" -f ((Get-ChildItem "$InstallDir\lib" -Recurse | Measure-Object -Property Length -Sum).Sum / 1MB)

Write-Host ""
Write-Host "  Installation complete!" -ForegroundColor Green
Write-Host ""
Write-Host "  Version:       $Version"
Write-Host "  azora          $InstallDir\bin\azora.bat"
Write-Host "  azora-build    $InstallDir\bin\azora-build.bat"
Write-Host "  Internal\      $InstallDir\Internal\"
Write-Host "  Library size:  $LibSize"
Write-Host ""
Write-Host "  Restart your terminal, then try:"
Write-Host "    azora version"
Write-Host "    azora run hello.az"
Write-Host "    azora-build init"
Write-Host ""
