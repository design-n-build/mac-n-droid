# Mac n Droid for Windows (preview)

A WinUI 3 app (Windows App SDK) with Fluent design (Mica, the navigation pane, Settings-style cards), so it
looks like Windows 11's own apps. Windows 10 (version 2004) and later, on Intel/AMD (x64) and ARM PCs.

Same pages and flows as the Mac app: Overview, Notifications, Clipboard, Files, Pair a phone and Settings.
It keeps running in the notification area (next to the clock) after you close the window, like the Mac app
in the menu bar. Notifications use Windows' own notifications, with a Reply box.

**Works now:** pairing (QR code), phone battery, notifications with Reply and the app's own buttons,
clipboard both ways with history, files both ways, links both ways, ring your phone, open at sign-in.
**Not yet:** Messages, music controls, screen mirroring, the browser extension link.

## Install
Download `MacNDroid-x.y.z-Windows-x64.exe` (or `-arm64` for ARM PCs) and open it. The app isn't signed yet,
so Windows shows "Windows protected your PC": click **More info → Run anyway**. The first time it starts,
allow it on **Private networks** when Windows Firewall asks.

## Projects
- `Core/`: the protocol and app logic, plain .NET 8. Builds and runs on any computer.
- `CoreTest/`: runs the core without a window, for testing with `tools/FakePhone.java`.
- `App/`: the WinUI 3 app (built in C#, no XAML files). Builds on Windows; the GitHub Actions workflow
  `.github/workflows/desktop.yml` builds the installers.

## Test without a phone (any computer)
```
MACNDROID_CONFIG=/tmp/mnd MACNDROID_PORT=47901 dotnet run --project windows/CoreTest -- 30
java -Didentity=/tmp/mnd/identity.json tools/FakePhone.java 127.0.0.1 47901 --full
```
