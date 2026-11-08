# Mac n Droid — Product Research Brief

*Prepared 7 Nov 2026. Goal: a free app that gives Android + Mac the same "it just works" experience iPhone + Mac has, and later extends to iPhone, iPad and Windows.*

---

## 1. Competitive landscape

| Product | Price | Platforms | What it does well | Gaps |
|---|---|---|---|---|
| **LinkMyDroid / LinkMyMac** | Mac app paid (one-time); Android client in closed test | Android ↔ macOS 15.5+ | File transfer, screen mirroring + control, phone as webcam, notifications with reply, USB or Wi-Fi | Paid, closed, Android client not widely available |
| **KDE Connect** | Free, open source (GPL) | Android, Linux, Windows, macOS (macOS only as **unstable nightly**) | Notifications, clipboard, SMS, file transfer, ring phone, remote input, media control, run commands | Mac build is unpolished and not "Mac-native"; clipboard is clunky |
| **AirSync** | Free, MIT, GitHub (sameerasw) | Android ↔ macOS 14.5+ | Notification mirroring, two-way clipboard, scrcpy-based mirroring | Hobby-grade; README says encryption not yet implemented |
| **Bounce Connect** | Paid | Android ↔ macOS | Local Wi-Fi only, private, clipboard incl. images | Paid, proprietary |
| **Droid2PC** | Newer | Android ↔ Mac/PC | LAN **and** internet via WebRTC P2P | Unproven |
| **HandsFree 2 / Call Connector** | Paid | Mac + any Bluetooth phone | Answer/make calls on Mac via Bluetooth hands-free, no phone app | Calls only; flaky on newer macOS |
| **NearDrop** | Free, open source | macOS | Receives Google Quick Share files on Mac | Receive-only, files only |
| **LocalSend** | Free, open source | All | Cross-platform file transfer | Files only |
| **scrcpy / Vysor** | Free / freemium | All desktops | Best-in-class mirroring + control (via ADB) | Needs USB debugging; not consumer-friendly |
| **Microsoft Phone Link** | Free | Android ↔ **Windows only** | Calls, SMS, photos, apps | No Mac. Reviews criticize lag |
| **Google Quick Share ↔ AirDrop** | Built in | Pixel 8a+ and some Samsung/Oppo/OnePlus/Xiaomi/Honor/Motorola | Android can AirDrop files to Mac/iPhone | Files only, limited phone list, no official Mac app yet |

**Takeaway:** nobody ships a **free, polished, Mac-native, all-in-one** app. KDE Connect is free but rough on Mac. The paid apps are polished but narrow or closed. That gap is your opening.

**Positioning:** *"Continuity for Android. Free, private, local-first, open."*

---

## 2. iPhone ↔ Mac Continuity features and Android feasibility

Each Apple feature, how close Android + a third-party Mac app can get, and what limits it.

