#!/bin/bash
# Stills and animation frames of the real Android app for the video series (video/series/STORY.md, section 5B).
#   tools/video/android-shots.sh [output folder] [-Ppalette=green] [-PallFrames=true]
# Default output: video/series/captures/android/   (1236 × 2745 px = a Pixel 8 screen at 3×)
# Rendered on this computer from the app's own Compose screens; no phone or emulator is needed.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/video/series/captures/android"
if [ $# -gt 0 ] && [[ "$1" != -* ]]; then OUT="$1"; shift; fi
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
mkdir -p "$OUT"
cd "$ROOT/android"
./gradlew -q videoShots -PshotsOut="$OUT" "$@"
echo "✓ $(find "$OUT" -name '*.png' | wc -l | tr -d ' ') captures in $OUT"
python3 "$ROOT/tools/video/gallery.py" >/dev/null 2>&1 || true
