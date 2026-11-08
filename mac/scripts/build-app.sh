#!/bin/bash
# Builds "Mac n Droid.app" and a downloadable DMG into mac/dist/.
#   ./scripts/build-app.sh [version]
# Set SIGN_ID="Developer ID Application: …" to sign for distribution (otherwise ad-hoc).
set -euo pipefail

VERSION="${1:-0.1.0}"
BUILD="$(date +%Y%m%d%H%M)"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist"
# Work dirs end in .noindex so Spotlight never lists the temporary app copies.
mkdir -p "$ROOT/.build"
WORK="$(mktemp -d "$ROOT/.build/work.XXXXXX.noindex")"
APP="$WORK/Mac n Droid.app"
# Prefer a stable identity so macOS keeps Local Network/notification permissions across rebuilds
# (see README → "Stable signing"). Falls back to ad-hoc.
if [ -z "${SIGN_ID:-}" ] && security find-identity -p codesigning 2>/dev/null | grep -q "Mac n Droid Local Signing"; then
  SIGN_ID="Mac n Droid Local Signing"
fi
SIGN_ID="${SIGN_ID:--}"

cd "$ROOT"
echo "▸ Compiling (universal: arm64 + x86_64)"
BINS=()
for ARCH in arm64 x86_64; do
  swift build -c release --triple "$ARCH-apple-macosx14.0" >/dev/null
  BINS+=("$(swift build -c release --triple "$ARCH-apple-macosx14.0" --show-bin-path)/MacNDroid")
done
BIN="$DIST/MacNDroid-universal"
mkdir -p "$DIST"
lipo -create "${BINS[@]}" -output "$BIN"

echo "▸ Assembling app bundle"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/MacNDroid"
sed -e "s/__VERSION__/$VERSION/" -e "s/__BUILD__/$BUILD/" Resources/Info.plist > "$APP/Contents/Info.plist"

# Icons come from public/ via tools/make-icons.swift.
iconutil -c icns Resources/AppIcon.iconset -o "$APP/Contents/Resources/AppIcon.icns"
cp Resources/MenuBarIcon.png Resources/MenuBarIcon@2x.png "$APP/Contents/Resources/"
cp Resources/Sounds/*.wav "$APP/Contents/Resources/"

echo "▸ Signing ($SIGN_ID)"
xattr -cr "$APP"
codesign --force --deep --options runtime --timestamp=none --sign "$SIGN_ID" "$APP"

echo "▸ Creating DMG"
DMG="$DIST/MacNDroid-$VERSION.dmg"
STAGE="$(mktemp -d "$ROOT/.build/dmg.XXXXXX.noindex")"
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"
rm -f "$DMG"
hdiutil create -volname "Mac n Droid" -srcfolder "$STAGE" -ov -format UDZO "$DMG" >/dev/null
rm -rf "$STAGE"
rm -rf "$DIST/Mac n Droid.app" "$DIST/AppIcon.icns"
# Keep exactly one installed copy (in /Applications) so Spotlight shows a single app.
# NO_INSTALL=1 leaves /Applications alone and only produces the DMG.
if [ -z "${NO_INSTALL:-}" ]; then
  rm -rf "/Applications/Mac n Droid.app"
  ditto --norsrc "$APP" "/Applications/Mac n Droid.app"
  echo "✓ /Applications/Mac n Droid.app"
fi
rm -rf "$WORK" "$BIN"
echo "✓ $DMG"
