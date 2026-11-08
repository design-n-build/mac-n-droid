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
