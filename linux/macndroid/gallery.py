"""The phone's photo and video library (Photos), loaded a page at a time, as on the Mac (Gallery.swift) and Windows
(Gallery.cs). Thumbnails are asked for as tiles are made; the full file only when you open or save one. Opened files
are kept in ~/.cache/macndroid/gallery so opening one again is instant; they are deleted if Photos is turned off on
the phone, or the phone is forgotten. Runs on the UI thread.
"""
import base64
import os
import shutil
from dataclasses import dataclass
from pathlib import Path

from gi.repository import GLib, GObject

PAGE_SIZE = 120


@dataclass
class GalleryItem:
    id: str
    is_video: bool
    name: str
    date: float  # seconds
    size: int
    width: int
    height: int
    duration: float  # seconds


def cache_dir() -> Path:
    d = Path(os.environ.get("MACNDROID_CONFIG") or GLib.get_user_cache_dir()) / ("gallery" if os.environ.get(
        "MACNDROID_CONFIG") else "macndroid/gallery")
    d.mkdir(parents=True, exist_ok=True)
    return d


def safe_name(name: str) -> str:
    clean = os.path.basename(str(name).replace("\\", "/")).replace("\0", "").strip()
    return "photo" if clean in ("", ".", "..") else clean


def cache_file_name(gid: str, name: str) -> str:
    return "".join(c for c in gid if c.isascii() and (c.isalnum() or c in "-_"))[:60] + "-" + safe_name(name)


