# Mac n Droid for Windows (beta)

> **Beta:** Mac n Droid for Windows is new, so you may run into a few bugs. We're working on it. If something
> doesn't work, please [report it](https://github.com/design-n-build/mac-n-droid/issues/new?template=bug_report.yml); it helps a lot.

A WinUI 3 app (Windows App SDK) with Fluent design (Mica, the navigation pane, Settings-style cards), so it
looks like Windows 11's own apps. Windows 10 (version 2004) and later, on Intel/AMD (x64) and ARM PCs.

Same pages as the Mac app: Overview, Notifications, Messages, Phone screen, Clipboard, Files, Pair a phone and
Settings. It keeps running in the notification area (next to the clock) after you close the window, like the
Mac app in the menu bar. Notifications are Windows' own, with a Reply box and the phone's buttons (Answer and
Decline for calls).

**Everything the Mac app does:** pairing (QR code), battery, notifications with Reply, **Messages** (read and
send texts, new message with contact search), **Phone screen** (see and use your phone: click to tap, drag to
swipe, type, right-click for Back), music on the phone shown on the PC with controls, **music on the PC
controlled from the phone** (anything in Windows' media controls: Spotify, browsers, Media Player…), clipboard
both ways with history, files and **folders** both ways, links both ways, ring your phone, the **browser
extension** (Chrome, Edge, Firefox), the **left-behind alert**, Play sounds on/off, open at sign-in.
The webcam is Android's own USB webcam mode, which Windows supports without Mac n Droid.

## Install
Download `MacNDroid-x.y.z-Windows-x64.exe` (or `-arm64` for ARM PCs such as Surface Pro X / Copilot+ PCs)
from GitHub Releases and open it. The app isn't signed yet, so Windows shows "Windows protected your PC":
click **More info → Run anyway**. The first time it starts, allow it on **Private networks** when Windows
Firewall asks. A portable zip (no installer) is there too.

## Projects
- `Core/`: the protocol and app logic, plain .NET 8. Builds and runs on any computer.
- `CoreTest/`: an end-to-end test of the core, without a window, against `tools/fake_phone.py`.
- `App/`: the WinUI 3 app (built in C#, no XAML files). Mirroring uses Windows' own H.264 decoder
  (MediaStreamSource); music on the PC uses Windows' media controls (GlobalSystemMediaTransportControls).
  The installers are built by `.github/workflows/desktop.yml` on GitHub's Windows machines.

On a Mac or Linux computer you can still check that the app compiles (the last packaging step needs Windows):
```
dotnet build windows/App/MacNDroid.csproj -r win-x64 -p:Platform=x64 -p:WindowsAppSDKSelfContained=false \
  -p:SelfContained=false -p:AppxGeneratePriEnabled=false
```
Errors that start with `error CS` are real; one about `RemovePayloadDuplicates` is the Windows-only step.

## Test without a phone (any computer)
```
MACNDROID_CONFIG=/tmp/mnd MACNDROID_PORT=47901 MACNDROID_DOWNLOADS=/tmp/mnd/dl dotnet run --project windows/CoreTest -- 9 &
python3 tools/fake_phone.py --identity /tmp/mnd/identity.json --port 47901
```
