<#
.SYNOPSIS
  Install vp-mcp-router-revamped into Visual Paradigm on Windows.

.DESCRIPTION
  Windows counterpart of opencode/install.sh. Two modes, picked
  automatically:

  - Release mode: run from an unpacked release zip (router.jar sits next
    to this script). Nothing is built; no JDK or Maven needed.
  - Source mode: run from a repo checkout. Builds plugin\ with Maven
    (unit tests included) unless -SkipBuild.

  Either way it copies plugin.xml, lib\router.jar and the schema pack into
  <PluginsDir>\vp.router\. Existing mcp.properties, backups and vpmcp.log
  in that folder are kept.

  The schema pack is regenerated from the local VP runtime jar (lib02.jar)
  into the installed copy only (needs python and javap on PATH), so the
  schema guard matches this exact VP build. The source schemas are never
  modified.

  Refuses to run while Visual Paradigm is open: copying over a loaded jar
  breaks VP's classloader. Also refuses when the original Avernus plugin
  (vpmcp.server) is still active, since both listen on port 8899.

.EXAMPLE
  .\install-windows.ps1                      # from an unpacked release zip
.EXAMPLE
  .\scripts\install-windows.ps1 -DryRun      # from a checkout: show the plan
.EXAMPLE
  .\scripts\install-windows.ps1 -SkipBuild   # reuse plugin\target\router.jar
#>
[CmdletBinding()]
param(
    [string]$VpHome = 'C:\Program Files\Visual Paradigm 18.1',
    [string]$PluginsDir = (Join-Path $env:APPDATA 'VisualParadigm\plugins'),
    [string]$SchemaVersion = 'v18.1',
    [switch]$SkipBuild,
    [switch]$KeepRepoSchemas,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$releaseMode = Test-Path (Join-Path $PSScriptRoot 'router.jar')
$target = Join-Path $PluginsDir 'vp.router'
$openApi = Join-Path $VpHome 'lib\openapi.jar'
$runtimeJar = Join-Path $VpHome 'lib\lib02.jar'
if ($releaseMode) {
    $repo = $PSScriptRoot
    $jar = Join-Path $repo 'router.jar'
    $pluginXml = Join-Path $repo 'plugin.xml'
} else {
    $repo = Split-Path -Parent $PSScriptRoot
    $jar = Join-Path $repo 'plugin\target\router.jar'
    $pluginXml = Join-Path $repo 'plugin\src\main\resources\vp.router\plugin.xml'
}
$sourceSchemas = Join-Path $repo "schemas\$SchemaVersion"
$autovendor = Join-Path $repo 'schemas\autovendor.py'

function Step($message) { Write-Host "==> $message" -ForegroundColor Cyan }
function Fail($message) { Write-Host "ERROR: $message" -ForegroundColor Red; exit 1 }

# --- preconditions ---------------------------------------------------------
if (Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Name -like 'Visual Paradigm*' }) {
    Fail 'Visual Paradigm is running. Save your project, close Visual Paradigm, then run this again.'
}
if (-not (Test-Path $PluginsDir)) {
    Fail "Plugins folder not found: $PluginsDir (start Visual Paradigm once, or pass -PluginsDir)."
}
if (-not (Test-Path $sourceSchemas)) { Fail "Schema pack not found: $sourceSchemas (pass -SchemaVersion)." }
$oldPlugin = Join-Path $PluginsDir 'vpmcp.server'
if (Test-Path $oldPlugin) {
    Fail "The original read-only plugin is still active at $oldPlugin and would clash on port 8899. Move it out of the plugins folder first."
}

# --- build -----------------------------------------------------------------
if ($releaseMode) {
    Step "Release mode: installing the prebuilt jar from $repo"
} elseif ($SkipBuild) {
    Step 'Skipping build (-SkipBuild)'
} elseif ($DryRun) {
    Step "Would build: mvn -B package -Dvp.openApi=`"$openApi`" (in plugin\)"
} else {
    if (-not (Test-Path $openApi)) { Fail "openapi.jar not found at $openApi (pass -VpHome)." }
    Step 'Building plugin (mvn package, unit tests included)'
    Push-Location (Join-Path $repo 'plugin')
    try {
        & mvn -B -q package "-Dvp.openApi=$openApi"
        if ($LASTEXITCODE -ne 0) { Fail "Maven build failed (exit $LASTEXITCODE)." }
    } finally { Pop-Location }
}
if (-not $DryRun -and -not (Test-Path $jar)) { Fail "Plugin jar missing: $jar" }

# --- install ---------------------------------------------------------------
$copies = @(
    @{ From = $pluginXml;     To = Join-Path $target 'plugin.xml' },
    @{ From = $jar;           To = Join-Path $target 'lib\router.jar' },
    @{ From = $sourceSchemas; To = Join-Path $target "schemas\$SchemaVersion" }
)
Step "Installing into $target"
foreach ($copy in $copies) {
    Write-Host "    $($copy.From)`n      -> $($copy.To)"
}
if ($DryRun) {
    Step 'Dry run: nothing copied.'
    exit 0
}

New-Item -ItemType Directory -Force (Join-Path $target 'lib') | Out-Null
New-Item -ItemType Directory -Force (Join-Path $target 'schemas') | Out-Null
Copy-Item -Force $pluginXml (Join-Path $target 'plugin.xml')
# Stage the jar next to the target, then swap, so a failed copy never leaves a torn jar.
$staged = Join-Path $target 'lib\router.jar.new'
Copy-Item -Force $jar $staged
Move-Item -Force $staged (Join-Path $target 'lib\router.jar')
$installedSchemas = Join-Path $target "schemas\$SchemaVersion"
if (Test-Path $installedSchemas) { Remove-Item -Recurse -Force $installedSchemas }
Copy-Item -Recurse $sourceSchemas $installedSchemas

# --- schema pack for this VP build ------------------------------------------
if ($KeepRepoSchemas) {
    Step 'Keeping the shipped schema pack (-KeepRepoSchemas)'
} elseif (-not (Test-Path $runtimeJar)) {
    Write-Warning "lib02.jar not found at $runtimeJar; keeping the shipped schema pack."
} elseif (-not (Test-Path $autovendor) -or -not (Get-Command python -ErrorAction SilentlyContinue) `
        -or -not (Get-Command javap -ErrorAction SilentlyContinue)) {
    Write-Warning 'python or javap not available; keeping the shipped schema pack (the schema guard may warn, which is harmless).'
} else {
    Step 'Regenerating schema pack from this VP build (installed copy only)'
    $vpVersion = $SchemaVersion.TrimStart('v')
    & python -I $autovendor --openapi $runtimeJar --out $installedSchemas --vp-version $vpVersion
    if ($LASTEXITCODE -ne 0) {
        Write-Warning 'Schema regeneration failed; restoring the shipped pack.'
        Remove-Item -Recurse -Force $installedSchemas
        Copy-Item -Recurse $sourceSchemas $installedSchemas
    }
}

Step 'Installed. Open Visual Paradigm with a project; Tools > MCP Router shows the status.'
Write-Host "    Log: $(Join-Path $target 'vpmcp.log')"
