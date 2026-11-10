# Chrome Web Store, Edge Add-ons and Firefox Add-ons listing

Everything to paste into the Chrome Web Store developer dashboard for the Mac n Droid browser extension.
The same text and zip work for Microsoft Edge Add-ons (Partner Center) and Firefox Add-ons (addons.mozilla.org), which are both free.

---

## Package

Zip the **contents** of `browser-extension/` (with `manifest.json` at the top level of the zip):

```
cd browser-extension && zip -r ../release/MacNDroid-extension-0.12.0.zip . -x ".*"
```

---

## Store listing

**Name:** Mac n Droid

**Summary** (132 max; it comes from the manifest's `description`)

```
Control music and videos playing in your browser from your Android phone, and open pages and links on it. Needs the Mac n Droid app.
```

**Description**

```
Mac n Droid connects your Android phone to your Mac. This extension brings your browser into it.

CONTROL BROWSER MUSIC AND VIDEOS FROM YOUR PHONE
YouTube, web music players, podcasts and other sites appear in your phone's media controls, with the title and artwork. Pause, play and skip from your phone or lock screen, without walking back to your Mac.

OPEN THE PAGE YOU'RE READING ON YOUR PHONE
Click the Mac n Droid button and the page opens on your phone, so you can keep reading on the go.

OPEN ANY LINK ON YOUR PHONE
Right-click a link and choose Open Link on Your Phone.

PRIVATE
The extension talks only to the Mac n Droid app on the same Mac, which passes things to your phone over your own Wi-Fi, encrypted. It doesn't track what you browse, collect any data or send anything to the internet. It's free and open source.

YOU'LL NEED
• The free Mac n Droid app for Mac (macOS 14 or later)
• The Mac n Droid app on your Android phone, paired with your Mac

Mac n Droid isn't affiliated with Apple or Google.
```

**Category:** Productivity → Tools (or "Workflow & Planning") · **Language:** English

**Graphics**
- Store icon: 128 × 128 PNG (`browser-extension/icons/icon-128.png`).
- Screenshots: 1 to 5 at **1280 × 800**. Suggested: the popup showing what's playing; the phone's media
  controls next to a YouTube tab; the right-click menu "Open Link on Your Phone".
- Small promo tile: **440 × 280** (required). Marquee 1400 × 560 is optional.

---

## Privacy practices tab

**Single purpose**

```
Lets the user's Android phone control media playing in the browser, and opens the current page or a chosen link on that phone, through the Mac n Droid app running on the same computer.
```

**Permission justifications**

| Permission | Justification |
|---|---|
| `tabs` | To read the address and title of the current tab when the user clicks the toolbar button or the right-click menu to open it on their phone, and to know which tab is playing media so phone controls reach the right tab. |
| `contextMenus` | Adds "Open Link on Your Phone" and "Open Page on Your Phone" to the right-click menu. |
| `alarms` | Retries the connection to the Mac n Droid app on this computer at growing intervals when it isn't running, instead of keeping the service worker awake. |
| `scripting` | Adds the media-control script to tabs that were already open when the extension was installed or updated, so media in those tabs can be controlled without reloading them. |
| Host permission `<all_urls>` and content script on all sites | Music and videos can play on any website. The content script finds playing audio and video elements, reads their title, artist and artwork (from the page's Media Session), and plays, pauses or skips them when the user presses a control on their phone. It reads nothing else on the page. |

**Remote code:** No, I am not using remote code. All JavaScript is included in the package.

**Data usage:** tick **none** of the data types. The extension doesn't collect or transmit user data to the
developer or any third party. What it reads goes only to `127.0.0.1` (the Mac n Droid app on the same
computer), and from there to the user's own phone.

Tick all three certifications:
- I do not sell or transfer user data to third parties, outside of the approved use cases.
- I do not use or transfer user data for purposes that are unrelated to my item's single purpose.
- I do not use or transfer user data to determine creditworthiness or for lending purposes.

**Privacy policy URL:** the same page as the Play Store (`site/privacy.html` once online).

---

## Notes for the reviewer (Distribution / test instructions)

```
This extension works together with the Mac n Droid app for macOS. It connects only to that app, on the same computer at ws://127.0.0.1:47809, and makes no other network connections apart from loading artwork for media that is already playing on a page.

Without the Mac app running, the extension does nothing visible except its popup, which explains that Mac n Droid needs to be open. It never shows errors to the user.

To test the full flow:
1. Install Mac n Droid on a Mac (macOS 14 or later) from <site URL>, and the Android app on a phone.
2. Pair them: Mac → Pair a Phone; phone → Scan QR code.
3. Play a YouTube video in Chrome. It appears in the phone's media controls; pause and skip from the phone.
4. Right-click a link → Open Link on Your Phone. The link opens on the phone.

Video of the full flow: <video URL>

Why <all_urls>: media can play on any site, and the content script must find the audio or video element on that page to control it. It reads only media title, artist, artwork and playback state.
```

---

## Checklist

1. Developer account: one-time US $5 fee, with 2-Step Verification turned on for the Google account.
2. Upload the zip, fill in Store listing and Privacy practices, then submit. Because the extension can run on
   every site, expect an in-depth review that takes a few days.
3. Edge: create a free Microsoft Partner Center account, then upload the same zip with the same text.
4. Firefox: create a free account at addons.mozilla.org/developers and upload the same zip. Mozilla reviews
   and signs it for free. In the data collection question, choose **No data collected** (the manifest already
   says so). Ask for the add-on page address to be `mac-n-droid`.
5. Once each store page is live, put its link in
   `mac/Sources/MacNDroid/Core/BrowserExtension.swift` (`chromeWebStore`, `edgeAddOns`, `firefoxAddOns`). Until
   then, the Mac app's **Add to Chrome-Based Browser** and **Add to Firefox-Based Browser** buttons open each
   store's search for "Mac n Droid". The Chrome button opens Edge Add-ons when the browser in use is Edge.
