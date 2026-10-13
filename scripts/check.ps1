$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$backendPython = Join-Path $projectRoot 'apps\backend\.venv\Scripts\python.exe'

if (-not (Test-Path -LiteralPath $backendPython)) {
    throw 'Backend virtual environment is missing. Run: cd apps/backend; uv sync --dev --no-editable'
}

Push-Location "$projectRoot\apps\backend"
try {
    & $backendPython -m pytest -q
    if ($LASTEXITCODE -ne 0) { throw 'Backend tests failed' }
} finally {
    Pop-Location
}

Push-Location $projectRoot
try {
    & $backendPython -m unittest discover -s scripts -p test_demo_preflight.py
    if ($LASTEXITCODE -ne 0) { throw 'Preflight tests failed' }
    pnpm test:web
    if ($LASTEXITCODE -ne 0) { throw 'Web tests failed' }
    pnpm test:desktop
    if ($LASTEXITCODE -ne 0) { throw 'Desktop tests failed' }
    pnpm build:web
    if ($LASTEXITCODE -ne 0) { throw 'Web build failed' }
} finally {
    Pop-Location
}

$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) {
    throw 'JDK 17 is required for Android checks. Configure JAVA_HOME and add java to PATH.'
}
$javaVersion = (& java -version 2>&1 | Select-Object -First 1) -join ''
if ($javaVersion -notmatch 'version "17\.') {
    throw "Android checks require JDK 17, but found: $javaVersion"
}
Push-Location "$projectRoot\apps\android"
try {
    .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Android checks failed' }

    $mergedManifest = '.\app\build\intermediates\merged_manifest\debug\processDebugMainManifest\AndroidManifest.xml'
    if (-not (Test-Path -LiteralPath $mergedManifest)) {
        throw "Merged Android manifest not found: $mergedManifest"
    }
    $manifestText = Get-Content -LiteralPath $mergedManifest -Raw
    if ($manifestText -notmatch 'android:minSdkVersion="24"') {
        throw 'Android minSdk regression: the built debug manifest must declare API 24.'
    }
} finally {
    Pop-Location
}

