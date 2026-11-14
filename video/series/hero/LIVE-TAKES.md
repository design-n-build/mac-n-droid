# Hero film: live takes to record

Everything the hero film ("Stay in the flow", STORY.md section 3) still needs from a screen recorder or a
camera. Stills already exist for every app screen; these takes add the real motion and the things only macOS,
Android or a camera can show. Take numbers match the amber boxes in `storyboard/index.html`.

Record screens at native Retina resolution, 60 fps. Leave 3 seconds of stillness before and after each action.

## 0. Once, in your own account

```bash
tools/video/recording-kit.sh
```

This puts a recording build of the Mac app, the demo phone, the demo files and this checklist in
`/Users/Shared/mac-n-droid-recording/`, where the clean account can reach them.

## 1. Set up the clean macOS account

1. Plain wallpaper, no desktop icons, Dock auto-hide, Dark appearance, accent Blue.
2. Open `/Users/Shared/mac-n-droid-recording/Mac n Droid (recording).app` (right-click → Open the first time).
   Allow Local Network and Notifications. Click through Welcome and choose **Set Up Later**.
3. Settings → Sync → Notch notifications: **Large: with buttons and reply**.
4. Do Not Disturb **on** for every take except M10.
5. In Terminal: `cd /Users/Shared/mac-n-droid-recording`

## 2. Mac takes with the demo phone (do these before pairing your real phone)

The demo phone pairs like a phone scanning the QR code for the first time, so the Mac must not be paired
with another phone yet. Each command connects, waits 5 seconds, then plays its scene. Press Ctrl-C after
the take.

| Take | For | Command | Do this | Length |
|---|---|---|---|---|
| M1 | 0:00–0:16 the zone, pulled out | none | TextEdit, one window, type a paragraph, stop mid-sentence and leave the cursor still for 8 s | 25 s |
| M2 | 0:16–0:30 reveal and reply | `./demo-phone.sh notify` | When Priya drops: wait 2 s, click **Reply**, type `Sounds good, see you there!`, click **Send**, wait for the "Reply sent" pill to go | 30 s |
| M3 | 0:30–0:37 code | `./demo-phone.sh code` | Safari open on `props/northwind-signin.html`. When Northwind Bank drops: click **Copy 482913**, click the first box, ⌘V | 20 s |
| M4 | 0:37–0:47 clipboard | `DELAY=14 ./demo-phone.sh clipboard` | Mac n Droid → Clipboard open beside a TextEdit note with `Osteria Lucca, 42 Elm Street`. Select it, ⌘C (it appears in the list). At 14 s the phone's copy arrives: ⌘V in the note | 25 s |
| M5 | 0:47–0:52 drop on the notch | `./demo-phone.sh idle` | Finder window on `demo/`. Drag **holiday-photos** up to the notch, hold 1 s over the target, let go | 15 s |
| M6 | 0:52–0:57 file lands | `./demo-phone.sh file-deck` | Dock visible with the Downloads stack. Wait for Q4-deck.pdf in the notch, click **Show in Finder** | 15 s |
| M7 | 0:57 montage: screenshots | `./demo-phone.sh screenshot` | Empty TextEdit note in front. When "Image copied from Pixel 8" shows: ⌘V | 12 s |
| M8 | 0:59 montage: texts | `./demo-phone.sh notify` | Mac n Droid → Messages open on Priya. Type `Sounds good, see you there!`, Return | 20 s |
| M8b | 1:01 montage: music | `./demo-phone.sh music` | Click the music bar beside the notch to open it, click **next**, click it closed | 12 s |
| M10 | 1:16–1:28 left-behind alert | `./left-behind.sh 5` | Do Not Disturb **off**. Clean desktop. The alert slides in 5 s after the command. (Run any demo-phone take first so it says "Pixel 8".) | 15 s |

Between takes, `./demo-phone.sh clear` removes leftover notifications and music.

## 3. Takes with your real phone

Rename the phone to **Pixel 8** (Settings → About phone → Device name) and use a clean profile. Then in
Mac n Droid: Pair a Phone → **Reset Pairing**, so the QR code is fresh.

```bash
./phone-demo.sh on            # clean status bar: 18:42, 82%, full Wi-Fi
./phone-demo.sh record p1.mp4 # records the phone with scrcpy; Ctrl-C to stop
./phone-demo.sh off           # when you're done
```

| Take | For | Record | Do this | Length |
|---|---|---|---|---|
| M11 + P5 | 1:05–1:16 pairing | Mac and phone at once | Mac shows the QR code. On the phone: **Scan QR code**, scan it, wait for "You're connected" on both | 20 s |
| P1 | 0:05–0:12 lock screen | phone | Phone locked, screen off. From the Mac: `./phone-notifications.sh story` | 15 s |
| P2 | 0:37–0:47 clipboard | phone | Mac n Droid → Clipboard tab open. On the Mac, ⌘C the address: it appears at the top. Then copy `482913` on the phone | 15 s |
| P3 | 0:47–0:57 files | phone | Files tab open while holiday-photos is dropped on the Mac's notch. Then Files app → Q4-deck.pdf → Share → **Send to Mac** | 30 s |
| P4 | 0:57 montage: links | phone | Phone on the home screen. On the Mac, with a page open in Safari, press ⌃⌥P: the page opens on the phone | 10 s |
| M9 | 1:03 montage: mirror | Mac | Menu bar → **Mirror Screen**, tap Start on the phone. In the window: scroll the app drawer, open Clock, click Back | 20 s |

For P1, the notifications come from three small demo apps with invented names and their own icons
(Messages, Northwind Bank, Files). Install them once, then play the story:

```bash
./phone-notifications.sh install   # once
./phone-notifications.sh story     # the code, Priya's text, the download, 2 s apart
./phone-notifications.sh clear     # take them away for another go
```

Check first that the lock screen shows notification content (Settings → Notifications → Notifications on
lock screen → Show conversations, default and silent) and wakes for them. Priya's text has working Reply and
Mark as read, and Mac n Droid on the phone passes it to the Mac like any other app's notification.

For P3, put the deck on the phone first: `adb push demo/Q4-deck.pdf /sdcard/Download/`

## 4. Camera plates (phone camera, 4K, locked exposure)

| Plate | For | Shot | Length |
|---|---|---|---|
| C1 | 0:00–0:05 | Night. Slow push across the desk: MacBook open and glowing, phone face-down, mug, notebook | 10 s |
| C2 | 0:05–0:12 | Same framing, three small moves toward the phone as it lights up | 10 s |
| C3 | 0:12–0:16 | Hands reach for the phone and stop just above it. Hold still | 6 s |
| C4 | 1:16–1:20 | Golden hour. Hands close the MacBook, slide it into a bag; the phone stays under the notebook | 8 s |
| C5 | 1:20–1:24 | Café table. The MacBook opens, screen toward camera | 8 s |
| C6 | 1:24–1:26 | The phone alone on the desk, golden hour, locked-off | 5 s |
| C7 | 1:26–1:28 | Hands rush in and grab the phone | 4 s |

## Not needed for the hero film

The menu bar popover (and so its glass panel) doesn't appear in the hero film. Short S7 will need it.