| Apple feature | Feasibility | How to build it / what limits it |
|---|---|---|
| **Notifications on Mac** (iPhone Mirroring notifications) | ✅ Full | Android `NotificationListenerService`. Show them as native macOS notifications with actions (reply, mark read, dismiss syncs both ways). On Android 15+, OTP contents are redacted for untrusted listeners. Registering as a **Companion Device app** (`CompanionDeviceManager`) is the route to trusted status and background exemptions. |
| **Universal Clipboard** | ⚠️ Partial | Mac → Android: works automatically. Android → Mac: **Android 10+ blocks background clipboard reads.** Workarounds: share-sheet target, Quick Settings tile, "copy" notification action, or an optional custom keyboard (IME), which can see its own copies. KDE Connect and AirSync hit the same wall. |
| **AirDrop** | ✅ Full | Custom fast transfer over LAN. Add a Finder Share extension, drag onto the menu bar icon, and an Android share-sheet target. Optionally interoperate with the Quick Share protocol, as NearDrop does. |
| **Phone calls on Mac** | ⚠️ Partial | *Call control* (caller ID popup, answer, decline, dial from Mac): `TelecomManager` plus `ANSWER_PHONE_CALLS` / `CALL_PHONE`. *Call audio:* Android won't let third-party apps capture cellular call audio, so audio has to go over **Bluetooth Hands-Free (HFP)** with the Mac as the headset (`IOBluetoothHandsFreeDevice`, which is how HandsFree 2 works). This is the hardest feature, so ship it after the MVP. |
| **Messages / SMS on Mac** | ⚠️ Policy-gated | Read and send SMS. Needs `READ_SMS` / `SEND_SMS`. Google Play allows these only for approved exceptions. **"Connected device companion apps that send/receive SMS or calls" is an allowed exception**, but you must submit a Permissions Declaration and Google reviews it case by case. RCS isn't reachable by third parties; RCS messages show up only through their notifications. |
| **iPhone Mirroring** | ✅ Full | Two engines: (a) **no-setup**: `MediaProjection` H.264/H.265 stream, with control through an `AccessibilityService` (Play-restricted, needs a declaration and prominent disclosure); (b) **power mode**: wireless ADB + scrcpy-style server for full control, app windows and virtual displays. Ship (a) by default and (b) as an "advanced" toggle. |
| **Continuity Camera** (phone as webcam) | ✅ Full | Android Camera2 → H.264 stream → **macOS Camera Extension** (CoreMediaIO). It then appears as a real camera in FaceTime, Zoom and Meet. Also: phone as microphone, Desk View-style ultra-wide crop. |
| **Handoff** | ⚠️ Partial | No OS-level hooks, so build it as "Send to Mac / Send to Phone": open the current URL, map location, YouTube timestamp, or file. A browser extension on the Mac side can detect the active tab. |
| **Instant Hotspot** | ⚠️ Limited | Since Android 8, third-party apps can't turn on the normal tethering hotspot. Options: deep-link the phone to hotspot settings from the Mac menu bar, offer `LocalOnlyHotspot` (no internet) for transfers, or use Shizuku/ADB in power mode. |
| **Auto Unlock** (Watch unlocks Mac) | ⚠️ Risky | No public macOS API. Possible via BLE proximity plus a stored credential or an authorization plugin, but security-sensitive. Ship later, opt-in, after an audit. **Proximity Lock** (lock the Mac when the phone walks away) is easy and safe. |
| **Sidecar / Universal Control** | ✅ Possible | Use an Android tablet as a second Mac display through a virtual display (`CGVirtualDisplay`, private API, or a DriverKit display driver) plus a stream. Mac keyboard/mouse controlling the phone comes from the mirroring engine. |
| **Find My** | ✅ Basic | Ring the phone (even on silent), show its last-known location, show the Mac's location on the phone. |
| **Live Activities on Mac** | ✅ Full | Mirror *ongoing* notifications (Uber, food delivery, timers, navigation, music) into a **menu-bar / notch "island"** on the Mac. |
| **Keychain OTP autofill** | ✅ Full | Detect OTP codes in SMS and notifications, show "Copy 482913" on the Mac, and paste it in one keystroke. Users notice this one quickly. |
| **Photos sync** | ✅ Full | Recent-photos strip in the Mac app, auto-import new screenshots, drag a photo straight into any Mac app. |

---

## 3. Features beyond Apple

Apple doesn't offer these, or Android makes them uniquely possible:

**Everyday "wow"**
1. **Clipboard history**, synced across devices (text, images, links), searchable from the Mac menu bar.
2. **Screenshot drop:** take a screenshot on the phone and it appears on the Mac desktop or clipboard instantly.
3. **OTP and 2FA catcher**, described above.
4. **Notch / menu-bar island** for calls, timers, deliveries, music and transfers in progress.
5. **Battery and status in the menu bar:** phone battery, signal and charging, plus "phone fully charged" and "low battery" alerts.
6. **Media remote both ways:** control Mac Spotify/YouTube from the phone, and phone media from the Mac's media keys and Control Center.
7. **Auto-pause / auto-mute:** an incoming call pauses Mac media, and Mac Focus mode mirrors to phone Do Not Disturb, both ways.
8. **Smart notification digest:** on-device summarization and grouping, rules ("show WhatsApp, hide games"), and per-app mute on Mac only.

