# Mac n Droid wire protocol (v1)

Local-network only. The Mac is the **server**, the phone is the **client**.

## Discovery & pairing
- Mac listens on TCP **47800** (falls back to a random port) and advertises Bonjour `_macndroid._tcp` with TXT `id=<macId>`.
- Mac shows a QR code:
  `https://play.google.com/store/apps/details?id=app.macndroid#macndroid://pair?v=1&id=<macId>&k=<pairingKey base64url>&p=<port>&h=<ip1,ip2>&n=<Mac name>`
- A phone without the app opens the Google Play page. The pairing part after `#` is a URL fragment, which
  is never included in web requests. The app reads the `macndroid://pair?…` part after `#`
  (and still accepts it on its own, from **Copy Pairing Link**).
- The Windows and Linux apps add `o=windows` / `o=linux` (and `"os"` in their hello), so the phone says "PC"
  instead of "Mac". A hello without `os` is a Mac.
- `k` is a random 32-byte secret generated on the Mac. Whoever holds it is trusted. "Reset pairing" on the Mac rotates it.
- Phone reconnects by trying the last known IPs, then Bonjour (matching TXT `id`).

## Framing
Every frame: `uint32 big-endian length` + `payload` (max 8 MiB).

## Handshake (plaintext JSON frames)
1. phone → mac `{"t":"hello","v":1,"phoneId":"…","nonce":"<16 random bytes, base64>"}`
2. mac → phone `{"t":"hello","v":1,"macId":"…","nonce":"<16 random bytes, base64>","name":"…"}`

Session key = `HMAC-SHA256(pairingKey, "mnd-v1" ‖ phoneNonce ‖ macNonce)`.

## Encrypted frames
AES-256-GCM, payload = `ciphertext ‖ 16-byte tag`.
Nonce (12 bytes) = `uint32 direction` ‖ `uint64 counter` (big-endian). Direction 1 = phone→mac, 2 = mac→phone. Each side counts from 0. A frame that fails to decrypt (wrong key, replay, reorder) closes the connection.

The first encrypted frame from the phone must be `device`; the Mac answers `welcome` (`name`). If the phone sees the socket close before `welcome`, its pairing key is wrong and it should ask the user to re-pair.

## Messages (JSON, field `t` = type)
| t | Direction | Fields |
|---|---|---|
| `device` | P→M | `name`, `model`, `os` |
| `battery` | P→M | `level` (0-100), `charging` (bool) |
| `notif` | P→M | `key`, `pkg`, `app`, `title`, `text`, `time` (ms), `call` (bool), `actions: [{i, title, reply}]`, `icon` (base64 PNG, optional, sent once per app per session) |
| `notif_removed` | P→M | `key` |
| `notif_dismiss` | M→P | `key` |
| `notif_action` | M→P | `key`, `i`, `text` (for reply actions) |
| `clip` | both | `text`, `explicit` (bool — user pressed "send") |
| `ring` / `ring_stop` | M→P | — |
| `file_start` | both | `id`, `name`, `size`, `mime` |
| `file_chunk` | both | `id`, `data` (base64, ≤256 KiB raw) |
| `file_end` | both | `id` |
| `ping` / `pong` | both | — (keep-alive every 20 s) |

## v0.7 additions

| Message | Direction | Fields |
|---|---|---|
| `mirror_start` / `mirror_stop` | Mac → phone | Start/stop screen mirroring. The phone shows Android's capture consent dialog every time. |
| `mirror_cfg` | phone → Mac | `w`, `h`, `d` (base64 H.264 SPS+PPS, Annex B). Sent before the first key frame and after rotation. |
| `mirror_frame` | phone → Mac | `k` (key frame), `d` (base64 H.264 access unit, Annex B). Frames are dropped under congestion until the next key frame. |
| `mirror_key` | Mac → phone | Decoder needs a fresh key frame + config. |
| `mirror_stopped` | phone → Mac | `reason` = `denied` if the user declined. |
| `input` | Mac → phone | Gesture `p` = [[x,y]…] normalized 0…1, `dur` ms; or `key` = back/home/recents/notifications/quick_settings/lock/screenshot/backspace/enter; or `text`. Injected by the accessibility service. |
| `input_unavailable` | phone → Mac | The accessibility service is off, so control isn't possible. |
| `live` / `live_end` | phone → Mac | Live activity: `key`, `pkg`, `app`, `kind` (call/timer/stopwatch/progress/status), `title`, `text`, `short`, `when` (chronometer base, ms), `progress`, `max`, optional `icon`. |
| `media` | phone → Mac | `active`; `title`, `artist`, `album`, `duration`, `playing`, `position` at `positionAt` (wall ms), `speed`, `canPrev/canNext/canSeek`, `art` (base64 JPEG, only when the track changes). |
| `media_cmd` | Mac → phone | `cmd` = play/pause/toggle/next/prev/seek, `pos` ms. |

