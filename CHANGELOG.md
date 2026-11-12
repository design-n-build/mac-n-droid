# What's new

## 0.13.0 (Windows and Linux beta; Android and browser extension)
- **Mac n Droid for Windows and Linux (beta),** with the same features as the Mac app: notifications with
  Reply and call buttons, **Messages** (read and send texts), **Phone screen** (see and use your phone, with
  mouse and keyboard), music both ways, clipboard with history, files and folders both ways, links both ways,
  ring your phone, the browser extension, and the left-behind alert. Windows 10 and 11 (Intel, AMD and ARM);
  Linux as a Flatpak (x86_64 and ARM) or from source. The webcam works too: it's Android's own USB webcam.
- **Android says "PC" when paired with Windows or Linux,** everywhere it used to say "Mac".
- **Volume, both ways:** a slider for your phone's volume next to its music on the Mac's Overview and in the
  notch. On the phone, "Music on your Mac" works like a Cast device: a Mac volume slider in Android's media
  controls, volume buttons in its notification, and the phone's volume keys change the Mac's volume while
  that's what's playing.
- **The notch shows over every app again** (with Stage Manager on, it could show only while Mac n Droid's own
  window was up).
- **Several live activities at once in the notch:** music beside the notch with a timer or delivery running as
  small bubbles next to it. Click the notch to see them all, and click one to put it beside the notch.
  The phone's volume has a mute button (click the speaker).
- Live activities end on the Mac as soon as you leave that app on the phone or lock it (a finished timer could
  stay up counting).
- **Browser extension:** video calls (Google Meet, Zoom, Teams and others) no longer show up as music on your
  phone. Separate packages for the Chrome Web Store, Edge Add-ons and Firefox Add-ons.
- **Android: one setting instead of two.** Clipboard sync and phone control are now a single optional
  accessibility setting, **Mac n Droid clipboard and controls**. It covers automatic copying, using the phone
  from your computer, links that open by themselves and music started from your computer. If you had clipboard
  sync on, it stays on; if you only had phone control on, turn on the new setting once (the phone reminds you).
- **Android: new first-run setup.** A welcome screen, then **Make it yours**: turn on only the features you want
  before pairing, each with a plain explanation. Before Android's Accessibility settings open, the app explains
  exactly what the setting looks at and asks you to agree.
- **Android: setup checklist** on the Home tab shows what's on and what's off, with an Enable button for each.
  If Android switches clipboard and controls off (it can happen after an update), the phone tells you.
- Android: a link from your computer that arrives as a notification explains how to make links open by
  themselves.

## 0.12.2 (Mac, Android and browser extension)
- **Turn off sounds:** on the Mac, Settings → General → **Play sounds**; on the phone, **Sounds** on the
  Home screen. Notifications and alerts still appear, just silently. Find My Phone still rings.

## 0.12.1 (Mac, Android and browser extension)
- **Send folders**, both ways. On the Mac, drag a folder onto Mac n Droid or the notch, pick one with
  **Choose Files…**, or use Share → Send to Phone in Finder. On the phone, tap **Send files** →
  **Folder**. The folder arrives with its subfolders, in Downloads (on the phone, Downloads › Mac n Droid).
- **Left-behind alerts explain how they work:** they come when your phone leaves the Wi-Fi network your
  Mac is on (or your Mac joins another network without your phone), not at a set distance.
- **New browser extension icon:** the white Mac n Droid logo. In light mode the toolbar shows a dark one.
- Support email: help.macndroid@gmail.com.

## 0.12.0 (Mac, Android and browser extension)
- **Add the browser extension from the Mac app:** click **Browser Extension** in the sidebar (or Settings →
  Sync → Browser → **Add to Browser…**). It explains what the extension does, then opens your browser's
  extension store: Chrome Web Store, Edge Add-ons or Firefox Add-ons. The Overview suggests it until an
  extension is connected.
- **Browser extension for Firefox.**
- **Pairing code opens Google Play:** scanning the Mac's pairing code with the phone's camera opens Mac n
  Droid on Google Play if it isn't installed. The pairing details stay on the phone.
- The menu bar no longer has a Send Tab button. Press **⌃⌥P**, or use the browser extension, to open the
  current page on your phone.
- The Android app now targets Android 16.

## 0.11.1
- Browser extension: no more "connection refused" entries on the browser's Errors page when Mac n
  Droid isn't running. It now connects only when something plays, when you open its popup or use
  its menu, and waits longer between retries. The right-click menu shows ✓ or ! on the toolbar
  icon to say whether it worked.

## 0.11.0 (Mac and Android)

### New
- **Messages on your Mac:** read and reply to texts from the Mac's new Messages page. Start a new
  conversation with contact search. Google Messages chat (RCS) messages that arrive while you're
  connected show up too, and replies go back through the chat.
- **Clipboard history:** the last 20 things copied on either device, on both devices. Tap to copy
  again, or clear the list from the phone or the Mac.
- **Link handoff:** share a link on the phone with **Open on Mac** to open it in the Mac's browser.
  On the Mac, press **⌃⌥P** to open the current browser tab on the phone. If the phone is locked,
  the link opens when you unlock it.
- **Send to Phone in the Mac's Share menu:** in Finder, Photos, Safari, Preview and any app with a
  Share button. Files and photos go to the phone, links open on it, and text goes to its clipboard.
- **Browser extension** for Chrome, Edge, Brave, Arc and other Chrome-based browsers: pause, play and skip music and
  videos playing in the browser from your phone, and open pages and links on your phone.
- **Control your Mac's music from the phone:** Music, Spotify and browser tabs appear in the
  phone's media controls.
- **Drag files onto the MacBook notch** to send them to the phone.
- **Send files to Mac** Quick Settings tile on the phone.
- **Automatic updates** for the Mac app.
- **Left-behind alerts:** a chime on the phone if you walk away from your Mac while it's awake.
- **Use your phone from the Mac** while mirroring (needs the optional phone control setting).

### Improved
- The phone's share sheet shows two clear actions, **Open on Mac** and **Send to Mac**, with a
  laptop icon.
- A burst of notifications (like WhatsApp re-posting old unread chats) plays one sound on the Mac
  and shows only the first few.
- Calls disappear from the Mac as soon as they end, even when the phone is locked.
- Clicking **Reply** in the notch puts the cursor straight in the text box. Replies that can only
  happen on the phone are labelled "Reply on Phone"; messages that can't be replied to have no
  Reply button.
- Several files at once give one sound and one notification.
- Android home screen: grouped settings in the style of Google's own apps, colors that follow your
  phone's theme, and the bottom bar in the order Home, Files, Clipboard, Notifications.
- Softer brand colors and clearer app and extension icons.

### Notes
- The Mac app isn't notarized yet: the first time, open **System Settings → Privacy & Security**
  and click **Open Anyway**.
- To install the browser extension, open Mac n Droid → **Settings → Sync → Browser → Show
  Extension in Finder**, then load it in your browser's extensions page with Developer mode on.
- Messages needs SMS access on the phone. Older Google Messages chats (RCS) stay on the phone;
  only new ones show on the Mac.

## 0.8.2 (Mac) · 0.8.5 (Android)
First public version: notifications, clipboard, files, phone battery, ring my phone, notch
notifications.
