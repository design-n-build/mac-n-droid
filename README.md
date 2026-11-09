# Mac n Droid

Free, private continuity between an Android phone and a Mac, with no account and no cloud.

| | |
|---|---|
| **Mac app** | Native SwiftUI. Follows your Mac's appearance (light/dark, accent color, Liquid Glass) automatically. macOS 14+, Apple silicon and Intel. |
| **Android app** | Jetpack Compose + Material 3 with Material You dynamic color. Android 10+. |
| **Link** | Local Wi-Fi only, QR-code pairing, AES-256-GCM end-to-end encryption. See [PROTOCOL.md](PROTOCOL.md). |

## Features

### Notifications
- **Phone notifications on your Mac:** native banners, or Dynamic Island–style drops from the MacBook notch (top of the screen on Macs without a notch). App names and logos (Gmail, WhatsApp…), and the app's own buttons: Reply, Mark as read, Answer/Decline for calls. Dismiss on the Mac and it's dismissed on the phone.
- **Reply right from the notch:** the text box is ready to type into as soon as you click Reply. Buttons that only open the phone are labelled "Reply on Phone".
- **No notification storms:** a burst of notifications (like WhatsApp re-posting every unread chat) plays one sound and shows only the first few.
- **Verification codes:** codes get a **Copy 482913** button, and can be copied automatically.
- **Live activities:** timers, ongoing calls, rides and deliveries stay live on the Mac while they run.

### Messages
- **Read and reply to texts from your Mac** (Messages page). Start new conversations with contact search. Google Messages chat (RCS) messages that arrive while connected also show up, and replies go back through the chat.

### Clipboard
- **Copy on one, paste on the other:** text and images, both ways. Automatic phone → Mac copying is optional (Settings → Accessibility → Mac n Droid clipboard sync). Passwords from password managers are never sent.
- **Clipboard history:** the last 20 copies from both devices, on both devices. Tap to copy again; clear it from either side.
- **Screenshots** taken on the phone are ready to paste on the Mac.

### Files and links
- **Files both ways:** drag onto the Mac window, Dock icon or the **notch**; on the phone use **Share → Send to <your Mac>**, the **Send files** button, or the **Send files to Mac** Quick Settings tile. Several files at once give one sound and one notification.
- **Link handoff:** share a link on the phone to open it in the Mac's browser. Send the current browser tab from the Mac with **⌃⌥P** (any browser) and it opens on the phone, right after you unlock it if it's locked.
- **Share sheet:** your Mac appears in the top row by name, with two actions. **Open on <your Mac>** opens a link in its browser; **Send to <your Mac>** sends files to Downloads and text or links to its clipboard. Icons follow the destination: black for the Mac, green for the Android phone.

### Music and media
- **Phone music on the Mac:** see and control what's playing on the phone.
- **Mac music on the phone:** play, pause and skip Music, Spotify and (with the browser extension) YouTube, YouTube Music, Spotify Web, SoundCloud and other sites, from the phone's media controls.

### Phone
- **Mirror and control your phone** in a Mac window (control needs the optional Mac n Droid phone control setting).
- **Phone as webcam** over USB (Android 14+).
- **Ring my phone** at full volume, even on silent. **Phone battery** in the menu bar.
- **Left-behind alerts:** a chime if you walk away from your Mac while it's awake.

### Browser extension (Helium, Chrome, Edge, Brave, Arc…)
- Control music and videos playing in the browser from your phone.
- Click the toolbar icon for **Send This Page to Phone**, or right-click a link for **Open Link on Phone**.
- Talks only to the Mac n Droid app on your own Mac (127.0.0.1); websites can't connect to it.

### Everything else
- **Automatic updates** for the Mac app (Sparkle), once the repo is public.
- Native look on both sides: macOS design on the Mac, Material 3 with your wallpaper colours on Android.
- Auto-reconnect after Wi-Fi changes, sleep or reboot. No account, no cloud.

## Install

### Mac
1. Download the latest **MacNDroid-x.y.z.dmg** from [Releases](https://github.com/santosh-mudragada-1/mac-n-droid/releases/latest), open it and drag **Mac n Droid** to Applications.
2. First launch: the app isn't notarized yet, so macOS blocks it. Open **System Settings → Privacy & Security**, scroll down and click **Open Anyway**.
3. Allow **Local Network** and **Notifications** when asked.

### Android
1. On the phone, download the latest **MacNDroid-x.y.z.apk** from [Releases](https://github.com/santosh-mudragada-1/mac-n-droid/releases/latest) and open it. Allow "install unknown apps" for the app you opened it from.
2. Open Mac n Droid → **Scan QR code** → scan the code on the Mac (**Pair a Phone**).
3. Tap **Allow** on each item in **Finish setup**.
   - **Notification access greyed out?** Sideloaded apps on Android 13+ need: Settings → Apps → Mac n Droid → ⋮ → **Allow restricted settings**, then try again.
   - On Xiaomi/Samsung/Oppo/OnePlus also set battery to **Unrestricted** and enable autostart (see dontkillmyapp.com).

### Browser extension (optional)
1. On the Mac, open **Mac n Droid → Settings → Sync → Browser → Show Extension in Finder**.
2. In your browser, open `chrome://extensions` (Helium: `helium://extensions`), turn on **Developer mode**, click **Load unpacked** and choose the **Browser Extension** folder.
3. Pin it from the puzzle-piece menu. After a Mac app update, click ↻ on the extension to load the new version.

## Build from source

```bash
# Mac app → mac/dist/Mac n Droid.app + .dmg (needs only Command Line Tools)
cd mac && ./scripts/build-app.sh 0.10.4

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
browser-extension/  Browser extension (Manifest V3), bundled into the Mac app
tools/      FakePhone.java protocol test client, icon and sound generators
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
Call audio through the Mac, browsing phone photos from the Mac, Do Not Disturb sync, hotspot in one click,
the browser extension in the Chrome Web Store, and a Play Store listing.
