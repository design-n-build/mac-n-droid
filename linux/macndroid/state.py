"""Everything the window shows, and what happens with each message from the phone. Runs on the UI thread."""
import base64
import logging
import mimetypes
import os
import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path

from gi.repository import GLib, GObject

from . import folders
from .bridge import BrowserBridge
from .connection import Server, Session
from .discovery import Advertiser
from .identity import Identity, computer_name, local_ipv4
from .leftbehind import LeftBehind
from .media import ComputerMedia

log = logging.getLogger("macndroid")
CHUNK = 256 * 1024
HISTORY_LIMIT = 20


@dataclass
class Notification:
    key: str
    pkg: str
    app: str
    title: str
    text: str
    time: float
    is_call: bool
    actions: list  # [{i, title, reply, opens}]


@dataclass
class Clip:
    id: str
    kind: str  # text | image
    text: str = ""
    png: bytes = b""
    from_phone: bool = False
    time: float = field(default_factory=time.time)


@dataclass
class Transfer:
    id: str
    name: str
    size: int
    to_phone: bool
    path: str
    bytes: int = 0
    state: str = "active"  # active | done | failed
    folder: bool = False


@dataclass
class Player:
    """Something playing on the phone (`media` in PROTOCOL.md)."""
    id: str
    pkg: str
    app: str
    title: str
    artist: str
    album: str
    duration: float  # ms, 0 for live streams
    playing: bool
    position: float  # ms at position_at
    position_at: float  # wall seconds
    speed: float
    since: float  # wall seconds it started playing, 0 while paused
    can_prev: bool
    can_next: bool
    can_seek: bool

    def now(self) -> float:
        """Current position in ms."""
        if not self.playing:
            return self.position
        p = self.position + (time.time() - self.position_at) * 1000 * (self.speed or 1)
        return min(p, self.duration) if self.duration else p


def can_text(address: str) -> bool:
    """Banks and shops send from names like "AD-ICICIB-S" that can't receive texts."""
    return not any(c.isalpha() for c in address) and sum(c.isdigit() for c in address) >= 3


def number_key(s: str) -> str:
    return "".join(c for c in s if c.isdigit())[-10:]


def downloads_dir() -> Path:
    d = os.environ.get("MACNDROID_DOWNLOADS") or GLib.get_user_special_dir(GLib.UserDirectory.DIRECTORY_DOWNLOAD) \
        or os.path.expanduser("~/Downloads")
    Path(d).mkdir(parents=True, exist_ok=True)
    return Path(d)


def safe_name(name: str, fallback: str = "file") -> str:
    """Just a file name from the other device: no folders, no "..", no hidden dot files."""
    name = os.path.basename(str(name).replace("\\", "/").rstrip("/")).replace("\0", "").strip().lstrip(".")
    return name[:200] or fallback


def safe_id(s) -> str:
    """Ids from the other device end up in file names: letters, digits, "-" and "_" only."""
    return "".join(c for c in str(s) if c.isascii() and (c.isalnum() or c in "-_"))[:80]


def unique_path(folder: Path, name: str) -> Path:
    name = safe_name(name)
    p = folder / name
    stem, ext = os.path.splitext(name)
    n = 2
    while p.exists():
        p = folder / f"{stem} {n}{ext}"
        n += 1
    return p


