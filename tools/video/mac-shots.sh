#!/bin/bash
# Stills of the real Mac app for the video series (video/series/STORY.md, section 5B).
#   tools/video/mac-shots.sh [output folder] [--scale 3] [--only <part of a name>[,<another>]] [--date 2026-11-12] [--time 18:40]
# --date and --time are the story's clock (default: Thursday 12 November 2026, 6:40 PM).
# Default output: video/series/captures/mac/
#
# Builds the debug app into a throwaway bundle with its own app id (app.macndroid.mac.shots), so the
# installed Mac n Droid, its settings and its pairing are never touched. Nothing appears on screen and
# no network is started.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/video/series/captures/mac"
if [ $# -gt 0 ] && [[ "$1" != --* ]]; then OUT="$1"; shift; fi

cd "$ROOT/mac"
echo "▸ Building (debug)"
swift build >/dev/null
BIN="$(swift build --show-bin-path)/MacNDroid"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/macndroid-shots.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
APP="$WORK/Mac n Droid Shots.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources" "$APP/Contents/Frameworks"
cp "$BIN" "$APP/Contents/MacOS/MacNDroid"
ditto .build/artifacts/sparkle/Sparkle/Sparkle.xcframework/macos-arm64_x86_64/Sparkle.framework \
  "$APP/Contents/Frameworks/Sparkle.framework"
sed -e "s/__VERSION__/0.0.0/" -e "s/__BUILD__/1/" -e "s/app\.macndroid\.mac/app.macndroid.mac.shots/" \
  Resources/Info.plist > "$APP/Contents/Info.plist"
cp Resources/MenuBarIcon.png Resources/MenuBarIcon@2x.png Resources/Sounds/*.wav "$APP/Contents/Resources/"
# Ad-hoc signatures are enough to run it here.
codesign --force --sign - "$APP/Contents/Frameworks/Sparkle.framework" >/dev/null 2>&1 || true
codesign --force --sign - "$APP" >/dev/null 2>&1 || true

DEMO="$ROOT/video/series/demo"
if [ ! -f "$DEMO/dinner-menu.jpg" ]; then
  echo "▸ Drawing the demo files into video/series/demo/"
  MACNDROID_CONFIG="$WORK/config" "$APP/Contents/MacOS/MacNDroid" --video-demo-files "$DEMO"
fi

# The story's clock: the capture app believes it is this date and time (tools/video/fixed-clock.c), so dates,
# times and "4 min ago" labels come out the same whenever this runs.
DATE="2026-11-12"; TIME="18:40"; ARGS=("$@")
for ((i = 0; i < ${#ARGS[@]}; i++)); do
  [ "${ARGS[$i]}" = "--date" ] && DATE="${ARGS[$((i + 1))]:-$DATE}"
  [ "${ARGS[$i]}" = "--time" ] && TIME="${ARGS[$((i + 1))]:-$TIME}"
done
clang -dynamiclib -O2 -include stdint.h -o "$WORK/fixed-clock.dylib" "$ROOT/tools/video/fixed-clock.c"

echo "▸ Capturing (clock: $DATE $TIME)"
mkdir -p "$OUT"
MND_CLOCK="${DATE}T${TIME}:00" DYLD_INSERT_LIBRARIES="$WORK/fixed-clock.dylib" \
MACNDROID_CONFIG="$WORK/config" "$APP/Contents/MacOS/MacNDroid" --video-shots "$OUT" --demo-files "$DEMO" "$@" 2>&1 \
  | grep -E "^(✓|!|Run this)" || true
defaults delete app.macndroid.mac.shots >/dev/null 2>&1 || true

python3 "$ROOT/tools/video/gallery.py" >/dev/null 2>&1 || true
