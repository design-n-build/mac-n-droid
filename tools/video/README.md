# Capture kit for the video series

Everything the videos show of the apps comes from the apps' own code, with the demo data in
`video/series/STORY.md` section 2 (Pixel 8 at 82%, Priya, Sam, Mum, Northwind Bank 482913, Osteria Lucca,
dinner-menu.jpg, Q4-deck.pdf, holiday-photos, "Golden Hour Drive" by Lumen Field).

## Stills (run on this Mac, nothing appears on screen)

| Command | Makes |
|---|---|
| `tools/video/mac-shots.sh` | `video/series/captures/mac/`: notch, drop target, music bar and popover at 3× on transparent; windows at 2×, light and dark; `manifest.json` with sizes |
| `tools/video/android-shots.sh` | `video/series/captures/android/`: 1236 × 2745 screens, light and dark, plus real animation frames of pairing |
| `python3 tools/video/gallery.py` | `video/series/captures/index.html`, one page to look through them (both scripts run it) |

Options: `mac-shots.sh --only notch/07` (one capture; `--only messages,files` for several), `--scale 2`,
`--date 2026-11-12 --time 18:40` (the story's clock, the default); `android-shots.sh -Ppalette=green`,
`-PallFrames=true` (animation frames in dark too).

`video/series/captures/` is not in git: run the scripts to make it again. `video/series/demo/` (the invented
menu photo, deck, holiday photos and album cover) is in git, so all three tools use the same files.

The Mac stills run on a fixed clock (`--date 2026-11-12 --time 18:40` by default: a Thursday evening), so dates,
times and "4 min ago" labels come out the same whenever they are made.

## Live recordings (for anything that moves)

`tools/video/recording-kit.sh` puts a recording build of the Mac app, the demo phone (`demo-phone.sh`), the demo
files and the take list in `/Users/Shared/mac-n-droid-recording/`, where another macOS account can reach them.

Record in the separate, clean macOS account described in STORY.md. The demo phone pairs like a phone scanning
the QR code for the first time, so that account's Mac n Droid must not be paired with another phone.

```bash
# Mac n Droid is running. Start recording, then:
java -Ddelay=5 tools/FakePhone.java 127.0.0.1 47800 --demo notify        # Priya drops from the notch
java tools/FakePhone.java 127.0.0.1 47800 --demo list                    # every scene
java -Dgap=8 tools/FakePhone.java 127.0.0.1 47800 --demo notify,code,file-menu,music
```

It stays connected and answers like a phone: Reply or Mark as read removes the notification, Messages shows
the conversations, the music buttons work, Ring and dropped files are printed in the terminal.

- `tools/video/left-behind.sh` shows the real "Did you leave Pixel 8 behind?" alert (needs a debug build of
  the Mac app running: `cd mac && swift build`, in an app bundle). The alert is a macOS notification, so
  it can't be a still.
- `tools/video/phone-notifications.sh install`, then `story`, posts the story's notifications on the phone from three
  invented apps (Messages, Northwind Bank, Files) with their own names and icons. They are debug-only props in
  `tools/video/demo-apps/`, a separate project from the real app.
- `tools/video/phone-demo.sh on` cleans the phone's status bar (18:42, 82%); `record take.mp4` records with
  scrcpy; `off` puts it back.

Only live: the left-behind alert, the Android lock screen, share sheet, Quick Settings tile and media
notification, the popover's glass, and the phone's picture inside the Phone Screen window.
