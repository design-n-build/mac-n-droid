"""Phone screen mirroring: decodes the phone's H.264 (mirror_cfg / mirror_frame in PROTOCOL.md) with GStreamer
and shows it in a Gtk.Picture. Clicks and drags become touches, the right button is Back, the middle button
is Home, and typing goes to the phone (through the phone-control accessibility service).

GStreamer picks whatever H.264 decoder is installed (FFmpeg's, OpenH264, or the graphics card's). The Flatpak
always has one; a computer running from source may need `gstreamer1.0-libav` (Ubuntu) or
`gstreamer1-plugin-openh264` (Fedora).
"""
import base64
import logging
import math
import threading
import time

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Gdk", "4.0")
from gi.repository import Gdk, GLib, Gtk  # noqa: E402

log = logging.getLogger("macndroid")

try:
    gi.require_version("Gst", "1.0")
    gi.require_version("GstApp", "1.0")
    from gi.repository import Gst, GstApp  # noqa: F401
    Gst.init(None)
    HAVE_GST = True
except (ImportError, ValueError):
    HAVE_GST = False

MAX_HEIGHT = 1600  # Decoded frames are scaled down to this, to keep copying cheap.


def decoder_available() -> bool:
    if not HAVE_GST:
        return False
    for name in ("avdec_h264", "openh264dec", "vah264dec", "vaapih264dec", "nvh264dec", "v4l2h264dec", "vtdec"):
        if Gst.ElementFactory.find(name):
            return True
    return False


class Decoder:
    """Feeds access units in, hands RGBA frames to `on_frame(texture)` on the UI thread."""

    def __init__(self, on_frame, need_key_frame):
        self.on_frame = on_frame
        self.need_key_frame = need_key_frame
        self.pipeline = None
        self.src = None
        self.cfg = b""
        self.waiting_for_key = True
        self.size = (0, 0)
        self._start = 0.0
        self._pending = None
        self._lock = threading.Lock()
        self._idle = 0

    def _build(self):
        if not HAVE_GST:
            return
        desc = ("appsrc name=src is-live=true format=time do-timestamp=false "
                "caps=video/x-h264,stream-format=byte-stream,alignment=au ! h264parse ! decodebin ! "
                "videoconvert ! videoscale ! video/x-raw,format=RGBA ! "
                "appsink name=sink emit-signals=true max-buffers=1 drop=true sync=false")
        self.pipeline = Gst.parse_launch(desc)
        self.src = self.pipeline.get_by_name("src")
        sink = self.pipeline.get_by_name("sink")
        sink.connect("new-sample", self._sample)
        bus = self.pipeline.get_bus()
        bus.add_signal_watch()
        bus.connect("message::error", self._error)
        self.pipeline.set_state(Gst.State.PLAYING)
        self._start = time.monotonic()

    def _error(self, _bus, msg):
        err, _dbg = msg.parse_error()
        log.warning("Mirroring decoder error: %s", err.message)
        self.reset()

    def config(self, m: dict):
        try:
            self.cfg = base64.b64decode(m.get("d", ""))
        except ValueError:
            self.cfg = b""
        self.size = (int(m.get("w") or 0), int(m.get("h") or 0))
        self.waiting_for_key = True

    def frame(self, m: dict):
        try:
            data = base64.b64decode(m.get("d", ""))
        except ValueError:
            return
        key = bool(m.get("k"))
        if self.waiting_for_key:
            if not key or not self.cfg:
                return
            self.waiting_for_key = False
        if self.pipeline is None:
            self._build()
            if self.pipeline is None:
                return
        if key and self.cfg and not data.startswith(self.cfg):
            data = self.cfg + data
        buf = Gst.Buffer.new_wrapped(data)
        buf.pts = int((time.monotonic() - self._start) * Gst.SECOND)
        if self.src.emit("push-buffer", buf) != Gst.FlowReturn.OK:
            self.waiting_for_key = True
            self.need_key_frame()

    def _sample(self, sink):
        sample = sink.emit("pull-sample")
        if sample is None:
            return Gst.FlowReturn.OK
        caps = sample.get_caps().get_structure(0)
        w, h = caps.get_value("width"), caps.get_value("height")
        buf = sample.get_buffer()
        ok, info = buf.map(Gst.MapFlags.READ)
        if not ok:
            return Gst.FlowReturn.OK
        data = GLib.Bytes.new(info.data)
        buf.unmap(info)
        with self._lock:
            self._pending = (w, h, data)
            if not self._idle:
                self._idle = GLib.idle_add(self._show)
        return Gst.FlowReturn.OK

    def _show(self):
        with self._lock:
            pending, self._pending, self._idle = self._pending, None, 0
        if pending:
            w, h, data = pending
            tex = Gdk.MemoryTexture.new(w, h, Gdk.MemoryFormat.R8G8B8A8, data, w * 4)
            self.on_frame(tex)
        return False

    def reset(self):
        if self.pipeline is not None:
            self.pipeline.set_state(Gst.State.NULL)
            self.pipeline = None
            self.src = None
        self.waiting_for_key = True


