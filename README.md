# Mac n Droid

Free, private continuity between an Android phone and a Mac (or, in beta, a Windows or Linux PC), with no
account and no cloud.

| | |
|---|---|
| **Mac app** | Native SwiftUI. Follows your Mac's appearance (light/dark, accent color, Liquid Glass) automatically. macOS 14+, Apple silicon and Intel. |
| **Android app** | Jetpack Compose + Material 3 with Material You dynamic color. Android 10+. |
| **Windows app** (beta) | WinUI 3 with Fluent design (Mica, Settings-style cards). Windows 10 2004+ and 11, x64 and ARM. See [windows/](windows/README.md). |
| **Linux app** (beta) | GTK 4 + libadwaita (GNOME design). Flatpak for x86_64 and ARM, or from source. See [linux/](linux/README.md). |
| **Link** | Local Wi-Fi only, QR-code pairing, AES-256-GCM end-to-end encryption. See [PROTOCOL.md](PROTOCOL.md). |

## Features
What changed in each version: [CHANGELOG.md](CHANGELOG.md).

### Clipboard
- **Copy on one, paste on the other:** text and images, both ways. Automatic copying from the phone is optional (Settings → Accessibility → Mac n Droid clipboard and controls). Passwords from password managers are never sent.
- **Clipboard history:** the last 20 copies from both devices, on both devices. Tap to copy again, or clear it from either side.

### Files
- **Phone → Mac:** **Share → Send to Mac**, the **Send files** button (files or a whole folder), or the **Send files to Mac** Quick Settings tile. Files land in Downloads.
- **Mac → phone:** drag files or folders onto the Mac window, the Dock icon or the **notch**, or use **Share → Send to Phone** in Finder, Photos, Preview and any app with a Share button.

### Links
- **Open on Mac:** share a link on the phone with **Open on Mac** and it opens in the Mac's browser.
- **Open on your phone:** press **⌃⌥P** on the Mac, or use **Share → Send to Phone**, to open the current page on the phone. If the phone is locked, it opens when you unlock it.

### Screenshots
- Screenshots taken on the phone appear on the Mac straight away, ready to paste.

### Browser extension (Chrome, Edge, Firefox, Brave, Arc and other Chrome-based browsers)
- Control music and videos playing in the browser from your phone.
- Open the page you're reading, or any link, on your phone from the toolbar button or the right-click menu.
- Talks only to the Mac n Droid app on your own Mac; websites can't connect to it.

### Music and media
- **Mac music on the phone:** play, pause and skip Music, Spotify and, with the browser extension, YouTube and other sites, from the phone's media controls.
- **Phone music on the Mac:** see and control what's playing on the phone.

### Notifications
- **Phone notifications on your Mac**, as native banners or drops from the MacBook notch, with the app's own actions: Reply, Mark as read, Answer and Decline for calls. Dismiss on the Mac and it's dismissed on the phone.
- **Verification codes** can be copied with one click, or automatically.
- **Live activities:** timers, calls, rides and deliveries stay live next to the notch.

### Phone
- **Phone battery** in the menu bar.
- **Ring my phone** at full volume, even on silent.
- **Left-behind alerts** when your phone leaves the Wi-Fi network your Mac is on, or your Mac joins another network without your phone. They're based on Wi-Fi, not distance, so they come once you're out of Wi-Fi range.

### Messages
- **Read and reply to texts from your Mac,** and start new conversations with contact search.

### Mirroring and webcam
- **Mirror and control your phone** in a Mac window (control needs the optional clipboard and controls setting).
- **Phone as webcam** over USB (Android 14+).

### Everything else
- **Automatic updates** for the Mac app.
- Native look on both sides: macOS design on the Mac, Material 3 with your wallpaper colours on Android.
- Reconnects by itself after Wi-Fi changes, sleep or restart. No account, no cloud.

## Install

