# Mac n Droid wire protocol (v1)

Local-network only. The Mac is the **server**, the phone is the **client**.

## Discovery & pairing
- Mac listens on TCP **47800** (falls back to a random port) and advertises Bonjour `_macndroid._tcp` with TXT `id=<macId>`.
- Mac shows a QR code:
  `macndroid://pair?v=1&id=<macId>&k=<pairingKey base64url>&p=<port>&h=<ip1,ip2>&n=<Mac name>`
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

## v0.8 additions

- `notif` gains `cat`: messages, calls, email, social, shopping, finance, media, travel, work, news, games, system, other (worked out on the phone from the package, notification category and Play category).
- `media` is now sent per player with `id` (package name) and `since` (wall ms it started playing, 0 when paused); `media_end` {`id`} removes a player. `media_cmd` takes `id` to target a player.
- `live` gains `paused` (ms, ≥0 when a stopwatch/timer is paused). Android 17 `MetricStyle` notifications are read from `android.metrics`. A live notification removed while its own app is in the foreground is held until the app is left.

## v0.9 additions
| Message | Direction | Fields | Meaning |
|---|---|---|---|
| `bye` | Mac → phone | `reason`: `sleep` \| `quit` | The Mac is about to sleep or quit. The coming disconnect is expected, so the phone won't raise a left-behind alert. |
| `input` | Mac → phone | `key`: `volume_up` \| `volume_down` | Changes the phone's volume. Works without the phone-control accessibility service. |

`input_unavailable` (phone → Mac) now also makes the phone show a one-time notification that opens the
accessibility settings for **Mac n Droid phone control**, which is now separate from **clipboard sync**.

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

### Browser extension link (Mac only, v0.10)
The extension connects to the Mac app over a WebSocket at `ws://127.0.0.1:47809`. It listens on
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
