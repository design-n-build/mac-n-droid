# Google Play listing

Everything to paste into Play Console for Mac n Droid. The sections follow the order of Play Console's
menus. Text limits are noted next to each field.

---

## Main store listing (Grow users → Store presence → Main store listing)

**App name** (30 max)

```
Mac n Droid
```

**Short description** (80 max)

```
Your Android phone and your Mac, together: notifications, texts, files and more
```

**Full description** (4000 max)

```
Mac n Droid connects your Android phone to your Mac, the way an iPhone works with a Mac. Free, open source, and private: your phone and your Mac talk directly over your own Wi-Fi, with no account, no cloud and no ads.

NOTIFICATIONS ON YOUR MAC
See your phone's notifications on your Mac as they arrive, right in the MacBook notch or as regular banners. Reply to messages and dismiss notifications without picking up your phone. Verification codes can be copied for you automatically.

TEXT MESSAGES
Read and reply to your text messages from the Mac. Start new conversations and search your contacts.

CALLS
See who's calling on your Mac and answer or decline from there.

CLIPBOARD
Copy on one device, paste on the other. The last 20 things you copied are kept on both, so you can copy them again. Passwords from password managers are never sent.

FILES, PHOTOS AND LINKS
Send files, photos and whole folders to your Mac from the app, the share sheet or a Quick Settings tile. On the Mac, drag files or folders onto the notch or use the Share menu to send them to your phone. Share a link with "Open on Mac" to open it in your Mac's browser, and send the page you're reading on the Mac to your phone.

SCREENSHOTS
Screenshots you take on your phone can appear on your Mac straight away.

MUSIC
Control music playing on your Mac from your phone's media controls, including Music, Spotify and, with the free browser extension, YouTube and other sites.

SCREEN MIRRORING
See your phone's screen in a window on your Mac. With the optional phone control setting, click, scroll and type to use your phone from the Mac.

AND MORE
• Phone battery on your Mac
• Ring your phone from the Mac to find it
• Live activities for timers, rides, deliveries and downloads next to the notch
• An alert when you leave Wi-Fi range of your Mac, so you don't leave it behind

PRIVATE BY DESIGN
• No account and no sign-in
• No servers: your devices connect directly on your Wi-Fi
• Everything is encrypted with a key only your two devices know
• No ads, no tracking, no analytics
• Open source, so anyone can check

YOU'LL NEED
• The free Mac n Droid app on a Mac with macOS 14 or later, from the Mac n Droid website
• Your phone and Mac on the same Wi-Fi network

Every feature is optional. Mac n Droid asks for each permission only when you turn on the feature that needs it, and explains why first.

Mac n Droid isn't affiliated with Apple or Google. Mac is a trademark of Apple Inc.
```

**App category:** Tools · **Tags:** Productivity, Utilities, File transfer

**Developer name:** Design n Build Studio

**Contact details:** email **help.macndroid@gmail.com** (shown publicly). The website is the Mac n Droid site.

**Graphics**
- App icon: 512 × 512 PNG, no transparency (`android/` launcher icon at 512 px).
- Feature graphic: 1024 × 500 PNG or JPG.
- Phone screenshots: 2 to 8, 1080 × 1920 or larger, 9:16. Suggested set: Home, notification on the Mac notch,
  Messages on the Mac, share sheet with Open on Mac / Send to Mac, Clipboard history, Files.

---

## Privacy policy (Policy → App content → Privacy policy)

The URL of `site/privacy.html` once the site is online, for example `https://<site>/privacy.html`.

---

## App access (Policy → App content → App access)

Choose **All or some functionality is restricted**, then add these instructions:

```
Mac n Droid is a companion app for a Mac. Most features need the free Mac n Droid Mac app (macOS 14 or later) on the same Wi-Fi network. No account or login is needed.

To test:
1. Install Mac n Droid on a Mac from <site URL>.
2. On the Mac, open Mac n Droid and click Pair a Phone. A QR code appears.
3. On the phone, open Mac n Droid and tap Scan QR code.
4. Follow the "Finish setup" list on the phone's Home screen to turn on each feature.

If you can't use a Mac, this video shows every feature working with a phone and a Mac: <video URL>
```

---

## Ads

**Does your app contain ads?** No.

---

## Content rating (IARC questionnaire)

- **Category:** All other app types (utility, productivity, communication or other).
- Violence, sexuality, language, controlled substances, crude humour: **No** to all.
- Gambling or simulated gambling: **No**.
- **Does the app allow users to interact or exchange content with other users?** **Yes**. You can send
  text messages to your contacts from the Mac. This adds the "Users Interact" note but keeps the rating at
  Everyone, PEGI 3.
- Does the app share the user's location with other users? **No**.
- Does the app allow purchases of digital goods? **No**.
- Is the app a web browser or search engine? **No**.
- Is the app primarily news or educational? **No**.

Expected result: **Everyone / PEGI 3 / USK 0**, with "Users Interact".

---

## Target audience and content

- **Target age groups:** 18 and over. This keeps Mac n Droid out of the Families program, which has
  extra rules about SMS and accessibility.
- **Could the app unintentionally appeal to children?** No.

---

## Data safety