Screenshots taken on the phone are sent as `file_start … file_end` with `clip: true`.

A folder is sent as one zip of the folder (the folder itself at the top), named `<folder>.zip`, with
`folder: true` on `file_start`. The receiver unpacks it into a folder in Downloads (on the phone,
Downloads/Mac n Droid), keeping the subfolders and dropping `..`, `__MACOSX` and `.DS_Store`. A receiver
that doesn't know the flag just saves the zip.

## v0.8 additions

- `notif` gains `cat`: messages, calls, email, social, shopping, finance, media, travel, work, news, games, system, other (worked out on the phone from the package, notification category and Play category).
- `media` is now sent per player with `id` (package name) and `since` (wall ms it started playing, 0 when paused); `media_end` {`id`} removes a player. `media_cmd` takes `id` to target a player.
- `live` gains `paused` (ms, ≥0 when a stopwatch/timer is paused). Android 17 `MetricStyle` notifications are read from `android.metrics`. A live notification removed while its own app is in the foreground is held until the app is left.

## v0.9 additions
| Message | Direction | Fields | Meaning |
|---|---|---|---|
| `bye` | Mac → phone | `reason`: `sleep` \| `quit` | The Mac is about to sleep or quit. The coming disconnect is expected, so the phone won't raise a left-behind alert. |
| `input` | Mac → phone | `key`: `volume_up` \| `volume_down` | Changes the phone's volume. Works without the accessibility service. |

`input_unavailable` (phone → Mac) now also makes the phone show a one-time notification that opens the
accessibility settings for **Mac n Droid clipboard and controls** (since 0.13 one service covers automatic
copying, phone control, links that open by themselves and music started from the computer).