**Power-user**
9. **Run Android apps as Mac windows** (scrcpy virtual display / Android desktop mode), pinned to the Dock.
10. **Phone as trackpad, keyboard, presentation clicker, or Touch Bar** for the Mac (Keynote/PowerPoint remote, volume and brightness sliders).
11. **Control the Mac from the phone:** screen view, lock/sleep/shutdown, run Shortcuts.
12. **Shortcuts.app plus Tasker/Automate integration:** "When phone connects to car, lock Mac", "When I copy an address on Mac, open it in Maps on phone".
13. **Browse the phone's storage in Finder** (File Provider extension), the way a USB drive mounts, but wireless.
14. **Wake-on-LAN and remote access** away from home via an encrypted P2P relay.
15. **Universal search:** search phone contacts, messages and files from Spotlight or the Mac app.
16. **Shared "drop zone"** in the Mac menu bar and an Android widget for quick sends.
17. **Phone as a second screen / wireless monitor** (tablets especially).
18. **Developer mode:** logcat viewer, APK drag-to-install, wireless ADB pairing in one click. Developers are a strong early-adopter audience.

**Trust features (differentiators for a free app)**
19. **Local-first, no account required.** Data never touches a server unless you turn on remote access.
20. **End-to-end encryption** with QR-code pairing and visible device fingerprints.
21. **Open source** so people can verify the "free and private" claim. This also builds community and contributors.

---

## 4. Recommended architecture

```
┌────────────── Android (Kotlin + Jetpack Compose) ──────────────┐
│ CompanionDeviceManager · NotificationListener · Telecom · SMS   │
│ MediaProjection · Camera2 · Accessibility (opt) · FGS           │
└──────────────────────────────┬─────────────────────────────────┘
                     Shared protocol core (Rust)
          discovery · pairing · crypto · framing · file xfer
          → bindings via UniFFI to Kotlin, Swift, C# (Windows later)
┌──────────────────────────────┴─────────────────────────────────┐
│ macOS (Swift + SwiftUI + AppKit) — menu-bar app                 │
│ UserNotifications · Share ext · File Provider · Camera Ext      │
│ IOBluetooth HFP · ScreenCaptureKit · VideoToolbox (decode)      │
└────────────────────────────────────────────────────────────────┘
```

**Why this stack**
- **Native UI on each OS.** A Mac-native feel is the whole product, and Electron/Flutter would undercut it. SwiftUI code carries over to iPhone/iPad later.
- **One Rust core** for networking, crypto and the protocol, written once and reused on Android, Mac, iOS and Windows. It prevents protocol drift between platforms.

**Transport**
- **Discovery:** mDNS/Bonjour (`NsdManager` on Android) plus BLE advertising for proximity and fast reconnects.
- **Link:** TLS 1.3 or Noise over TCP/QUIC on the LAN. USB (ADB) fallback for mirroring. Wi-Fi Direct for networks without a router.
- **Remote (optional):** WebRTC P2P with a small STUN/TURN relay. A relay costs money, so keep it optional and self-hostable.
- **Pairing:** QR code shown on the Mac, scanned by the phone. Exchanges long-term keys, so there's no account.

**Android must-dos**
- Use `CompanionDeviceManager` to associate the Mac. It grants background-start and battery exemptions and presence callbacks.
- Run a foreground service of type `connectedDevice` (Android 14+ requires the type).
- Handle OEM battery killers (Xiaomi, Samsung, Oppo, Huawei) with guided setup screens (see dontkillmyapp.com).
- Ship feature modules so that SMS, calls and accessibility permissions are opt-in and declared.

**Mac distribution**
- **Direct download, notarized, plus Homebrew cask** as the main channel. Accessibility, Bluetooth HFP and virtual-display features fight the App Store sandbox.
- Optionally a lighter Mac App Store edition later for discoverability.

---

## 5. Phased roadmap

