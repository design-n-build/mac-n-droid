"""Draws the pairing QR code (black on white in light and dark mode, so every phone camera can read it)."""
import qrcode
from gi.repository import Graphene, Gdk, Gtk


class QRCodeView(Gtk.Widget):
    def __init__(self, size=240):
        super().__init__(width_request=size, height_request=size, margin_top=12, margin_bottom=12,
                         margin_start=12, margin_end=12)
        self._matrix = []
        self._text = None
        self.update_property([Gtk.AccessibleProperty.LABEL], ["Pairing QR code"])

    def set_text(self, text: str):
        if text == self._text:
            return
        self._text = text
        if text:
            q = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=2)
            q.add_data(text)
            q.make(fit=True)
            self._matrix = q.get_matrix()
        else:
            self._matrix = []
        self.queue_draw()

    def do_snapshot(self, snapshot):
        w, h = self.get_width(), self.get_height()
        side = min(w, h)
        white, black = Gdk.RGBA(), Gdk.RGBA()
        white.parse("#ffffff")
        black.parse("#000000")
        snapshot.append_color(white, Graphene.Rect().init(0, 0, side, side))
        n = len(self._matrix)
        if not n:
            return
        cell = side / n
        for y, row in enumerate(self._matrix):
            for x, on in enumerate(row):
                if on:
                    snapshot.append_color(black, Graphene.Rect().init(x * cell, y * cell, cell + 0.5, cell + 0.5))
