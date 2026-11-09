#!/bin/bash
# Builds "Mac n Droid.app" and a downloadable DMG into mac/dist/.
#   ./scripts/build-app.sh [version]
# Set SIGN_ID="Developer ID Application: …" to sign for distribution (otherwise ad-hoc).
set -euo pipefail

VERSION="${1:-0.1.0}"
BUILD="$(date +%Y%m%d%H%M)"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist"
# Assemble outside the project: iCloud Drive (Desktop/Documents) tags bundles with Finder info,
# which code signing rejects. The system temp folder isn't indexed by Spotlight either.
mkdir -p "$ROOT/.build"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/macndroid-app.XXXXXX")"
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
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources" "$APP/Contents/Frameworks"
cp "$BIN" "$APP/Contents/MacOS/MacNDroid"
# Sparkle (automatic updates). ditto keeps the framework's symlinks intact.
ditto .build/artifacts/sparkle/Sparkle/Sparkle.xcframework/macos-arm64_x86_64/Sparkle.framework \
  "$APP/Contents/Frameworks/Sparkle.framework"
sed -e "s/__VERSION__/$VERSION/" -e "s/__BUILD__/$BUILD/" Resources/Info.plist > "$APP/Contents/Info.plist"

# Icons come from public/ via tools/make-icons.swift.
iconutil -c icns Resources/AppIcon.iconset -o "$APP/Contents/Resources/AppIcon.icns"
cp Resources/MenuBarIcon.png Resources/MenuBarIcon@2x.png "$APP/Contents/Resources/"
cp Resources/Sounds/*.wav "$APP/Contents/Resources/"
# The browser extension (music control from the phone, send pages to the phone).
ditto "$ROOT/../browser-extension" "$APP/Contents/Resources/BrowserExtension"

echo "▸ Signing ($SIGN_ID)"
xattr -cr "$APP"
# Sign inside-out: Sparkle's helpers, the framework, then the app with its entitlements.
SPK="$APP/Contents/Frameworks/Sparkle.framework/Versions/B"
for item in "$SPK/XPCServices/Installer.xpc" "$SPK/XPCServices/Downloader.xpc" "$SPK/Autoupdate" "$SPK/Updater.app"; do
  if [ -e "$item" ]; then codesign --force --options runtime --timestamp=none --sign "$SIGN_ID" "$item"; fi
done
codesign --force --options runtime --timestamp=none --sign "$SIGN_ID" "$APP/Contents/Frameworks/Sparkle.framework"
codesign --force --options runtime --timestamp=none --entitlements Resources/MacNDroid.entitlements --sign "$SIGN_ID" "$APP"

echo "▸ Creating DMG"
DMG="$DIST/MacNDroid-$VERSION.dmg"
STAGE="$(mktemp -d "${TMPDIR:-/tmp}/macndroid-dmg.XXXXXX")"
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

# The update feed the app checks (published with each GitHub release). Signed with the private update
# key in mac/signing/, which never goes on GitHub. NOTES_HTML="<ul><li>…</li></ul>" adds release notes.
KEY="$ROOT/signing/sparkle_ed25519_private.key"
if [ -f "$KEY" ]; then
  SIG="$("$ROOT/.build/artifacts/sparkle/Sparkle/bin/sign_update" --ed-key-file "$KEY" "$DMG")"
  URL="https://github.com/santosh-mudragada-1/mac-n-droid/releases/download/v$VERSION/MacNDroid-$VERSION.dmg"
  DEFAULT_NOTES="<p>See what&#8217;s new on <a href=\"https://github.com/santosh-mudragada-1/mac-n-droid/releases/tag/v$VERSION\">GitHub</a>.</p>"
  NOTES="${NOTES_HTML:-$DEFAULT_NOTES}"
  cat > "$DIST/appcast.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<rss version="2.0" xmlns:sparkle="http://www.andymatuschak.org/xml-namespaces/sparkle">
  <channel>
    <title>Mac n Droid</title>
    <item>
      <title>Version $VERSION</title>
      <pubDate>$(LC_ALL=C date -u "+%a, %d %b %Y %H:%M:%S +0000")</pubDate>
      <sparkle:version>$BUILD</sparkle:version>
      <sparkle:shortVersionString>$VERSION</sparkle:shortVersionString>
      <sparkle:minimumSystemVersion>14.0</sparkle:minimumSystemVersion>
      <description><![CDATA[$NOTES]]></description>
      <enclosure url="$URL" type="application/octet-stream" $SIG />
    </item>
  </channel>
</rss>
XML
  echo "✓ $DIST/appcast.xml"
else
  echo "! No update key at $KEY: skipped appcast.xml (automatic updates need it)"
fi