class AppState(GObject.Object):
    """Signals tell the window what to redraw."""

    __gsignals__ = {
        "status-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "notifications-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "clips-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "transfers-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "pairing-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        # (title, body, notification id or "") for the app to show as a desktop notification.
        "post-notification": (GObject.SignalFlags.RUN_FIRST, None, (object,)),
        "withdraw-notification": (GObject.SignalFlags.RUN_FIRST, None, (str,)),
        # Text or PNG to put on this computer's clipboard.
        "set-clipboard": (GObject.SignalFlags.RUN_FIRST, None, (object,)),
        "media-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "live-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "sms-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "contacts-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "mirror-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        # (title, body) for "Did you leave your phone behind?"
        "left-behind": (GObject.SignalFlags.RUN_FIRST, None, (object,)),
        # (request id, new phone, paired phone): ask whether to allow a new phone; "" closes the question.
        "approval-request": (GObject.SignalFlags.RUN_FIRST, None, (object,)),
    }

    def __init__(self, settings=None):
        super().__init__()
        self.settings = settings or {}
        self.identity = Identity.load()
        self.name = computer_name()
        self.active: Session | None = None
        self.phone: dict | None = None
        self.last_phone_name = ""
        self.battery: int | None = None
        self.charging = False
        self.ringing = False
        self.notifications: list[Notification] = []
        self.icons: dict[str, bytes] = {}
        self.clips: list[Clip] = []
        self.transfers: list[Transfer] = []
        self.last_error = ""
        self._incoming: dict[str, tuple] = {}
        self._received: list[Path] = []
        self._received_timer = 0
        # Phone media, live activities, Messages, mirroring
        self.players: list[Player] = []
        self.player_art: dict[str, bytes] = {}
        self.live: dict[str, dict] = {}
        self.sms_state = "unknown"  # unknown | loading | ready | off | permission
        self.sms_threads: list[dict] = []
        self.sms_messages: dict[str, list[dict]] = {}
        self.sms_open: str | None = None
        self.sms_select_address = ""
        self.contacts: list[dict] = []
        self.contact_query = ""
        self.contacts_allowed = True
        self.mirror = "idle"  # idle | starting | live | denied | stopped
        self.mirror_sink = None  # object with config(m), frame(m), reset()
        self.control_unavailable = False
        # Music on this computer, for the phone
        self.phone_wants_media = False
        self.computer_media = ComputerMedia()
        self.computer_media.connect("changed", lambda *_: self._media_source_changed())
        self.browser_tracks: dict[tuple, dict] = {}  # (client, tab) → track
        self.browser_art: dict[tuple, bytes] = {}
        self._sent_track = None
        self._pushed_art_key = ""
        self.bridge = BrowserBridge(lambda f, *a: GLib.idle_add(lambda: f(*a) and False))
        self.bridge.on_message = self._bridge_message
        self.bridge.on_closed = self._bridge_closed
        self.left_behind = LeftBehind(lambda: self.connected, lambda: self.phone_name,
                                      lambda: self._setting("left_behind", True),
                                      lambda title, body: self.emit("left-behind", (title, body)),
                                      lambda: self.say_bye("sleep"))
        self.server = Server(self.identity, self.name, "linux", lambda f, *a: GLib.idle_add(lambda: f(*a) and False))
        self.server.on_device = self._decide
        self.server.on_authenticated = self._connected
        self._pending: dict | None = None
        self.server.on_message = self._message
        self.server.on_closed = self._closed
        self.advertiser = Advertiser()

    def start(self):
        port = self.server.start()
        self.advertiser.start(self.name, port, self.identity.pc_id)
        GLib.timeout_add_seconds(30, self._refresh_addresses)
        self.bridge.start()
        self.computer_media.start()
        self.left_behind.start()
        GLib.timeout_add_seconds(15, self._media_tick)

    def _setting(self, key, default):
        try:
            v = self.settings[key]
        except (KeyError, TypeError):
            return default
        return default if v is None else v

    def _refresh_addresses(self):
        self.emit("pairing-changed")
        return True

    @property
    def connected(self) -> bool:
        return self.active is not None

    @property
    def phone_name(self) -> str:
        return (self.phone or {}).get("name") or self.last_phone_name or "your phone"

    @property
    def addresses(self) -> list[str]:
        return local_ipv4()

    def qr_text(self) -> str:
        return self.identity.qr_text(self.server.port, self.addresses, self.name)

    def pairing_link(self) -> str:
        return self.identity.pairing_uri(self.server.port, self.addresses, self.name)

    def reset_pairing(self):
        if self._pending:
            self.resolve_approval(self._pending["id"], False)
        self.identity.reset()
        if self.active:
            self.active.close("Pairing reset")
        self.emit("pairing-changed")

    # -- pairing: single-use codes and approving new phones --

    APPROVAL_SECONDS = 120

    def _replace_invite_key(self):
        """The QR code has been used: make a new one, so screenshots and copied links of it stop working."""
        self.identity.replace_invite_key()
        self.emit("pairing-changed")

    def _decide(self, s: Session):
        name = s.device.get("name") or "Android phone"
        if s.used_device_key:
            s.admit()
            if self.identity.phone and not self.identity.phone["confirmed"]:
                self._confirm_phone_key()
            return
        p = self.identity.phone
        if p is None or p["id"] == s.phone_id:
            self._pair(s, name)
        elif s.legacy:
            s.deny("An older Mac n Droid app tried to pair")
            self.last_error = f"{name} tried to pair, but its Mac n Droid app is out of date. Update it, then scan the code again."
            self._replace_invite_key()
            self.emit("status-changed")
        else:
            self._ask_approval(s, name)

    def _pair(self, s: Session, name: str):
        if s.legacy:
            # An older app keeps using the invite key, so it can't be replaced until that phone updates.
            self.identity.phone = {"id": s.phone_id, "name": name, "key": None, "confirmed": True}
            self.identity.save()
            s.admit()
            return
        key = os.urandom(32)
        # Saved before it's sent: if the phone keeps it but the reply is lost, its next connection still works.
        self.identity.phone = {"id": s.phone_id, "name": name, "key": key, "confirmed": False}
        self.identity.save()
        s.admit(key)

    def _confirm_phone_key(self):
        p = self.identity.phone
        if not p or p["confirmed"]:
            return
        p["confirmed"] = True
        self.identity.save()
        self._replace_invite_key()

    def _ask_approval(self, s: Session, name: str):
        if self._pending:
            self.resolve_approval(self._pending["id"], False)
        paired = (self.identity.phone or {}).get("name") or self.last_phone_name or "your phone"
        rid = uuid.uuid4().hex
        self._pending = {"id": rid, "session": s, "name": name}
        s.await_approval(paired, self.APPROVAL_SECONDS)
        if self.active:
            self.active.send({"t": "approve_request", "id": rid, "name": name})
        GLib.timeout_add_seconds(self.APPROVAL_SECONDS, lambda: self.resolve_approval(rid, False) and False)
        self.emit("approval-request", (rid, name, paired))

    def resolve_approval(self, rid: str, allow: bool):
        """From this computer, the paired phone or the time limit; only the first answer counts."""
        p = self._pending
        if not p or p["id"] != rid:
            return False
        self._pending = None
        self.emit("approval-request", ("", "", ""))
        s = p["session"]
        if allow:
            old = self.active
            if old:
                old.send({"t": "unpaired", "by": p["name"]})
                GLib.timeout_add(500, lambda: old.close("Replaced by a new phone") and False)
            self._pair(s, p["name"])
        else:
            s.deny("Not allowed")
            self._replace_invite_key()
        return False

    # -- connection --

    def _connected(self, s: Session):
        if self.active and self.active is not s:
            self.active.close("Replaced by a new connection")
        self.active = s
        self.phone = s.device
        self.last_phone_name = s.device.get("name", "")
        self.last_error = ""
        self.control_unavailable = False
        self.emit("status-changed")
        self._push_history()
        self.bridge.set_status(True, self.phone_name)
        self.left_behind.linked()
        if self.sms_state != "unknown":
            self.refresh_sms()

    def _closed(self, s: Session, reason):
        if reason and "Pair it again" in reason:
            self.last_error = reason
            self.emit("status-changed")
        if self.active is not s:
            return
        self.active = None
        self.battery = None
        self.ringing = False
        self.players.clear()
        self.player_art.clear()
        self.live.clear()
        self.phone_wants_media = False
        self._sent_track = None
        if self.mirror != "idle":
            self.mirror = "stopped"
            self.emit("mirror-changed")
        self.bridge.set_status(False, "")
        if not self._bye_sent:
            self.left_behind.lost()
        self._bye_sent = False
        for n in self.notifications:
            self.emit("withdraw-notification", n.key)
        self.notifications.clear()
        for tid, (fh, path, _clip, _folder) in list(self._incoming.items()):
            fh.close()
            path.unlink(missing_ok=True)
            self._update_transfer(tid, state="failed")
        self._incoming.clear()
        for t in self.transfers:
            if t.state == "active":
                t.state = "failed"
        self.emit("status-changed")
        self.emit("notifications-changed")
        self.emit("transfers-changed")
        self.emit("media-changed")
        self.emit("live-changed")

    _bye_sent = False

    def send(self, msg: dict):
        s = self.active
        if s:
            threading.Thread(target=s.send, args=(msg,), daemon=True).start()

    def say_bye(self, reason: str):
        """Before sleeping or quitting: the phone then knows the disconnect is expected (no left-behind alert)."""
        s = self.active
        if s:
            self._bye_sent = True
            s.send({"t": "bye", "reason": reason})

    # -- messages from the phone --

    def _message(self, s: Session, m: dict):
        if s is not self.active:
            return
        t = m.get("t")
        if t == "key_saved":
            self._confirm_phone_key()
        elif t == "approve":
            self.resolve_approval(str(m.get("id", "")), bool(m.get("allow")))
        elif t == "battery":
            self.battery = m.get("level")
            self.charging = bool(m.get("charging"))
            self.emit("status-changed")
        elif t == "notif":
            self._notification(m)
        elif t == "notif_removed":
            key = m.get("key", "")
            self.notifications = [n for n in self.notifications if n.key != key]
            self.emit("withdraw-notification", key)
            self.emit("notifications-changed")
        elif t == "clip":
            text = m.get("text")
            if isinstance(text, str):
                self.emit("set-clipboard", text)
                self._record(Clip(uuid.uuid4().hex, "text", text=text, from_phone=True))
                if m.get("explicit"):
                    self.emit("post-notification", (f"Copied from {self.phone_name}", text[:120], ""))
        elif t == "ring_stopped":
            self.ringing = False
            self.emit("status-changed")
        elif t == "open_url":
            url = m.get("url", "")
            if url.startswith(("http://", "https://")):
                from gi.repository import Gio
                Gio.AppInfo.launch_default_for_uri(url, None)
        elif t == "clip_get":
            c = self._clip(m.get("id"))
            if c and c.kind == "text":
                self.send({"t": "clip", "text": c.text, "explicit": True})
            elif c:
                self._send_png(c.png)
        elif t == "clip_use":
            c = self._clip(m.get("id"))
            if c:
                self.emit("set-clipboard", c.text if c.kind == "text" else c.png)
        elif t == "clip_clear":
            self.clear_history()
        elif t in ("file_start", "file_chunk", "file_end"):
            self._file(t, m)
        elif t == "media":
            self._media(m)
        elif t == "media_end":
            self._remove_player(m.get("id", ""))
        elif t == "live":
            if m.get("key"):
                self.live[m["key"]] = m
                if m.get("icon") and m.get("pkg"):
                    try:
                        self.icons[m["pkg"]] = base64.b64decode(m["icon"])
                    except ValueError:
                        pass
                self.emit("live-changed")
        elif t == "live_end":
            if self.live.pop(m.get("key", ""), None):
                self.emit("live-changed")
        elif t == "mac_media_want":
            self.phone_wants_media = bool(m.get("on"))
            self._sent_track = None
            self._pushed_art_key = ""
            self._push_computer_media()
        elif t == "mac_media_cmd":
            self._computer_media_command(m.get("cmd", ""))
        elif t == "sms_threads":
            self.sms_threads = [x for x in m.get("threads") or [] if isinstance(x, dict) and x.get("id")]
            self.sms_state = "ready"
            self.emit("sms-changed")
        elif t == "sms_thread":
            tid = m.get("id")
            if tid:
                fresh = [x for x in m.get("messages") or [] if isinstance(x, dict)]
                # Keep a just-sent text visible until the phone's copy shows up.
                waiting = [x for x in self.sms_messages.get(tid, []) if str(x.get("id", "")).startswith("local-")
                           and not any(f.get("out") and f.get("body") == x.get("body") for f in fresh)]
                self.sms_messages[tid] = fresh + waiting
                self.emit("sms-changed")
        elif t == "sms_changed":
            self.refresh_sms()
        elif t == "sms_sent":
            if m.get("ok") is False:
                for lst in self.sms_messages.values():
                    for x in lst:
                        if x.get("id") == m.get("id"):
                            x["failed"], x["pending"] = True, False
                self.emit("sms-changed")
            else:
                self.refresh_sms()
        elif t == "sms_unavailable":
            self.sms_state = "permission" if m.get("reason") == "permission" else "off"
            self.emit("sms-changed")
        elif t == "contacts":
            if m.get("q") == self.contact_query:
                self.contacts_allowed = m.get("permission", True) is not False
                self.contacts = [c for c in m.get("results") or [] if isinstance(c, dict) and c.get("number")]
                self.emit("contacts-changed")
        elif t == "mirror_cfg":
            if self.mirror_sink:
                self.mirror_sink.config(m)
            if self.mirror == "starting":
                self.mirror = "live"
                self.emit("mirror-changed")
        elif t == "mirror_frame":
            if self.mirror_sink:
                self.mirror_sink.frame(m)
        elif t == "mirror_stopped":
            if self.mirror != "idle":
                self.mirror = "denied" if m.get("reason") == "denied" else "stopped"
                if self.mirror_sink:
                    self.mirror_sink.reset()
                self.emit("mirror-changed")
        elif t == "input_unavailable":
            self.control_unavailable = True
            self.emit("mirror-changed")
        elif t == "ping":
            self.send({"t": "pong"})

    def _notification(self, m: dict):
        key = m.get("key")
        if not key:
            return
        pkg = m.get("pkg", "")
        if m.get("icon"):
            try:
                self.icons[pkg] = base64.b64decode(m["icon"])
            except ValueError:
                pass
        n = Notification(key=key, pkg=pkg, app=m.get("app", pkg), title=m.get("title", ""), text=m.get("text", ""),
                         time=(m.get("time") or time.time() * 1000) / 1000, is_call=bool(m.get("call")),
                         actions=m.get("actions") or [])
        update = any(x.key == key for x in self.notifications)
        self.notifications = [n] + [x for x in self.notifications if x.key != key]
        del self.notifications[200:]
        self.emit("notifications-changed")
        if not m.get("silent") and not update:
            self.emit("post-notification", (n.title or n.app, n.text, key))

    def notification(self, key: str) -> Notification | None:
        return next((n for n in self.notifications if n.key == key), None)

    def dismiss(self, key: str):
        self.send({"t": "notif_dismiss", "key": key})
        self.notifications = [n for n in self.notifications if n.key != key]
        self.emit("withdraw-notification", key)
        self.emit("notifications-changed")

    def action(self, key: str, i: int, text: str | None = None):
        msg = {"t": "notif_action", "key": key, "i": i}
        if text is not None:
            msg["text"] = text
        self.send(msg)

    # -- phone actions --

    def ring(self):
        self.ringing = not self.ringing
        self.send({"t": "ring" if self.ringing else "ring_stop"})
        self.emit("status-changed")

    def open_on_phone(self, url: str):
        self.send({"t": "open_url", "url": url})

    def volume(self, up: bool):
        self.send({"t": "input", "key": "volume_up" if up else "volume_down"})

    # -- media playing on the phone --

    def _media(self, m: dict):
        pid = m.get("id") or m.get("pkg") or ""
        if not m.get("active") or not isinstance(m.get("title"), str):
            # Old phones send one player without an id; inactive then means nothing is playing.
            if m.get("id"):
                self._remove_player(m["id"])
            else:
                self.players.clear()
                self.player_art.clear()
                self.emit("media-changed")
            return
        if m.get("art"):
            try:
                self.player_art[pid] = base64.b64decode(m["art"])
            except ValueError:
                pass
        if m.get("icon") and m.get("pkg"):
            try:
                self.icons[m["pkg"]] = base64.b64decode(m["icon"])
            except ValueError:
                pass
        at = (m.get("positionAt") or time.time() * 1000) / 1000
        since = (m.get("since") or 0) / 1000
        p = Player(id=pid, pkg=m.get("pkg", ""), app=m.get("app", ""), title=m["title"], artist=m.get("artist", ""),
                   album=m.get("album", ""), duration=float(m.get("duration") or 0), playing=bool(m.get("playing")),
                   position=float(m.get("position") or 0), position_at=at, speed=float(m.get("speed") or 1),
                   since=since, can_prev=m.get("canPrev", True) is not False, can_next=m.get("canNext", True) is not False,
                   can_seek=bool(m.get("canSeek")))
        self.players = [x for x in self.players if x.id != pid] + [p]
        self.players.sort(key=lambda x: (not x.playing, -(x.since or x.position_at)))
        self.emit("media-changed")

    def _remove_player(self, pid: str):
        self.players = [x for x in self.players if x.id != pid]
        self.player_art.pop(pid, None)
        self.emit("media-changed")

    @property
    def now_playing(self) -> Player | None:
        return self.players[0] if self.players else None

    def media_command(self, cmd: str, pid: str | None = None, position_ms: float = 0):
        target = pid or (self.now_playing.id if self.now_playing else None)
        if target:
            self.send({"t": "media_cmd", "cmd": cmd, "pos": int(position_ms), "id": target})

    # -- music on this computer, for the phone --

    def _bridge_message(self, cid, m: dict):
        t = m.get("t")
        if t == "media" and isinstance(m.get("tab"), int):
            key = (cid, m["tab"])
            if m.get("art"):
                try:
                    from .media import jpeg_thumb
                    self.browser_art[key] = jpeg_thumb(base64.b64decode(m["art"]))
                except ValueError:
                    pass
            old = self.browser_tracks.get(key)
            playing = bool(m.get("playing"))
            since = (old or {}).get("since", 0) if playing else 0
            if playing and not since:
                since = time.time()
            self.browser_tracks[key] = {
                "key": f"tab|{cid}|{m['tab']}|{m.get('title', '')}", "app": m.get("site", "Browser"),
                "title": m.get("title", ""), "artist": m.get("artist", ""), "album": m.get("album", ""),
                "playing": playing, "dur": float(m.get("dur") or 0), "pos": float(m.get("pos") or 0),
                "since": since, "tab": key,
            }
            self._media_source_changed()
        elif t == "media_end" and isinstance(m.get("tab"), int):
            self.browser_tracks.pop((cid, m["tab"]), None)
            self.browser_art.pop((cid, m["tab"]), None)
            self._media_source_changed()
        elif t == "send_url":
            url = m.get("url", "")
            if url.startswith(("http://", "https://")):
                self.open_on_phone(url)

    def _bridge_closed(self, cid):
        for key in [k for k in self.browser_tracks if k[0] == cid]:
            self.browser_tracks.pop(key, None)
            self.browser_art.pop(key, None)
        self._media_source_changed()

    def computer_track(self) -> dict | None:
        """The newest playing source on this computer (a player, or a browser tab via the extension)."""
        candidates = list(self.browser_tracks.values())
        sys_track = self.computer_media.current()
        if sys_track:
            candidates.append(sys_track)
        candidates = [c for c in candidates if c.get("title")]
        if not candidates:
            return None
        playing = [c for c in candidates if c["playing"]]
        if playing:
            return max(playing, key=lambda c: c.get("since", 0))
        return sys_track or candidates[-1]

    def _media_source_changed(self):
        self._push_computer_media()

    def _media_tick(self):
        if self.phone_wants_media:
            self._push_computer_media(periodic=True)
        return True

    def _push_computer_media(self, periodic=False):
        if not self.phone_wants_media or not self.active:
            return
        t = self.computer_track()
        if not t:
            if self._sent_track is not None:
                self._sent_track = None
                self.send({"t": "mac_media_end"})
            return
        sig = (t["key"], t["playing"], t["title"], t["artist"])
        if not periodic and sig == self._sent_track:
            return
        if periodic and not t["playing"]:
            return
        self._sent_track = sig
        m = {"t": "mac_media", "app": t["app"], "title": t["title"], "artist": t["artist"], "album": t["album"],
             "playing": t["playing"], "dur": t["dur"], "pos": t["pos"]}
        art = None
        if "tab" in t:
            art = self.browser_art.get(t["tab"])
        elif self.computer_media.art and self.computer_media.art[0] == t["key"]:
            art = self.computer_media.art[1]
        art_key = t["key"] + ("+art" if art else "")
        if art and art_key != self._pushed_art_key:
            m["art"] = base64.b64encode(art).decode()
            self._pushed_art_key = art_key
        self.send(m)

    def _computer_media_command(self, cmd: str):
        t = self.computer_track()
        if t and "tab" in t:
            cid, tab = t["tab"]
            self.bridge.send(cid, {"t": "cmd", "tab": tab, "cmd": cmd})
        else:
            self.computer_media.command(cmd)
        GLib.timeout_add(400, lambda: self._push_computer_media() and False)

    # -- Messages (SMS) --

    def refresh_sms(self):
        if not self.active:
            return
        if self.sms_state in ("unknown", "off", "permission"):
            self.sms_state = "loading"
            self.emit("sms-changed")
        self.send({"t": "sms_threads"})
        if self.sms_open:
            self.send({"t": "sms_thread", "id": self.sms_open})

    def open_sms_thread(self, tid: str | None):
        self.sms_open = tid
        if tid:
            self.send({"t": "sms_thread", "id": tid})

    def sms_thread(self, tid) -> dict | None:
        return next((x for x in self.sms_threads if x.get("id") == tid), None)

    def search_contacts(self, q: str):
        self.contact_query = q
        if not q.strip():
            self.contacts = []
            self.emit("contacts-changed")
            return
        self.send({"t": "contacts_search", "q": q})

    def send_sms(self, address: str, text: str, thread_id: str | None):
        body = text.strip()
        if not body or not self.active or (not address and not thread_id):
            return
        local = "local-" + uuid.uuid4().hex
        tid = thread_id or next((x["id"] for x in self.sms_threads
                                 if number_key(x.get("address", "")) == number_key(address) and number_key(address)),
                                None)
        if tid:
            self.sms_messages.setdefault(tid, []).append(
                {"id": local, "body": body, "date": time.time() * 1000, "out": True, "pending": True})
        if not thread_id:
            self.sms_select_address = address
        self.send({"t": "sms_send", "address": address, "text": body, "id": local, "thread": thread_id or ""})
        self.emit("sms-changed")

    # -- screen mirroring --

    def start_mirror(self):
        if not self.active:
            return
        self.mirror = "starting"
        self.control_unavailable = False
        self.send({"t": "mirror_start"})
        self.emit("mirror-changed")

    def stop_mirror(self):
        if self.mirror == "idle":
            return
        self.mirror = "idle"
        self.send({"t": "mirror_stop"})
        if self.mirror_sink:
            self.mirror_sink.reset()
        self.emit("mirror-changed")

    def request_key_frame(self):
        self.send({"t": "mirror_key"})

    def send_input(self, msg: dict):
        self.send({"t": "input", **msg})

    # -- clipboard --

    def local_copy(self, value):
        """Something was copied on this computer (text or PNG bytes)."""
        if isinstance(value, str):
            if not value.strip() or (self.clips and self.clips[0].kind == "text" and self.clips[0].text == value):
                return
            self._record(Clip(uuid.uuid4().hex, "text", text=value))
            self.send({"t": "clip", "text": value, "explicit": False})
        elif isinstance(value, bytes) and value:
            if self.clips and self.clips[0].png == value:
                return
            self._record(Clip(uuid.uuid4().hex, "image", png=value))
            self._send_png(value)

    def send_clipboard_now(self, value):
        if isinstance(value, str) and value:
            self.send({"t": "clip", "text": value, "explicit": True})
        elif isinstance(value, bytes) and value:
            self._send_png(value)

    def _send_png(self, png: bytes):
        path = Path(GLib.get_tmp_dir()) / f"macndroid-clip-{uuid.uuid4().hex}.png"
        path.write_bytes(png)
        self._send_file(path, clip=True)

    def _record(self, c: Clip):
        self.clips = [c] + [x for x in self.clips if not (x.kind == c.kind and x.text == c.text and x.png == c.png)]
        del self.clips[HISTORY_LIMIT:]
        self.emit("clips-changed")
        self._push_history()

    def _clip(self, cid) -> Clip | None:
        return next((c for c in self.clips if c.id == cid), None)

    def use_clip(self, c: Clip):
        self.emit("set-clipboard", c.text if c.kind == "text" else c.png)

    def clear_history(self):
        self.clips.clear()
        self.emit("clips-changed")
        self._push_history()

    def _push_history(self):
        items = []
        for c in self.clips:
            item = {"id": c.id, "kind": c.kind, "from": "phone" if c.from_phone else "mac", "time": int(c.time * 1000)}
            if c.kind == "text":
                item["text"] = c.text[:2000]
                item["truncated"] = len(c.text) > 2000
            items.append(item)
        self.send({"t": "clip_history", "items": items})

    # -- files --

    def send_files(self, paths: list[str]):
        for p in paths:
            if os.path.isdir(p):
                self._send_folder(Path(p))
            elif os.path.isfile(p):
                self._send_file(Path(p))

    def _send_folder(self, folder: Path):
        """Zipped first (on a background thread), then sent like a file with `folder: true`."""
        if not self.active:
            return
        tid = uuid.uuid4().hex
        self.transfers.insert(0, Transfer(tid, folder.name, 0, True, str(folder), folder=True))
        self.emit("transfers-changed")

        def zip_it():
            try:
                z = folders.zip_folder(folder)
            except OSError:
                z = None
            GLib.idle_add(zipped, z)

        def zipped(z):
            t = next((x for x in self.transfers if x.id == tid), None)
            if not z or not t:
                self._update_transfer(tid, state="failed")
                return False
            t.size = z.stat().st_size
            self._send_file(z, tid=tid, folder=True, cleanup=True)
            return False

        threading.Thread(target=zip_it, daemon=True).start()

    def _send_file(self, path: Path, clip: bool = False, tid: str | None = None, folder: bool = False,
                   cleanup: bool = False):
        s = self.active
        if not s:
            if tid:
                self._update_transfer(tid, state="failed")
            return
        new = tid is None
        tid = tid or uuid.uuid4().hex
        size = path.stat().st_size
        if not clip and new:
            self.transfers.insert(0, Transfer(tid, path.name, size, True, str(path)))
            self.emit("transfers-changed")

        def stream():
            start = {"t": "file_start", "id": tid, "name": path.name, "size": size,
                     "mime": "application/zip" if folder else
                     (mimetypes.guess_type(path.name)[0] or "application/octet-stream")}
            if clip:
                start["clip"] = True
            if folder:
                start["folder"] = True
            ok = s.send(start)
            sent, last = 0, 0.0
            with open(path, "rb") as f:
                while ok:
                    chunk = f.read(CHUNK)
                    if not chunk:
                        break
                    ok = s.send({"t": "file_chunk", "id": tid, "data": base64.b64encode(chunk).decode()})
                    sent += len(chunk)
                    if time.time() - last > 0.1:
                        last = time.time()
                        GLib.idle_add(self._update_transfer, tid, sent, None)
            ok = ok and s.send({"t": "file_end", "id": tid})
            if clip or cleanup:
                path.unlink(missing_ok=True)
            if not clip:
                GLib.idle_add(self._sent_done, tid, sent, ok)

        threading.Thread(target=stream, daemon=True).start()

    def _sent_done(self, tid, sent, ok):
        self._update_transfer(tid, sent, "done" if ok else "failed")
        t = next((x for x in self.transfers if x.id == tid), None)
        if t and not any(x.to_phone and x.state == "active" for x in self.transfers):
            title = f"Sent to {self.phone_name}" if ok else "Couldn't send"
            self.emit("post-notification", (title, t.name, ""))
        return False

    def _update_transfer(self, tid, sent=None, state=None):
        for t in self.transfers:
            if t.id == tid:
                if sent is not None:
                    t.bytes = sent
                if state:
                    t.state = state
                    if state == "done":
                        t.bytes = max(t.bytes, t.size)
        self.emit("transfers-changed")
        return False

    def _file(self, t: str, m: dict):
        tid = safe_id(m.get("id") or "")
        if not tid:
            return
        if t == "file_start":
            clip = bool(m.get("clip"))
            is_folder = bool(m.get("folder")) and not clip
            name = m.get("name", "file")
            if clip:
                path = unique_path(Path(GLib.get_tmp_dir()), f"macndroid-clip-{tid}.png")
            elif is_folder:
                path = Path(GLib.get_tmp_dir()) / f"macndroid-folder-{tid}.zip"
            else:
                path = unique_path(downloads_dir(), name)
            self._incoming[tid] = (open(path, "wb"), path, clip, name if is_folder else None)
            if not clip:
                shown = (name[:-4] if name.lower().endswith(".zip") else name) if is_folder else path.name
                self.transfers.insert(0, Transfer(tid, shown, int(m.get("size") or 0), False, str(path),
                                                  folder=is_folder))
                self.emit("transfers-changed")
        elif t == "file_chunk":
            entry = self._incoming.get(tid)
            if entry:
                try:
                    entry[0].write(base64.b64decode(m.get("data", "")))
                except ValueError:
                    return
                self._update_transfer(tid, entry[0].tell())
        elif t == "file_end":
            entry = self._incoming.pop(tid, None)
            if not entry:
                return
            fh, path, clip, folder_name = entry
            fh.close()
            if folder_name:
                def unpack():
                    out = folders.unpack(path, downloads_dir(), folder_name)
                    GLib.idle_add(self._folder_done, tid, out)
                threading.Thread(target=unpack, daemon=True).start()
                return
            if clip:
                png = path.read_bytes()
                path.unlink(missing_ok=True)
                self.emit("set-clipboard", png)
                self._record(Clip(uuid.uuid4().hex, "image", png=png, from_phone=True))
                self.emit("post-notification", (f"Image copied from {self.phone_name}", "Ready to paste", ""))
                return
            self._update_transfer(tid, state="done")
            self._received.append(path)
            if self._received_timer:
                GLib.source_remove(self._received_timer)
            self._received_timer = GLib.timeout_add(800, self._received_summary)

    def _folder_done(self, tid, out):
        t = next((x for x in self.transfers if x.id == tid), None)
        if out and t:
            t.path, t.name = str(out), out.name
            self._update_transfer(tid, state="done")
            self._received.append(out)
            if self._received_timer:
                GLib.source_remove(self._received_timer)
            self._received_timer = GLib.timeout_add(800, self._received_summary)
        else:
            self._update_transfer(tid, state="failed")
        return False

    def _received_summary(self):
        """Several files in a row get one notification, after the last one."""
        self._received_timer = 0
        files, self._received = self._received, []
        if files:
            body = files[0].name if len(files) == 1 else f"{len(files)} items in Downloads"
            self.emit("post-notification", (f"Received from {self.phone_name}", body, ""))
        return False