Background: Play counts data as *collected* only when it leaves the device and the developer or another
company can read it. Mac n Droid's own data goes only to the user's own Mac, end-to-end encrypted with a key
that only those two devices hold. Google's guidance says end-to-end encrypted data that only the sender and
recipient can read doesn't need to be disclosed.

The one exception is Google's QR code scanner (ML Kit, in Google Play services). It sends basic diagnostics
(device model, Android version, error codes) to Google. See
https://developers.google.com/ml-kit/android-data-disclosure.

**Answers:**

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all user data collected by your app encrypted in transit? | **Yes** |
| Do you provide a way for users to request that their data is deleted? | **No**. The developer holds no user data; only Google's diagnostics. |
| Data types | **App info and performance → Diagnostics** only |
| Diagnostics: collected or shared? | **Collected**, not shared |
| Processed ephemerally? | No |
| Required or optional? | Required (used when you scan the pairing code) |
| Purpose | **App functionality**, **Analytics** |

---

## Government apps, financial features, health, news

No to all.

---

## Sensitive permission declarations

Play asks for each of these when you upload an App Bundle that includes them. Each needs a short video
(an unlisted YouTube link is fine) that shows the feature in use, starting with the in-app explanation.

### SMS (READ_SMS, SEND_SMS)

- **Core functionality:** Cross-device synchronization or transfer of SMS or calls.
- **Description:**

```
Mac n Droid shows the user's text messages on their own paired Mac and lets them reply and start new conversations from the Mac. Messages are read on the phone and sent directly to the user's Mac over their local Wi-Fi, end-to-end encrypted with a key created when they paired the two devices. They're never sent to the developer or to any server. Messages is an optional feature: the app asks for SMS access only when the user opens Messages on the Mac or turns it on.
```

- **Video:** turn on Messages → permission prompt → message appears on the Mac → reply from the Mac → reply
  arrives on the other phone.

### Accessibility services

Mac n Droid has two optional services. Neither is an accessibility tool, so declare
`isAccessibilityTool = false` for both. Both are explained in a dialog in the app before Android's
settings open.

**Mac n Droid clipboard sync**

```
Since Android 10, apps can read the clipboard only while they're in the foreground. This optional service detects when the user taps Copy in any app, then sends what they copied to their own paired Mac, so copying on the phone and pasting on the Mac works. It reacts only to copy events and the name of the open app; it doesn't read screen content and can't perform any action (no gestures, taps or typing). Copied text is sent only to the user's Mac, end-to-end encrypted, and never to the developer or any server. Passwords copied from password managers are never sent. Before Android's settings open, the app shows a dialog explaining exactly what the service does.
```

**Mac n Droid phone control**

```
Lets the user operate their own phone from a screen-mirroring window on their paired Mac: clicks on the Mac become taps, and the user can scroll, type and use Back, Home and Recents. The service performs gestures only in response to the user's own input in that window on their Mac, while mirroring is active. It doesn't read or collect screen content; the mirrored screen is shown live on the Mac and never recorded. Before Android's settings open, the app shows a dialog explaining what the service does and that it can be turned off at any time.
```

- **Videos:** for each one, the in-app dialog → Android settings → turning it on → the feature working.

> **Risk:** Google often rejects accessibility services that aren't for people with disabilities. Phone
> control (remote control from a computer) is an accepted kind of use. Clipboard sync is the most likely to
> be rejected. If it is, publish the Play version without it.

### Foreground service types

- **connectedDevice**

```
Keeps the encrypted connection to the user's paired Mac open so phone notifications, calls and messages appear on the Mac straight away. The service runs only while the phone is paired with a Mac, and shows an ongoing notification. Stopping it would mean notifications and calls reach the Mac late or not at all.
```

- **mediaProjection**

```
Used only for screen mirroring, which the user starts from their Mac. Android shows its screen-capture consent prompt each time. The screen is streamed live to the user's own Mac over the local network and never recorded or saved.
```

- **Video:** the ongoing notification while connected, and a mirroring session starting.

### Photo and video permissions (READ_MEDIA_IMAGES)

```
Mac n Droid can send each new screenshot to the user's Mac automatically, as soon as it's taken, without the user opening the app. This needs to notice new images in the background, which the system photo picker can't do. The app only reads screenshots taken after the feature is turned on, and sends them only to the user's own paired Mac.
```

> **Risk:** Google allows broad photo access only when it's central to the app. This may be rejected. If
> so, publish the Play version without automatic screenshots (sending photos by hand already uses the
> system share sheet and picker).

### Battery optimisation (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)

There's no form for this, but the use has to qualify. Mac n Droid qualifies as a companion app that has to
stay connected to another device. Keep the in-app explanation before asking.

---

## Release checklist

1. Developer account: one-time US $25 fee and identity verification.
2. **New personal accounts:** a closed test with at least 12 testers opted in for 14 days in a row is
   required before applying for production.
3. **App signing:** when creating the app, choose to use your own key and upload the existing release key
   (Play Console shows the steps, using `pepk`). Play and GitHub versions can then update over each other.
4. Upload `app-release.aab` (Android App Bundle) from `android/app/build/outputs/bundle/release/`, built
   with `./gradlew bundleRelease`.
5. Fill in every App content section above, then send the closed test for review.
