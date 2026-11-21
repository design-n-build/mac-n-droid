#!/bin/bash
# Makes a running DEBUG build of the Mac app show its real "Did you leave Pixel 8 behind?" alert and chime
# (the alert itself is a macOS notification, so it can only be recorded live, not rendered as a still).
#   tools/video/left-behind.sh [seconds to wait first]
sleep "${1:-3}"
osascript -l JavaScript -e 'ObjC.import("Foundation"); $.NSDistributedNotificationCenter.defaultCenter.postNotificationNameObjectUserInfoDeliverImmediately("app.macndroid.demo.leftBehind", $(), $(), true)' >/dev/null
echo "✓ asked Mac n Droid for the left-behind alert"