### Mac
1. Download the latest **MacNDroid-x.y.z.dmg** from [Releases](https://github.com/design-n-build/mac-n-droid/releases/latest), open it and drag **Mac n Droid** to Applications.
2. First launch: the app isn't notarized yet, so macOS blocks it. Open **System Settings → Privacy & Security**, scroll down and click **Open Anyway**.
3. Allow **Local Network** and **Notifications** when asked.

### Android
1. On the phone, download the latest **MacNDroid-x.y.z.apk** from [Releases](https://github.com/design-n-build/mac-n-droid/releases/latest) and open it. Allow "install unknown apps" for the app you opened it from.
   - **"App blocked to protect your device"?** Google Play Protect blocks apps from outside the Play Store that ask for SMS, notification or accessibility access. Open the **Play Store** → your profile picture → **Play Protect** → ⚙ → turn off **Scan apps with Play Protect**, install Mac n Droid, then turn it back on. Mac n Droid keeps working.
2. Open Mac n Droid → **Scan QR code** → scan the code on the Mac (**Pair a Phone**).
3. Tap **Allow** on each item in **Finish setup**.
   - **Notification access greyed out?** Sideloaded apps on Android 13+ need: Settings → Apps → Mac n Droid → ⋮ → **Allow restricted settings**, then try again.
   - On Xiaomi/Samsung/Oppo/OnePlus also set battery to **Unrestricted** and enable autostart (see dontkillmyapp.com).

### Windows and Linux (beta)
The Windows and Linux apps are new, so there may be a few bugs; we're working on them. Please
[report anything that doesn't work](https://github.com/design-n-build/mac-n-droid/issues/new?template=bug_report.yml).

Download the Windows installer (`-x64.exe` for most PCs, `-arm64.exe` for ARM laptops) or the Linux Flatpak from
[Releases](https://github.com/design-n-build/mac-n-droid/releases/latest). The Windows app isn't signed yet, so
Windows shows "Windows protected your PC": click **More info → Run anyway**. Then follow [windows/README.md](windows/README.md) or [linux/README.md](linux/README.md). Testers: see
[docs/TESTING-WINDOWS-LINUX.md](docs/TESTING-WINDOWS-LINUX.md).

### Browser extension (optional)
Works in Chrome, Edge, Firefox, Brave, Arc and other Chrome-based browsers, with the Mac, Windows or Linux app.
1. On the Mac, click **Browser Extension** in Mac n Droid's sidebar (or **Settings → Sync → Browser → Add to Browser…**).
2. Click **Add to Chrome-Based Browser** (Chrome Web Store) or **Add to Firefox-Based Browser** (Firefox Add-ons). The store opens in the browser you're using; click **Add** there.

Developers can load it from source instead: open the browser's Extensions page (`chrome://extensions`), turn on **Developer mode**, click **Load unpacked** and choose the `browser-extension` folder.

## Build from source

```bash
# Mac app → mac/dist/Mac n Droid.app + .dmg (needs only Command Line Tools)
cd mac && ./scripts/build-app.sh 0.14.0

# Android APK → android/app/build/outputs/apk/release/app-release.apk
cd android
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleRelease

# Android App Bundle for Google Play → android/app/build/outputs/bundle/release/app-release.aab
./gradlew bundleRelease

# Protocol test: acts like a phone against the running Mac app
java tools/FakePhone.java            # handshake only (nothing appears on the Mac)
java tools/FakePhone.java 127.0.0.1 47800 --full   # also sends test notifications, clipboard and a file
java tools/FakePhone.java 127.0.0.1 47800 --wrong-key   # should be rejected
```

### Signing keys: back these up
- `android/keystore/release.jks` + `android/keystore.properties` sign the APK. **Updates only install over earlier versions if they're signed with the same key.** Keep a copy somewhere safe and never commit it (it's git-ignored).
- Mac: builds are ad-hoc signed. To ship without the "Open Anyway" step, join the Apple Developer Program ($99/yr), then `SIGN_ID="Developer ID Application: …" ./scripts/build-app.sh` and notarize with `xcrun notarytool` (needs full Xcode).

### Stable signing (stops macOS re-asking for permissions after every rebuild)
Ad-hoc builds get a new signature each time, so macOS asks for **Local Network** access again, and
the phone can't connect until you click **Allow**. To avoid that, create a local signing certificate
once. Run this yourself, since it adds a certificate to your login keychain:

```bash
cd mac && mkdir -p signing && cd signing
openssl req -x509 -newkey rsa:2048 -keyout key.pem -out cert.pem -days 3650 -nodes \
  -subj "/CN=Mac n Droid Local Signing" \
  -addext "keyUsage=critical,digitalSignature" -addext "extendedKeyUsage=critical,codeSigning"
openssl pkcs12 -export -legacy -out id.p12 -inkey key.pem -in cert.pem -passout pass:macndroid
security import id.p12 -k ~/Library/Keychains/login.keychain-db -P macndroid -T /usr/bin/codesign
```
`build-app.sh` then picks it up automatically. To undo it, delete "Mac n Droid Local Signing" in Keychain Access.

## Hosting the download
`site/index.html` is a simple download page. Upload the DMG and APK to a GitHub Release, update the two links in the page, and publish `site/` with GitHub Pages (or any static host).

## Project layout
```
mac/        SwiftUI app (Swift Package) + build scripts
android/    Kotlin/Compose app (Gradle)
browser-extension/  Browser extension (Manifest V3), bundled into the Mac app; tools/package-extension.sh
                    makes the Chrome, Edge and Firefox store zips
windows/            Windows app (WinUI 3, C#): Core/ is the protocol and logic, App/ the window
linux/              Linux app (GTK 4 + libadwaita, Python)
tools/fake_phone.py A fake phone that tests the Windows and Linux apps end to end
tools/      FakePhone.java protocol test client, icon and sound generators
docs/       Maintainer guide
PROTOCOL.md Wire protocol
RESEARCH.md Market research and roadmap
```

## Contributing
Bug reports and ideas are welcome: open an [issue](https://github.com/design-n-build/mac-n-droid/issues/new/choose). See [CONTRIBUTING.md](CONTRIBUTING.md).

## License
Mac n Droid is free software under the [GNU General Public License v3.0](LICENSE). You can use,
study, change and share it; copies and changed versions you share must stay open source under the same license.

### Name and logo
The name **Mac n Droid** and the Mac n Droid logo are trademarks of Santosh Mudragada and are not
covered by the GPL. You're welcome to fork the code, but please give your version a different name
and logo so people don't confuse it with the official app. Using the name to refer to this project
(for example "works with Mac n Droid" or a review) is fine.

## Next up (from RESEARCH.md roadmap)
Call audio through the Mac, browsing phone photos from the Mac, Do Not Disturb sync, hotspot in one click,
the browser extension in the Chrome Web Store, and a Play Store listing.

For file sharing: a free-space check before sending (so a file that won't fit says so up front instead of
failing partway), and transfers that pick up where they stopped if the Wi-Fi drops.
