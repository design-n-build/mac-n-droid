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

from .connection import Server, Session
from .discovery import Advertiser
from .identity import Identity, computer_name, local_ipv4

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


def downloads_dir() -> Path:
    d = GLib.get_user_special_dir(GLib.UserDirectory.DIRECTORY_DOWNLOAD) or os.path.expanduser("~/Downloads")
    Path(d).mkdir(parents=True, exist_ok=True)
    return Path(d)


def unique_path(folder: Path, name: str) -> Path:
    name = os.path.basename(name).replace("/", "-") or "file"
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
    }

    def __init__(self):
        super().__init__()
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
        self.server = Server(self.identity, self.name, "linux", lambda f, *a: GLib.idle_add(lambda: f(*a) and False))
        self.server.on_authenticated = self._connected
        self.server.on_message = self._message
        self.server.on_closed = self._closed
        self.advertiser = Advertiser()

    def start(self):
        port = self.server.start()
        self.advertiser.start(self.name, port, self.identity.pc_id)
        GLib.timeout_add_seconds(30, self._refresh_addresses)

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
        self.identity.reset()
        if self.active:
            self.active.close("Pairing reset")
        self.emit("pairing-changed")

    # -- connection --

    def _connected(self, s: Session):
        if self.active and self.active is not s:
            self.active.close("Replaced by a new connection")
        self.active = s
        self.phone = s.device
        self.last_phone_name = s.device.get("name", "")
        self.last_error = ""
        self.emit("status-changed")
        self._push_history()

    def _closed(self, s: Session, reason):
        if reason and "Pair it again" in reason:
            self.last_error = reason
            self.emit("status-changed")
        if self.active is not s:
            return
        self.active = None
        self.battery = None
        self.ringing = False
        for n in self.notifications:
            self.emit("withdraw-notification", n.key)
        self.notifications.clear()
        for tid, (fh, path, _clip) in list(self._incoming.items()):
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

    def send(self, msg: dict):
        s = self.active
        if s:
            threading.Thread(target=s.send, args=(msg,), daemon=True).start()

    # -- messages from the phone --

    def _message(self, s: Session, m: dict):
        if s is not self.active:
            return
        t = m.get("t")
        if t == "battery":
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
            if os.path.isfile(p):
                self._send_file(Path(p))

    def _send_file(self, path: Path, clip: bool = False):
        s = self.active
        if not s:
            return
        tid = uuid.uuid4().hex
        size = path.stat().st_size
        if not clip:
            self.transfers.insert(0, Transfer(tid, path.name, size, True, str(path)))
            self.emit("transfers-changed")

        def stream():
            start = {"t": "file_start", "id": tid, "name": path.name, "size": size,
                     "mime": mimetypes.guess_type(path.name)[0] or "application/octet-stream"}
            if clip:
                start["clip"] = True
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
            if clip:
                path.unlink(missing_ok=True)
            else:
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
        tid = m.get("id")
        if not tid:
            return
        if t == "file_start":
            clip = bool(m.get("clip"))
            folder = Path(GLib.get_tmp_dir()) if clip else downloads_dir()
            path = unique_path(folder, f"macndroid-clip-{tid}.png" if clip else m.get("name", "file"))
            self._incoming[tid] = (open(path, "wb"), path, clip)
            if not clip:
                self.transfers.insert(0, Transfer(tid, path.name, int(m.get("size") or 0), False, str(path)))
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
            fh, path, clip = entry
            fh.close()
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

    def _received_summary(self):
        """Several files in a row get one notification, after the last one."""
        self._received_timer = 0
        files, self._received = self._received, []
        if files:
            body = files[0].name if len(files) == 1 else f"{len(files)} files in Downloads"
            self.emit("post-notification", (f"Received from {self.phone_name}", body, ""))
        return False
