"""What's playing on this computer, for the phone's "Music on your computer" (mac_media in PROTOCOL.md).

Reads MPRIS, the standard every Linux player speaks (Spotify, Rhythmbox, VLC, Firefox, Chrome…). The most
recently started player that's playing wins; otherwise the last one that played. Runs on the UI thread.
"""
import logging
import threading
import time
import urllib.request

from gi.repository import Gio, GLib, GObject

log = logging.getLogger("macndroid")
PREFIX = "org.mpris.MediaPlayer2."
PATH = "/org/mpris/MediaPlayer2"
PLAYER = "org.mpris.MediaPlayer2.Player"


def jpeg_thumb(data: bytes, size: int = 512) -> bytes:
    """Any image → a JPEG at most `size` pixels on its longer side (b"" if it can't be read)."""
    try:
        import gi
        gi.require_version("GdkPixbuf", "2.0")
        from gi.repository import GdkPixbuf
        loader = GdkPixbuf.PixbufLoader()
        loader.write(data)
        loader.close()
        pb = loader.get_pixbuf()
        w, h = pb.get_width(), pb.get_height()
        scale = min(1.0, size / max(w, h))
        if scale < 1:
            pb = pb.scale_simple(max(1, int(w * scale)), max(1, int(h * scale)), GdkPixbuf.InterpType.BILINEAR)
        if pb.get_has_alpha():
            pb = pb.composite_color_simple(pb.get_width(), pb.get_height(), GdkPixbuf.InterpType.NEAREST, 255, 8,
                                           0xFFFFFF, 0xFFFFFF)
        ok, buf = pb.save_to_bufferv("jpeg", ["quality"], ["85"])
        return bytes(buf) if ok else b""
    except Exception:  # noqa: BLE001 — a bad image just means no artwork
        return b""


class _Player:
    def __init__(self, name: str, proxy: Gio.DBusProxy, app: str):
        self.name = name
        self.proxy = proxy
        self.app = app
        self.since = 0.0  # when it last started playing
        self.touched = time.time()

    def prop(self, key, default=None):
        v = self.proxy.get_cached_property(key)
        return v.unpack() if v is not None else default

    @property
    def playing(self) -> bool:
        return self.prop("PlaybackStatus", "Stopped") == "Playing"

    @property
    def metadata(self) -> dict:
        return self.prop("Metadata", {}) or {}

    def position(self) -> float:
        """Seconds. Players don't announce position changes, so it's asked for each time."""
        try:
            v = self.proxy.get_connection().call_sync(
                self.name, PATH, "org.freedesktop.DBus.Properties", "Get", GLib.Variant("(ss)", (PLAYER, "Position")),
                GLib.VariantType("(v)"), Gio.DBusCallFlags.NONE, 500, None)
            return max(0.0, v.unpack()[0] / 1e6)
        except GLib.Error:
            return 0.0


