"""Advertises `_macndroid._tcp` on the local network through Avahi (D-Bus), so the phone can find this
computer again when its address changes. Without Avahi the phone still connects with the addresses in
the QR code."""
import logging

from gi.repository import Gio, GLib

log = logging.getLogger("macndroid")


class Advertiser:
    def __init__(self):
        self._group = None

    def start(self, name: str, port: int, pc_id: str):
        try:
            bus = Gio.bus_get_sync(Gio.BusType.SYSTEM, None)
            server = Gio.DBusProxy.new_sync(bus, Gio.DBusProxyFlags.NONE, None, "org.freedesktop.Avahi", "/",
                                            "org.freedesktop.Avahi.Server", None)
            path = server.call_sync("EntryGroupNew", None, Gio.DBusCallFlags.NONE, 3000, None).unpack()[0]
            group = Gio.DBusProxy.new_sync(bus, Gio.DBusProxyFlags.NONE, None, "org.freedesktop.Avahi", path,
                                           "org.freedesktop.Avahi.EntryGroup", None)
            txt = [list(f"id={pc_id}".encode())]
            # interface -1 and protocol 0 (IPv4) / -1 (any): all interfaces.
            group.call_sync("AddService", GLib.Variant("(iiussssqaay)", (-1, -1, 0, name, "_macndroid._tcp", "", "",
                                                                          port, txt)),
                            Gio.DBusCallFlags.NONE, 3000, None)
            group.call_sync("Commit", None, Gio.DBusCallFlags.NONE, 3000, None)
            self._group = group
            log.info("Advertising _macndroid._tcp on port %d", port)
        except GLib.Error as e:
            log.info("Network discovery isn't available (Avahi): %s", e.message)

    def stop(self):
        if self._group:
            try:
                self._group.call_sync("Free", None, Gio.DBusCallFlags.NONE, 1000, None)
            except GLib.Error:
                pass
            self._group = None