| Phase | Scope | Notes |
|---|---|---|
| **v0.1 MVP** (~8–10 wks) | QR pairing, E2E encrypted LAN link, notifications (view/dismiss/reply), clipboard (Mac→Android auto, Android→Mac via tile/share), file and photo transfer, battery in menu bar, ring my phone, media controls | Core daily-use loop; all Play-safe permissions |
| **v0.2** | SMS read/send + OTP catcher, call alerts + answer/decline/dial, clipboard history, screenshot drop | Submit Play SMS/Call Log declaration early, since review takes time |
| **v0.3** | Screen mirroring + control (MediaProjection + optional ADB power mode), phone as webcam/mic (Camera Extension) | Biggest "wow" release, good for a launch on Product Hunt, Reddit and Hacker News |
| **v0.4** | Call audio over Bluetooth HFP, notch island / Live-Activity mirroring, Focus/DND sync, proximity lock | |
| **v1.0** | Android apps as Mac windows, Finder File Provider, Shortcuts/Tasker automations, remote access relay, tablet as second display | |
| **v2 – iPhone/iPad** | iOS sandbox is far stricter: no notification access, no SMS, limited background. Realistic scope: files, clipboard, webcam, Mac control, photo sync. **iPhone ↔ Windows/Android** is the more valuable direction, since Apple already covers iPhone ↔ Mac. A desktop can read iPhone notifications via **ANCS over BLE** (how Intel Unison and Phone Link do it). | Reuse Rust core + SwiftUI |
| **v3 – Windows** | WinUI 3 / C# shell on the Rust core | Competes with Phone Link; differentiate on iPhone support and privacy |

---

## 6. Risks and constraints

1. **Google Play permission review** for SMS, Call Log and Accessibility is the top risk. Mitigate by keeping these in optional modules, writing a precise declaration and demo video, and offering an F-Droid / GitHub APK build with everything enabled.
2. **Android 10+ clipboard restriction:** set expectations in the UI and make the workaround one tap.
3. **OEM background killing** causes most "it stopped working" reviews in this category. Invest in onboarding.
4. **Call audio (HFP) on macOS** is fragile across macOS versions, as competitor reviews show. Treat it as beta.
5. **Private macOS APIs** (virtual display, auto-unlock) can break in updates. Isolate them behind feature flags.
6. **Running costs of a free app:** Apple Developer $99/yr, Google Play $25 one-time, an optional TURN relay at a few $/month that scales with users. Fund it with GitHub Sponsors or Open Collective, and optionally a paid hosted relay while keeping self-hosting free.
7. **Naming and trademarks:** avoid "AirDrop", "Continuity" and "Link My…" in branding. Check that "Mac n Droid" is clear of trademarks. Apple restricts "Mac" in app names on the App Store, so consider a neutral brand.

---

## 7. Sources

- LinkMyDroid on AlternativeTo — https://alternativeto.net/software/linkmydroid/about
- LinkMyMac on APKPure — https://m.apkpure.com/linkmymac/com.kdg.beam_android
- KDE Connect (Wikipedia) — https://en.wikipedia.org/wiki/KDE_Connect
- KDE Connect macOS feature list (OMG Ubuntu) — https://www.omgubuntu.co.uk/?p=146891
- AirSync (GitHub, sameerasw) — https://awesome.ecosyste.ms/projects/github.com%2Fsameerasw%2Fairsync-mac
- Bounce Connect — https://alternativeto.net/software/bounce-connect/about
- Droid2PC — https://dev.to/droid2pc/why-android-and-mac-still-feel-disconnected-and-what-we-built-instead-with-droid2pc-1d72
- Google Play SMS/Call Log policy — https://support.google.com/googleplay/android-developer/answer/10208820 and https://support.google.com/googleplay/android-developer/answer/17225965
- Android notification access docs — https://source.android.com/docs/automotive/hmi/notifications/notification-access
- Quick Share ↔ AirDrop expansion (MacRumors) — https://www.macrumors.com/2026/06/02/google-airdrop-support-more-android-phones/
- Apple IOBluetoothHandsFreeDevice — https://developer.apple.com/documentation/iobluetooth/iobluetoothhandsfreedevice
- HandsFree 2 — https://alternativeto.net/software/handsfree-2/about
- Phone Link comparison (Android Authority) — https://www.androidauthority.com/iphone-mirroring-vs-phone-link-3464907
