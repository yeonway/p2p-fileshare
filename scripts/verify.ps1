param(
    [switch]$IncludeWindows,
    [switch]$IncludeAndroid,
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot

function Invoke-Step {
    param(
        [string]$Name,
        [scriptblock]$Body
    )

    Write-Host ""
    Write-Host "==> $Name"
    & $Body
}

Invoke-Step "Server tests" {
    Push-Location (Join-Path $Root "server")
    try {
        python -m pytest
    } finally {
        Pop-Location
    }
}

if ($IncludeWindows) {
    Invoke-Step "Windows build" {
        Push-Location (Join-Path $Root "windows")
        try {
            if (-not $SkipInstall) {
                npm ci
            }
            npm run build
        } finally {
            Pop-Location
        }
    }
}

if ($IncludeAndroid) {
    Invoke-Step "Android unit test and debug APK" {
        Push-Location (Join-Path $Root "android")
        try {
            if (-not $env:ANDROID_HOME -and $env:LOCALAPPDATA) {
                $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android\Sdk"
            }
            .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
        } finally {
            Pop-Location
        }
    }
}
