#!/bin/bash
# Puts everything the live recordings need where the clean macOS account can reach it (it can't read this
# project folder):  /Users/Shared/mac-n-droid-recording/
#   Mac n Droid (recording).app   a debug build of the Mac app (the left-behind trigger only exists in debug builds)
#   demo-phone.sh (runs FakePhone.java --demo), demo/, left-behind.sh, phone-demo.sh, LIVE-TAKES.md, props/
#   tools/video/recording-kit.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
KIT="/Users/Shared/mac-n-droid-recording"
APP="$KIT/Mac n Droid (recording).app"

cd "$ROOT/mac"
echo "▸ Building (debug)"
swift build >/dev/null
BIN="$(swift build --show-bin-path)/MacNDroid"

rm -rf "$KIT"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources" "$APP/Contents/Frameworks"
cp "$BIN" "$APP/Contents/MacOS/MacNDroid"
ditto .build/artifacts/sparkle/Sparkle/Sparkle.xcframework/macos-arm64_x86_64/Sparkle.framework \
  "$APP/Contents/Frameworks/Sparkle.framework"
# Its own app id and no update feed: it never mixes with an installed Mac n Droid or offers to replace itself.
sed -e "s/__VERSION__/0.0.0/" -e "s/__BUILD__/1/" -e "s/app\.macndroid\.mac/app.macndroid.mac.recording/" \
  -e "s#<key>SUEnableAutomaticChecks</key><true/>#<key>SUEnableAutomaticChecks</key><false/>#" \
  Resources/Info.plist > "$APP/Contents/Info.plist"
iconutil -c icns Resources/AppIcon.iconset -o "$APP/Contents/Resources/AppIcon.icns"
cp Resources/MenuBarIcon.png Resources/MenuBarIcon@2x.png Resources/Sounds/*.wav "$APP/Contents/Resources/"
ditto "$ROOT/browser-extension" "$APP/Contents/Resources/BrowserExtension"
xattr -cr "$APP"
codesign --force --sign - "$APP/Contents/Frameworks/Sparkle.framework" >/dev/null 2>&1 || true
codesign --force --sign - "$APP" >/dev/null 2>&1 || true

echo "▸ Building the phone's demo notification apps"
"$ROOT/tools/video/phone-notifications.sh" build >/dev/null
cp -R "$ROOT/tools/video/demo-apks" "$KIT/demo-apks"
cp "$ROOT/tools/FakePhone.java" "$ROOT/tools/video/left-behind.sh" "$ROOT/tools/video/phone-demo.sh" "$ROOT/tools/video/phone-notifications.sh" "$KIT/"
cp -R "$ROOT/video/series/demo" "$KIT/demo"
[ -f "$ROOT/video/series/hero/LIVE-TAKES.md" ] && cp "$ROOT/video/series/hero/LIVE-TAKES.md" "$KIT/"
[ -d "$ROOT/video/series/hero/props" ] && cp -R "$ROOT/video/series/hero/props" "$KIT/props"
cat > "$KIT/demo-phone.sh" <<'SH'
#!/bin/bash
# The demo phone ("Pixel 8", 82%).   ./demo-phone.sh notify        ./demo-phone.sh list
# Starts its scene 5 seconds after it connects (DELAY=8 ./demo-phone.sh code to change that). Ctrl-C to stop.
cd "$(dirname "$0")"
JAVA="$(command -v java || true)"; [ -x /opt/homebrew/opt/openjdk@17/bin/java ] && JAVA=/opt/homebrew/opt/openjdk@17/bin/java
exec "$JAVA" -Ddelay="${DELAY:-5}" -Dgap="${GAP:-8}" -Ddemo.files=demo FakePhone.java 127.0.0.1 "${PORT:-47800}" --demo "${1:-list}"
SH
chmod +x "$KIT/demo-phone.sh"
chmod -R a+rX "$KIT"
echo "✓ $KIT"
