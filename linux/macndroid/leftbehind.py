""""Did you leave your phone behind?" — the same rule as the Mac app (mac/…/LeftBehind.swift).

The computer can't measure distance to the phone, so it uses the network: when the phone stops answering *and*
this computer is now on a different network (a different router, or none) than when they were last connected,
the computer has moved and the phone hasn't. Usually it was asleep in a bag, so the check runs after waking.
No network at all (Wi-Fi off, router restarting) isn't evidence of moving, so it stays quiet then.

It also tells the phone before the computer sleeps (`bye`), so closing the lid doesn't set off the phone's alert.
"""
import logging

from gi.repository import Gio, GLib

log = logging.getLogger("macndroid")


def network_signature() -> str:
    """The default route's interface, gateway and the gateway's hardware address (from /proc)."""
    try:
        with open("/proc/net/route") as f:
            rows = [line.split() for line in f.readlines()[1:]]
    except OSError:
        return "unknown"
    for r in rows:
        if len(r) > 2 and r[1] == "00000000" and int(r[3], 16) & 2:  # default route, RTF_GATEWAY
            iface = r[0]
            gw = ".".join(str(b) for b in bytes.fromhex(r[2])[::-1])
            hw = ""
            try:
                with open("/proc/net/arp") as f:
                    for line in f.readlines()[1:]:
                        p = line.split()
                        if p and p[0] == gw:
                            hw = p[3]
            except OSError:
                pass
            return f"{iface}|{gw}|{hw}"
    return "none"


class LeftBehind:
    def __init__(self, is_connected, phone_name, enabled, alert, before_sleep):
        self.is_connected = is_connected
        self.phone_name = phone_name
        self.enabled = enabled
        self.alert = alert              # (title, body)
        self.before_sleep = before_sleep
        self._network_when_linked = None
        self._connected_at_sleep = False
        self._timer = 0
        self._alerted = False

    def start(self):
        try:
            bus = Gio.bus_get_sync(Gio.BusType.SYSTEM, None)
            bus.signal_subscribe("org.freedesktop.login1", "org.freedesktop.login1.Manager", "PrepareForSleep",
                                 "/org/freedesktop/login1", None, Gio.DBusSignalFlags.NONE, self._sleep_signal)
        except GLib.Error as e:
            log.info("No logind, so sleep isn't noticed: %s", e.message)

    def _sleep_signal(self, _c, _s, _p, _i, _sig, params):
        (sleeping,) = params.unpack()
        if sleeping:
            self._connected_at_sleep = self.is_connected()
            self._cancel()
            self.before_sleep()
        elif self._connected_at_sleep:
            self._connected_at_sleep = False
            self._schedule(60)  # Give Wi-Fi and the phone time to reconnect.

    def linked(self):
        self._cancel()
        self._alerted = False
        self._network_when_linked = network_signature()

    def lost(self):
        self._schedule(45)

    def _cancel(self):
        if self._timer:
            GLib.source_remove(self._timer)
            self._timer = 0

    def _schedule(self, seconds):
        self._cancel()
        self._timer = GLib.timeout_add_seconds(seconds, self._run)

    def _run(self):
        self._timer = 0
        before = self._network_when_linked
        if not self.enabled() or self._alerted or self.is_connected() or before is None:
            return False
        now = network_signature()
        if now not in ("none", "unknown") and now != before:
            self._alerted = True
            name = self.phone_name()
            self.alert(f"Did you leave {name} behind?",
                       f"This computer is on a different network now and can't reach {name}.")
        return False