class GalleryModel(GObject.Object):
    __gsignals__ = {
        # The list, filter or access changed (rebuild the grid).
        "changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        # Items were added to the end of the list: the first new index.
        "appended": (GObject.SignalFlags.RUN_FIRST, None, (int,)),
        "thumb": (GObject.SignalFlags.RUN_FIRST, None, (str,)),
        # A file opened with "view" has arrived: open it with the default app.
        "opened": (GObject.SignalFlags.RUN_FIRST, None, (str,)),
    }

    def __init__(self, send):
        super().__init__()
        self._send = send
        self.items: list[GalleryItem] = []
        self.albums: list[dict] = []
        self.total = 0
        self.access = "unknown"  # unknown | full | photos | partial | none | off
        self.loading = False
        self.kind = "all"
        self.album: str | None = None
        self.thumbs: dict[str, bytes] = {}
        self.opening: set[str] = set()
        self.connected = False
        self._requested_offset = None
        self._wanted: list[str] = []
        self._asked: set[str] = set()
        self._flush_queued = False

    @property
    def has_more(self) -> bool:
        return len(self.items) < self.total

    def set_filter(self, kind: str, album: str | None):
        if kind == self.kind and album == self.album:
            return
        self.kind, self.album = kind, album
        self.reload()

    def reload(self):
        self.items.clear()
        self.total = 0
        self._requested_offset = None
        self.emit("changed")
        self.load_more()

    def load_if_needed(self):
        if not self.items and not self.loading:
            self.reload()

    def load_more(self):
        if not self.connected:
            return
        offset = len(self.items)
        if self._requested_offset == offset:
            return
        self._requested_offset = offset
        self.loading = True
        msg = {"t": "gallery_list", "offset": offset, "limit": PAGE_SIZE, "kind": self.kind}
        if self.album is not None:
            msg["album"] = self.album
        self._send(msg)

    def ask_for_access(self):
        self._send({"t": "gallery_ask"})

    def want_thumb(self, gid: str):
        """A tile was made: batch up its thumbnail request."""
        if gid in self.thumbs or gid in self._asked:
            return
        self._asked.add(gid)
        self._wanted.append(gid)
        if not self._flush_queued:
            self._flush_queued = True
            GLib.timeout_add(50, self._flush)

    def _flush(self):
        self._flush_queued = False
        if self.connected:
            while self._wanted:
                batch, self._wanted = self._wanted[:40], self._wanted[40:]
                self._send({"t": "gallery_thumbs", "ids": batch, "size": 280})
        return False

    def cached_path(self, item: GalleryItem) -> Path | None:
        p = cache_dir() / cache_file_name(item.id, item.name)
        return p if p.exists() else None

    def open(self, item: GalleryItem):
        """Opens with the default app (fetching the original first, unless it was opened before)."""
        cached = self.cached_path(item)
        if cached:
            self.emit("opened", str(cached))
            return
        if not self.connected or item.id in self.opening:
            return
        self.opening.add(item.id)
        self.emit("changed")
        self._send({"t": "gallery_get", "id": item.id, "mode": "view"})

    def save(self, items: list[GalleryItem], downloads: Path):
        """Originals to Downloads, through the normal transfer list (or copied, if opened before)."""
        for item in items:
            cached = self.cached_path(item)
            if cached:
                dest = downloads / safe_name(item.name)
                stem, ext = os.path.splitext(dest.name)
                n = 2
                while dest.exists():
                    dest = downloads / f"{stem} {n}{ext}"
                    n += 1
                try:
                    shutil.copyfile(cached, dest)
                except OSError:
                    pass
                continue
            self._send({"t": "gallery_get", "id": item.id, "mode": "save"})

    # -- from the phone --

    def handle(self, m: dict):
        t = m.get("t")
        if t == "gallery":
            self.access = m.get("access") or "none"
            if self.access == "off":
                self._forget()
                self.loading = False
                self.emit("changed")
                return
            # Ignore pages for a filter that's no longer showing.
            if (m.get("kind") or "all") != self.kind or m.get("album") != self.album:
                return
            if int(m.get("offset") or 0) != len(self.items):
                self.loading = False
                self.emit("changed")
                return
            first = len(self.items)
            for o in m.get("items") or []:
                if isinstance(o, dict) and o.get("id"):
                    self.items.append(GalleryItem(str(o["id"]), bool(o.get("v")), o.get("name") or "Photo",
                                                  (o.get("date") or 0) / 1000, int(o.get("size") or 0),
                                                  int(o.get("w") or 0), int(o.get("h") or 0),
                                                  (o.get("dur") or 0) / 1000))
            self.total = int(m["total"]) if m.get("total") is not None else len(self.items)
            if isinstance(m.get("albums"), list):
                self.albums = [a for a in m["albums"] if isinstance(a, dict) and a.get("id") is not None]
            self.loading = False
            if first == 0:
                self.emit("changed")
            else:
                self.emit("appended", first)
        elif t == "gallery_thumb":
            gid, d = m.get("id"), m.get("d")
            if not gid or not d:
                return
            try:
                self.thumbs[gid] = base64.b64decode(d)
            except ValueError:
                return
            self.emit("thumb", gid)
        elif t == "gallery_access":
            access = m.get("access") or "none"
            if access == "off":
                self.access = access
                self._forget()
                self.emit("changed")
            elif access != self.access or not self.items:
                self.access = access
                self.reload()

    def forget_phone(self):
        """A different phone, or the phone was forgotten: nothing of the old one's photos stays."""
        self._forget()
        self.access = "unknown"
        self.emit("changed")

    def _forget(self):
        self.items.clear()
        self.albums = []
        self.total = 0
        self.thumbs.clear()
        self._asked.clear()
        self.opening.clear()
        self._requested_offset = None
        for f in cache_dir().iterdir():
            try:
                f.unlink()
            except OSError:
                pass

    def arrived(self, gid: str, path: str):
        """An original asked for with "view" has arrived."""
        self.opening.discard(gid)
        self.emit("changed")
        self.emit("opened", path)

    def phone_connected(self):
        self.connected = True
        self._asked = set(self.thumbs)
        if self.items or self.access != "unknown":
            self.reload()

    def phone_disconnected(self):
        self.connected = False
        self.loading = False
        self.opening.clear()
        self._requested_offset = None
        self._wanted.clear()
        self.emit("changed")