## v0.10 additions
| Message | Direction | Fields | Meaning |
|---|---|---|---|
| `notif` actions | phone → Mac | `opens` (bool) | The action opens a screen on the phone (e.g. Gmail's Reply, which has no inline reply). The Mac labels it "Reply on Phone". |
| `open_url` | both | `url` (http/https only) | Link handoff: open this link in the other device's browser. The phone opens it directly when an accessibility service is on and the screen is on; otherwise it posts a "Link from Mac" notification. |
| `clip_history` | Mac → phone | `items`: [{`id`, `kind` text\|image, `text`, `truncated`, `thumb` (base64 JPEG), `from` mac\|phone, `time` ms}] | The last 20 copies on either device. The Mac keeps the list and sends it after every change and on connect. |
| `clip_get` | phone → Mac | `id` | Put that history item on the phone's clipboard (sent back as `clip` or a clip image transfer). |
| `clip_use` | phone → Mac | `id` | Put that history item on the Mac's clipboard. |
| `mac_media_want` | phone → Mac | `on` | Whether the phone shows "Music on your Mac". Sent on connect and when the setting changes. |
| `mac_media` / `mac_media_end` | Mac → phone | `app`, `title`, `artist`, `album`, `playing`, `dur` s, `pos` s, `art` (base64 JPEG, when the track changes) | What's playing in Music or Spotify on the Mac. |
| `mac_media_cmd` | phone → Mac | `cmd`: play \| pause \| toggle \| next \| prev | Controls the Mac player (AppleScript). |
| `sms_threads` | Mac → phone, then phone → Mac | reply: `threads`: [{`id`, `address`, `name`, `snippet`, `date` ms, `unread`, `out`}] | Latest SMS conversations. |
| `sms_thread` | Mac → phone `id`, then phone → Mac | reply: `id`, `messages`: [{`id`, `body`, `date`, `out`, `failed`, `pending`}] | Last 200 texts in one conversation, oldest first. |
| `sms_send` | Mac → phone | `address`, `text`, `id` (Mac's local id) | Send a text. |
| `sms_sent` | phone → Mac | `id`, `ok` | Result of a send. |
| `sms_changed` | phone → Mac | | A text arrived or was sent; the Mac refreshes. |
| `sms_unavailable` | phone → Mac | `reason`: off \| permission | Messages on Mac is off, or SMS access wasn't given. |
| `sms_send` | Mac → phone | `thread` (optional) | Reply in an existing conversation. With `thread`, `address` may be empty. Google Messages chat (RCS) conversations are replied to through the chat's notification when it's still on the phone. |
| `sms_threads` items | phone → Mac | `reply` (bool) | Whether replying is possible (short codes and business senders can't be replied to). |
| `contacts_search` | Mac → phone | `q` | Search the phone's contacts (New Message). |
| `contacts` | phone → Mac | `q`, `results`: [{`name`, `number`, `label`}], `permission` | Search results (`permission` false when contacts access is off). |
| `clip_clear` | phone → Mac | | Clear the clipboard history on both devices (the Mac sends back an empty `clip_history`). |

### Browser extension link (v0.10; Windows and Linux since v0.13)
The extension connects to the Mac n Droid app (Mac, Windows or Linux) over a WebSocket at `ws://127.0.0.1:47809`. It listens on
127.0.0.1 only and accepts only browser-extension origins (`chrome-extension://`, `moz-extension://`,
`safari-web-extension://`), so web pages can't connect. Messages are JSON with a `t` field:

| Message | Direction | Fields | Meaning |
|---|---|---|---|
| `status` | app → extension | `connected`, `phone` | Whether a phone is connected, and its name (for the menus and popup). |
| `media` | extension → app | `tab`, `site`, `title`, `artist`, `album`, `playing`, `dur`, `pos`, `canNext`, `art` (base64 image, when it changes) | A tab is playing or paused. The newest playing source (browser tab, Music or Spotify) is what the phone controls. |
| `media_end` | extension → app | `tab` | The tab stopped or closed. |
| `cmd` | app → extension | `tab`, `cmd`: play \| pause \| toggle \| next \| prev | From the phone's media controls. |
| `send_url` | extension → app | `url` (http/https) | Open this page on the phone (`open_url`). |
| `ping` | extension → app | | Keep-alive. |

## v0.13 additions
| Message | Direction | Fields | Meaning |
|---|---|---|---|
| `volume` | phone → Mac | `vol` 0–100 | The phone's media volume, on connect and when it changes. The Mac shows a slider with the phone's music. |
| `volume_set` | Mac → phone | `vol` 0–100 | Set the phone's media volume (no accessibility service needed). |
| `mac_media` | Mac → phone | + `vol` 0–100, `muted`, `fixed` | The Mac's output volume with what's playing. `fixed`: this output can't be changed (some HDMI/USB). |
| `mac_volume` | Mac → phone | `vol`, `muted`, `fixed` | The Mac's volume changed. The phone shows it as a remote (Cast-style) volume for "Music on your Mac". |
| `mac_volume_set` | phone → Mac | `vol` 0–100 | Set the Mac's output volume. |
| `media_limited` | phone → Mac | `id`, `reason`: `background` | The phone was asked to start a player but Android 17+ will mute it (an app that starts playing in the background is muted unless the asking app may act in the background, e.g. through an enabled accessibility service). The Mac explains this. |

## v0.13 additions: Photos

The Mac's Photos pane browses the phone's photos and videos. Nothing is sent unless the Mac asks.

| Message | Direction | Fields | Meaning |
|---|---|---|---|
| `gallery_list` | Mac → phone | `offset`, `limit` (≤ 300), `kind`: `all` \| `photos` \| `videos`, `album` (optional bucket id) | Ask for one page, newest first. |
| `gallery` | phone → Mac | `offset`, `kind`, `album`, `access`: `full` \| `photos` \| `partial` \| `none` \| `off` (turned off on the phone; the Mac clears what it showed), `total`, `items` [{`id` (`i…` photo, `v…` video), `v`, `name`, `date` ms, `size`, `w`, `h`, `dur` ms, `mime`}], `albums` [{`id`, `name`, `count`}] (first page only) | One page. |
| `gallery_thumbs` | Mac → phone | `ids` (≤ 60), `size` px | Ask for thumbnails. |
| `gallery_thumb` | phone → Mac | `id`, `d` (base64 JPEG) or `missing` | One thumbnail. |
| `gallery_get` | Mac → phone | `id`, `mode`: `view` \| `save` | Send the original through the normal file transfer; its `file_start` carries `gallery` (the id) and `view`. `view` files go to the Mac's cache and open in Quick Look; `save` files go to Downloads. |
| `gallery_ask` | Mac → phone | — | The phone shows a notification that asks for photo and video access. |
| `gallery_access` | phone → Mac | `access` | Access changed; the Mac loads the library again. |

## v0.13.2 additions: Pause sharing

| Type | Dir | Fields |
|---|---|---|
| `paused` | P→M | `on` (bool), `until` (ms since 1970; 0 = until the person resumes). Sent when sharing is paused or resumed, and on connect while paused. |

While paused the phone stays connected but sends only `ping`, `pong`, `hello`, `device`, `battery`, `paused`,
`ring_stopped` and messages that switch things off (`gallery_access`, `mac_media_want`, `notif_removed`,
`live_end`, `media_end`, `mirror_stopped`). From the computer it accepts only `ring`, `ring_stop` and `bye`;
a `mirror_start` is answered with `mirror_stopped` (`reason: "paused"`). Computers that don't know `paused`
simply see a quiet connection.
