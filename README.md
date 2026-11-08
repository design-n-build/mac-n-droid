# Mac n Droid

Free, private continuity between an Android phone and a Mac, with no account and no cloud.

| | |
|---|---|
| **Mac app** | Native SwiftUI. Follows your Mac's appearance (light/dark, accent color, Liquid Glass) automatically. macOS 14+, Apple silicon and Intel. |
| **Android app** | Jetpack Compose + Material 3 with Material You dynamic color. Android 10+. |
| **Link** | Local Wi-Fi only, QR-code pairing, AES-256-GCM end-to-end encryption. See [PROTOCOL.md](PROTOCOL.md). |

## Features (v0.2)
- **Notifications on your Mac:** native macOS banners with the app's action buttons (Reply, Mark as read, Answer/Decline for calls). Dismiss on the Mac and it's dismissed on the phone.
- **Verification codes:** codes in notifications get a **Copy 482913** button (optionally auto-copied).
- **Clipboard:** copy on the Mac and it's on the phone. Phone → Mac via the Quick Settings tile, the status notification's **Send clipboard** button, or Share. Passwords from password managers are never sent.
- **Files both ways:** drag onto the Mac window or Dock icon, or **Share → Send to Mac** on the phone. Mac saves to `~/Downloads`, phone to `Downloads/Mac n Droid`.
- **Notch notifications:** phone notifications drop out of the MacBook notch, Dynamic Island style, with Copy code and Answer/Decline buttons. Macs without a notch get them at the top of the screen. You can switch back to standard banners in Settings.
- **Real app names and logos** (Gmail, Flipkart…) in the list, banners and notch.
- **Clipboard images both ways.** Copy a screenshot on the Mac and paste it on the phone, and the other way round.
- **Automatic phone → Mac clipboard (optional):** Settings → Accessibility → Mac n Droid clipboard sync. It reacts only to Copy actions.
- **Files** with real previews (photos, videos, PDFs) on both sides, Quick Look on the Mac, and a Files tab on Android.
- **Phone battery** in the Mac menu bar.
- **Ring my phone** at full volume, even on silent.
- Auto-reconnect: Bonjour discovery, reconnects after Wi-Fi changes, sleep or reboot.

## Install

### Mac
1. Download **MacNDroid-0.8.2.dmg** from [Releases](https://github.com/santosh-mudragada-1/mac-n-droid/releases/latest), open it and drag **Mac n Droid** to Applications.
2. First launch: the app isn't notarized yet, so macOS blocks it. Open **System Settings → Privacy & Security**, scroll down and click **Open Anyway**.
3. Allow **Local Network** and **Notifications** when asked.

### Android
1. On the phone, download **MacNDroid-0.8.5.apk** from [Releases](https://github.com/santosh-mudragada-1/mac-n-droid/releases/latest) and open it. Allow "install unknown apps" for the app you opened it from.
2. Open Mac n Droid → **Scan QR code** → scan the code on the Mac (**Pair a Phone**).
3. Tap **Allow** on each item in **Finish setup**.
   - **Notification access greyed out?** Sideloaded apps on Android 13+ need: Settings → Apps → Mac n Droid → ⋮ → **Allow restricted settings**, then try again.
   - On Xiaomi/Samsung/Oppo/OnePlus also set battery to **Unrestricted** and enable autostart (see dontkillmyapp.com).

## Build from source

```bash
# Mac app → mac/dist/Mac n Droid.app + .dmg (needs only Command Line Tools)
cd mac && ./scripts/build-app.sh 0.2.0

# Android APK → android/app/build/outputs/apk/release/app-release.apk
cd android
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleRelease

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
tools/      FakePhone.java protocol test client
docs/       Maintainer guide
PROTOCOL.md Wire protocol
RESEARCH.md Market research and roadmap
```

## Contributing
Bug reports and ideas are welcome: open an [issue](https://github.com/santosh-mudragada-1/mac-n-droid/issues/new/choose). See [CONTRIBUTING.md](CONTRIBUTING.md).

## License
Mac n Droid is free software under the [GNU General Public License v3.0](LICENSE). You can use,
study, change and share it; copies and changed versions you share must stay open source under the same license.

### Name and logo
The name **Mac n Droid** and the Mac n Droid logo are trademarks of Santosh Mudragada and are not
covered by the GPL. You're welcome to fork the code, but please give your version a different name
and logo so people don't confuse it with the official app. Using the name to refer to this project
(for example "works with Mac n Droid" or a review) is fine.

## Next up (from RESEARCH.md roadmap)
SMS view/send, screen mirroring, phone as webcam, call audio through the Mac, media controls, clipboard history.
