#!/bin/bash
# The story's notifications on the recording phone, from invented apps with their own names and icons
# (Messages: Priya · Northwind Bank: the 482913 code · Files: a download). Debug-only props in tools/video/demo-apps.
#   phone-notifications.sh install     build (if needed) and install the three apps, allow their notifications
#   phone-notifications.sh story       code, then Priya's text, then the download, 2 s apart (the lock-screen take)
#   phone-notifications.sh text | code | download      one of them
#   phone-notifications.sh clear       take them away        phone-notifications.sh uninstall
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ADB="${ADB:-$(command -v adb || echo /opt/homebrew/share/android-commandlinetools/platform-tools/adb)}"
APKS="$HERE/demo-apks"                       # in the recording kit the built apps sit next to this script
SRC="$HERE/demo-apps"
pkg() { echo "app.macndroid.demo.$1"; }
post() { "$ADB" shell am broadcast --include-stopped-packages -n "$(pkg "$1")/app.macndroid.demo.Post" "${@:2}" >/dev/null; }
build() {
  [ -d "$SRC" ] || return 0
  [ -n "${JAVA_HOME:-}" ] || export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
  [ -f "$SRC/local.properties" ] || cp "$HERE/../../android/local.properties" "$SRC/" 2>/dev/null || true
  "$HERE/../../android/gradlew" -q -p "$SRC" assembleDebug
  mkdir -p "$APKS"
  for f in messages bank files; do cp "$SRC/app/build/outputs/apk/$f/debug/app-$f-debug.apk" "$APKS/$f.apk"; done
}
case "${1:-}" in
  build) build; echo "✓ $APKS" ;;
  install)
    [ -f "$APKS/messages.apk" ] || build
    for f in messages bank files; do
      "$ADB" install -r "$APKS/$f.apk" >/dev/null
      "$ADB" shell pm grant "$(pkg "$f")" android.permission.POST_NOTIFICATIONS
    done
    echo "✓ installed Messages, Northwind Bank and Files (demo)" ;;
  text) post messages ;;
  code) post bank ;;
  download) post files ;;
  story) post bank; sleep "${GAP:-2}"; post messages; sleep "${GAP:-2}"; post files ;;
  clear) for f in messages bank files; do post "$f" --ez clear true; done ;;
  uninstall) for f in messages bank files; do "$ADB" uninstall "$(pkg "$f")" >/dev/null || true; done; echo "✓ removed" ;;
  *) sed -n 2,7p "$0"; exit 1 ;;
esac