class MirrorView(Gtk.Picture):
    """The phone's screen. Sends `input` messages through `send(dict)`."""

    def __init__(self, send):
        super().__init__(can_shrink=True, content_fit=Gtk.ContentFit.CONTAIN, hexpand=True, vexpand=True,
                         focusable=True, can_focus=True)
        self.send = send
        self._points = []
        self._down_at = 0.0
        self._last = (0, 0)
        self._scroll = [0.0, 0.0]
        self._scroll_origin = None
        self._scroll_timer = 0

        click = Gtk.GestureClick(button=0)
        click.connect("pressed", self._pressed)
        self.add_controller(click)
        drag = Gtk.GestureDrag(button=Gdk.BUTTON_PRIMARY)
        drag.connect("drag-begin", self._drag_begin)
        drag.connect("drag-update", self._drag_update)
        drag.connect("drag-end", self._drag_end)
        self.add_controller(drag)
        scroll = Gtk.EventControllerScroll(flags=Gtk.EventControllerScrollFlags.BOTH_AXES)
        scroll.connect("scroll", self._scrolled)
        self.add_controller(scroll)
        self._motion = Gtk.EventControllerMotion()
        self.add_controller(self._motion)
        keys = Gtk.EventControllerKey()
        keys.connect("key-pressed", self._key)
        self.add_controller(keys)

    def _video_rect(self):
        p = self.get_paintable()
        w, h = self.get_width(), self.get_height()
        if not p or not p.get_intrinsic_width():
            return 0, 0, w, h
        pw, ph = p.get_intrinsic_width(), p.get_intrinsic_height()
        scale = min(w / pw, h / ph)
        vw, vh = pw * scale, ph * scale
        return (w - vw) / 2, (h - vh) / 2, vw, vh

    def _norm(self, x, y):
        rx, ry, rw, rh = self._video_rect()
        return [min(max((x - rx) / max(rw, 1), 0), 1), min(max((y - ry) / max(rh, 1), 0), 1)]

    def _pressed(self, gesture, _n, _x, _y):
        self.grab_focus()
        button = gesture.get_current_button()
        if button == Gdk.BUTTON_SECONDARY:
            self.send({"key": "back"})
        elif button == Gdk.BUTTON_MIDDLE:
            self.send({"key": "home"})

    def _drag_begin(self, _g, x, y):
        self._origin = (x, y)
        self._points = [self._norm(x, y)]
        self._down_at = time.monotonic()
        self._last = (x, y)

    def _drag_update(self, _g, dx, dy):
        x, y = self._origin[0] + dx, self._origin[1] + dy
        if math.hypot(x - self._last[0], y - self._last[1]) <= 3:
            return
        self._last = (x, y)
        self._points.append(self._norm(x, y))

    def _drag_end(self, _g, dx, dy):
        if not self._points:
            return
        self._points.append(self._norm(self._origin[0] + dx, self._origin[1] + dy))
        elapsed = (time.monotonic() - self._down_at) * 1000
        moved = len(self._points) > 2
        # Quick clicks become crisp taps; holds become long presses; drags keep their speed (flings work).
        dur = max(elapsed, 80) if moved else (50 if elapsed < 350 else elapsed)
        self.send({"p": thin(self._points, 40), "dur": int(min(dur, 10_000))})
        self._points = []

    def _scrolled(self, _c, dx, dy):
        if self._scroll_origin is None:
            ok, x, y = False, 0, 0
            try:
                ev = self._motion.get_current_event()
                if ev:
                    ok, x, y = True, *ev.get_position()
            except (AttributeError, TypeError):
                ok = False
            self._scroll_origin = self._norm(x, y) if ok else [0.5, 0.5]
        # Wheel clicks are about 1 step each; scale them to a short swipe. Content follows the fingers.
        self._scroll[0] -= dx * 40
        self._scroll[1] -= dy * 40
        if self._scroll_timer:
            GLib.source_remove(self._scroll_timer)
        self._scroll_timer = GLib.timeout_add(60, self._flush_scroll)
        return True

    def _flush_scroll(self):
        self._scroll_timer = 0
        o = self._scroll_origin
        self._scroll_origin = None
        if not o:
            return False
        _rx, _ry, rw, rh = self._video_rect()
        dx, dy = self._scroll[0] / max(rw, 1), self._scroll[1] / max(rh, 1)
        self._scroll = [0.0, 0.0]
        c = lambda v: min(max(v, -0.6), 0.6)  # noqa: E731
        end = [min(max(o[0] + c(dx), 0), 1), min(max(o[1] + c(dy), 0.02), 0.98)]
        if abs(end[0] - o[0]) + abs(end[1] - o[1]) > 0.005:
            self.send({"p": [o, end], "dur": 120})
        return False

    def _key(self, _c, keyval, _code, state):
        if state & Gdk.ModifierType.CONTROL_MASK:
            if keyval in (Gdk.KEY_v, Gdk.KEY_V):
                def got(cb, res):
                    try:
                        text = cb.read_text_finish(res)
                    except GLib.Error:
                        return
                    if text:
                        self.send({"text": text})
                self.get_clipboard().read_text_async(None, got)
                return True
            return False
        if keyval == Gdk.KEY_BackSpace or keyval == Gdk.KEY_Delete:
            self.send({"key": "backspace"})
        elif keyval in (Gdk.KEY_Return, Gdk.KEY_KP_Enter):
            self.send({"key": "enter"})
        elif keyval == Gdk.KEY_Escape:
            self.send({"key": "back"})
        else:
            ch = Gdk.keyval_to_unicode(keyval)
            if ch and ch >= 32 and ch != 127:
                self.send({"text": chr(ch)})
            else:
                return False
        return True


def thin(pts, n):
    if len(pts) <= n:
        return pts
    step = (len(pts) - 1) / (n - 1)
    return [pts[round(i * step)] for i in range(n)]
