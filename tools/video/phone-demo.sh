#!/bin/bash
# Clean status bar on the recording phone (Android's own System UI demo mode): 18:42, 82%, full Wi-Fi, no icons.
#   tools/video/phone-demo.sh on [hhmm]   |   tools/video/phone-demo.sh off
#   tools/video/phone-demo.sh record take.mp4     (scrcpy, 60 fps; Ctrl-C to stop)
set -euo pipefail
ADB="${ADB:-$(command -v adb || echo /opt/homebrew/share/android-commandlinetools/platform-tools/adb)}"
demo() { "$ADB" shell am broadcast -a com.android.systemui.demo "$@" >/dev/null; }
case "${1:-}" in
  on)
    "$ADB" shell settings put global sysui_demo_allowed 1
    demo -e command enter
    demo -e command clock -e hhmm "${2:-1842}"
    demo -e command battery -e level 82 -e plugged false
    demo -e command network -e wifi show -e level 4
    demo -e command network -e mobile hide
    demo -e command notifications -e visible false
    echo "✓ demo status bar on" ;;
  off) demo -e command exit; echo "✓ demo status bar off" ;;
  record) scrcpy --record="${2:-take.mp4}" --max-fps=60 --video-bit-rate=24M --no-audio ;;
  *) sed -n 2,4p "$0"; exit 1 ;;
esac
