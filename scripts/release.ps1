<#
.SYNOPSIS
  Cut a release on Windows: bump VERSION, test, build, package the dist zip.

.DESCRIPTION
  Windows counterpart of opencode/release.sh, producing the same zip layout:

    dist\vp-router-revamped-<version>.zip
      vp-router-revamped-<version>\
        router.jar, plugin.xml, schemas\, LICENSE, NOTICE.txt
        install-windows.ps1   (release mode: no build needed)
        install.sh            (Linux/macOS installer, --release mode)
        skills\vp-router\     (opencode skill)
        claude-skills\vp-router\ (Claude Code skill)

  Refuses a dirty working tree, so the version label always matches a
  commit. The version bump itself is left uncommitted for you to review.

.EXAMPLE
  .\scripts\release.ps1 -Version 0.17.0
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
function Fail($message) { Write-Host "ERROR: $message" -ForegroundColor Red; exit 1 }

Push-Location $repo
try {
    if (git status --porcelain) { Fail 'Working tree is dirty; commit first.' }

    $src = Join-Path $repo 'plugin\src\main\java\vpmcp\RouterPlugin.java'
    $code = Get-Content -Raw $src
    $bumped = [regex]::Replace($code, 'private static final String VERSION = ".*?";',
        "private static final String VERSION = `"$Version`";")
    if ($bumped -notmatch [regex]::Escape("VERSION = `"$Version`"")) { Fail 'Version bump failed.' }
    [IO.File]::WriteAllText($src, $bumped)

    Push-Location (Join-Path $repo 'plugin')
    try {
        & mvn -B -q package
        if ($LASTEXITCODE -ne 0) { Fail "Tests or build failed (exit $LASTEXITCODE)." }
    } finally { Pop-Location }

    $name = "vp-router-revamped-$Version"
    $dist = Join-Path $repo 'dist'
    $zip = Join-Path $dist "$name.zip"
    $stage = Join-Path ([IO.Path]::GetTempPath()) ("vp-release-" + [guid]::NewGuid())
    $out = Join-Path $stage $name
    New-Item -ItemType Directory -Force $out, $dist | Out-Null
    New-Item -ItemType Directory -Force (Join-Path $out 'skills'), (Join-Path $out 'claude-skills') | Out-Null

    Copy-Item (Join-Path $repo 'plugin\target\router.jar') $out
    Copy-Item (Join-Path $repo 'plugin\src\main\resources\vp.router\plugin.xml') $out
    Copy-Item (Join-Path $repo 'scripts\install-windows.ps1') $out
    Copy-Item (Join-Path $repo 'opencode\install.sh') $out
    Copy-Item -Recurse (Join-Path $repo 'schemas') (Join-Path $out 'schemas')
    Copy-Item -Recurse (Join-Path $repo 'opencode\skills\vp-router') (Join-Path $out 'skills\vp-router')
    Copy-Item -Recurse (Join-Path $repo '.claude\skills\vp-router') (Join-Path $out 'claude-skills\vp-router')
    Copy-Item (Join-Path $repo 'LICENSE'), (Join-Path $repo 'plugin\NOTICE.txt') $out

    if (Test-Path $zip) { Remove-Item -Force $zip }
    Compress-Archive -Path $out -DestinationPath $zip
    Remove-Item -Recurse -Force $stage

    Write-Host "Release $Version ready: $zip" -ForegroundColor Cyan
    Write-Host "Next: commit the version bump, tag v$Version, push with tags, then"
    Write-Host "  gh release create v$Version `"$zip`""
} finally {
    Pop-Location
}