class ComputerMedia(GObject.Object):
    __gsignals__ = {"changed": (GObject.SignalFlags.RUN_FIRST, None, ())}

    def __init__(self):
        super().__init__()
        self._players: dict[str, _Player] = {}
        self._bus = None
        self.art: tuple[str, bytes] | None = None  # (track key, JPEG)
        self._art_loading = ""

    def start(self):
        try:
            self._bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
        except GLib.Error as e:
            log.warning("No session bus, so music on this computer can't be shown: %s", e.message)
            return
        self._bus.signal_subscribe("org.freedesktop.DBus", "org.freedesktop.DBus", "NameOwnerChanged",
                                   "/org/freedesktop/DBus", None, Gio.DBusSignalFlags.NONE, self._owner_changed)
        try:
            names = self._bus.call_sync("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus",
                                        "ListNames", None, GLib.VariantType("(as)"), Gio.DBusCallFlags.NONE, 2000,
                                        None).unpack()[0]
        except GLib.Error:
            names = []
        for n in names:
            if n.startswith(PREFIX):
                self._add(n)

    def _owner_changed(self, _conn, _sender, _path, _iface, _signal, params):
        name, old, new = params.unpack()
        if not name.startswith(PREFIX):
            return
        if new and not old:
            self._add(name)
        elif old and not new and self._players.pop(name, None):
            self.emit("changed")

    def _add(self, name: str):
        try:
            proxy = Gio.DBusProxy.new_sync(self._bus, Gio.DBusProxyFlags.GET_INVALIDATED_PROPERTIES, None, name, PATH,
                                           PLAYER, None)
            ident = self._bus.call_sync(name, PATH, "org.freedesktop.DBus.Properties", "Get",
                                        GLib.Variant("(ss)", ("org.mpris.MediaPlayer2", "Identity")),
                                        GLib.VariantType("(v)"), Gio.DBusCallFlags.NONE, 1000, None).unpack()[0]
        except GLib.Error:
            ident = ""
            try:
                proxy = Gio.DBusProxy.new_sync(self._bus, Gio.DBusProxyFlags.NONE, None, name, PATH, PLAYER, None)
            except GLib.Error:
                return
        app = ident or name[len(PREFIX):].split(".")[0].capitalize()
        p = _Player(name, proxy, app)
        if p.playing:
            p.since = time.time()
        self._players[name] = p
        proxy.connect("g-properties-changed", lambda *_a, pl=p: self._props_changed(pl))
        self.emit("changed")

    def _props_changed(self, p: _Player):
        playing = p.playing
        if playing and not p.since:
            p.since = time.time()
        elif not playing:
            p.since = 0.0
        p.touched = time.time()
        self.emit("changed")

    # -- what's playing --

    def _current_player(self) -> _Player | None:
        with_title = [p for p in self._players.values() if p.metadata.get("xesam:title")]
        playing = sorted((p for p in with_title if p.playing), key=lambda p: p.since, reverse=True)
        if playing:
            return playing[0]
        paused = [p for p in with_title if p.prop("PlaybackStatus") == "Paused"]
        return max(paused, key=lambda p: p.touched) if paused else None

    def current(self) -> dict | None:
        """{key, app, title, artist, album, playing, dur, pos, since} or None."""
        p = self._current_player()
        if not p:
            return None
        md = p.metadata
        artist = md.get("xesam:artist") or []
        if isinstance(artist, str):
            artist = [artist]
        title = md.get("xesam:title", "")
        track = {
            "key": f"{p.name}|{md.get('mpris:trackid', '')}|{title}",
            "app": p.app,
            "title": title,
            "artist": ", ".join(artist),
            "album": md.get("xesam:album", "") or "",
            "playing": p.playing,
            "dur": (md.get("mpris:length") or 0) / 1e6,
            "pos": p.position(),
            "since": p.since,
        }
        self._load_art(track["key"], md.get("mpris:artUrl", "") or "")
        return track

    def _load_art(self, key: str, url: str):
        if not url or (self.art and self.art[0] == key) or self._art_loading == key:
            return
        self._art_loading = key

        def load():
            data = b""
            try:
                if url.startswith("file://"):
                    data = Gio.File.new_for_uri(url).load_contents(None)[1]
                elif url.startswith(("http://", "https://")):
                    with urllib.request.urlopen(url, timeout=5) as r:
                        data = r.read(4_000_000)
            except (GLib.Error, OSError, ValueError):
                data = b""
            jpeg = jpeg_thumb(data) if data else b""
            GLib.idle_add(done, jpeg)

        def done(jpeg):
            if self._art_loading == key:
                self._art_loading = ""
                if jpeg:
                    self.art = (key, jpeg)
                    self.emit("changed")
            return False

        threading.Thread(target=load, daemon=True).start()

    def command(self, cmd: str) -> bool:
        p = self._current_player()
        method = {"play": "Play", "pause": "Pause", "toggle": "PlayPause", "next": "Next", "prev": "Previous"}.get(cmd)
        if not p or not method:
            return False
        p.proxy.call(method, None, Gio.DBusCallFlags.NONE, 1000, None, None)
        return True
