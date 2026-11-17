"""For CI only (MACNDROID_SHOTS=folder): waits for the phone (tools/fake_phone.py), opens every page at a normal
and a wide window size, saves each as a PNG, then quits. The pictures are the build's "linux-screenshots" artifact,
so the layout can be checked without a Linux PC."""
import logging
import os

from gi.repository import GLib, Gtk

from .window import PAGES, SETUP

SIZES = [("normal", 1100, 760), ("wide", 1600, 900)]
log = logging.getLogger(__name__)


def run(app, window, folder: str):
    os.makedirs(folder, exist_ok=True)
    pages = [p for p, _, _ in PAGES + SETUP]
    steps = [(name, w, h, n, pid) for name, w, h in SIZES for n, pid in enumerate(pages, 1)]
    waited = [0]

    def save(path):
        try:
            w, h = window.get_width(), window.get_height()
            snap = Gtk.Snapshot()
            Gtk.WidgetPaintable.new(window).snapshot(snap, w, h)
            node = snap.to_node()
            if node:
                window.get_native().get_renderer().render_texture(node, None).save_to_png(path)
        except Exception:
            log.exception("screenshot %s", path)
        GLib.idle_add(step)
        return False

    def step():
        if not steps:
            app.quit()
            return False
        name, w, h, n, pid = steps.pop(0)
        if (window.get_default_size().width, window.get_default_size().height) != (w, h):
            window.set_default_size(w, h)
        window.show_page(pid)
        GLib.timeout_add(1200, save, os.path.join(folder, f"{name}-{n:02d}-{pid}.png"))
        return False

    def wait():
        waited[0] += 1
        if app.state.connected or waited[0] > 60:
            GLib.timeout_add(3000, step)   # let the phone's notifications, music and clipboard arrive
            return False
        return True

    GLib.timeout_add(500, wait)
