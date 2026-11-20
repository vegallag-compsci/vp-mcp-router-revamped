#!/usr/bin/env bash
# Cut a release on Linux/macOS: bump VERSION, test, build, package the dist zip.
# Single entry point so the version label always matches the code.
# Windows: scripts/release.ps1 does the same.
#
#   opencode/release.sh 0.17.0
#
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${1:-}"
if [ -z "$VERSION" ]; then echo "usage: release.sh <version>" >&2; exit 1; fi
if [ -n "$(cd "$REPO" && git status --porcelain)" ]; then
  echo "tree is dirty, commit first" >&2; exit 1
fi
command -v zip > /dev/null || { echo "missing dependency: zip" >&2; exit 1; }

SRC="$REPO/plugin/src/main/java/vpmcp/RouterPlugin.java"
sed -i.bak "s/private static final String VERSION = \".*\";/private static final String VERSION = \"$VERSION\";/" "$SRC"
rm -f "$SRC.bak"
grep -q "VERSION = \"$VERSION\"" "$SRC" || { echo "bump failed" >&2; exit 1; }

LOGS="$(mktemp -d)"
(cd "$REPO/plugin" && mvn -q package > "$LOGS/build.log" 2>&1) || {
  echo "tests or build failed, see $LOGS/build.log" >&2
  exit 1
}

NAME="vp-router-revamped-$VERSION"
DIST="$REPO/dist/$NAME.zip"
mkdir -p "$REPO/dist"
STAGE="$(mktemp -d)"
OUT="$STAGE/$NAME"
mkdir -p "$OUT/skills" "$OUT/claude-skills"
cp "$REPO/plugin/target/router.jar" "$OUT/"
cp "$REPO/plugin/src/main/resources/vp.router/plugin.xml" "$OUT/"
cp "$REPO/opencode/install.sh" "$OUT/install.sh"
cp "$REPO/scripts/install-windows.ps1" "$OUT/install-windows.ps1"
cp -r "$REPO/schemas" "$OUT/"
cp -r "$REPO/opencode/skills/vp-router" "$OUT/skills/vp-router"
cp -r "$REPO/.claude/skills/vp-router" "$OUT/claude-skills/vp-router"
cp "$REPO/LICENSE" "$REPO/plugin/NOTICE.txt" "$OUT/"
rm -f "$DIST"
(cd "$STAGE" && zip -qr "$DIST" "$NAME")
rm -rf "$STAGE"
echo "release $VERSION ready: $DIST"
echo "next: commit the version bump, tag v$VERSION, push with tags, then"
echo "  gh release create \"v$VERSION\" \"$DIST\""
