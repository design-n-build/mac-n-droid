"""Mac n Droid for Linux: a GNOME (GTK 4 + libadwaita) app.

Like the Mac app, it keeps running after its window is closed, so notifications keep arriving.
"""
import logging
import os
import sys

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gdk, Gio, GLib, Gtk  # noqa: E402

from . import APP_ID, __version__  # noqa: E402
from .settings import Settings  # noqa: E402
from .state import AppState  # noqa: E402
from .window import Window  # noqa: E402

log = logging.getLogger("macndroid")


class Application(Adw.Application):
    def __init__(self):
        super().__init__(application_id=APP_ID, flags=Gio.ApplicationFlags.DEFAULT_FLAGS)
        GLib.set_application_name("Mac n Droid")
        self.settings = Settings()
        self.state: AppState | None = None
        self.window: Window | None = None
        self._clipboard = None
        self._last_set = None  # What we put on the clipboard ourselves, so it isn't sent back.

    # -- lifecycle --

    def do_startup(self):
        Adw.Application.do_startup(self)
        self.state = AppState(self.settings)
        self.state.last_phone_name = self.settings["last_phone_name"]
        self.state.connect("status-changed", self._status_changed)
        self.state.connect("post-notification", self._post)
        self.state.connect("withdraw-notification", lambda _s, key: self.withdraw_notification(self._nid(key)))
        self.state.connect("set-clipboard", lambda _s, v: self.set_clipboard(v))
        self.state.connect("left-behind", self._left_behind)
        self.state.start()

        for name, cb, ptype in [
            ("quit", lambda *_: self._quit(), None),
            ("about", lambda *_: self._about(), None),
            ("preferences", lambda *_: self._preferences(), None),
            ("show", lambda *_: self.activate(), None),
            ("show-notifications", lambda *_: self._show("notifications"), None),
            ("show-files", lambda *_: self._show("files"), None),
            ("dismiss", lambda _a, p: self.state.dismiss(p.get_string()), "s"),
            ("reply", lambda _a, p: self._reply(*p.unpack()), "(si)"),
            ("notif-action", lambda _a, p: self.state.action(*p.unpack()), "(si)"),
            ("ring", lambda *_: self.state.ring(), None),
            ("send-files", lambda *_: (self.activate(), self.window.choose_files()), None),
        ]:
            action = Gio.SimpleAction.new(name, GLib.VariantType.new(ptype) if ptype else None)
            action.connect("activate", cb)
            self.add_action(action)
        self.set_accels_for_action("app.quit", ["<Control>q"])
        self.set_accels_for_action("app.preferences", ["<Control>comma"])
        self.set_accels_for_action("app.send-files", ["<Control>o"])
        self.set_accels_for_action("window.close", ["<Control>w"])

        display = Gdk.Display.get_default()
        if display:
            self._clipboard = display.get_clipboard()
            self._clipboard.connect("changed", self._clipboard_changed)

        # Keep running in the background after the window closes, like the Mac app.
        self.hold()

    start_hidden = False

    def do_activate(self):
        if not self.window:
            self.window = Window(self, self.state)
            self.window.connect("close-request", self._on_close)
        if self.start_hidden:
            self.start_hidden = False
            return
        self.window.present()

    def _quit(self):
        # Tell the phone first, so it doesn't think it was left behind.
        self.state.say_bye("quit")
        self.quit()

    def _left_behind(self, _st, info):
        title, body = info
        n = Gio.Notification.new(title)
        n.set_body(body)
        n.set_priority(Gio.NotificationPriority.URGENT)
        n.set_default_action("app.show")
        self.send_notification("left-behind", n)

    def _on_close(self, win):
        win.set_visible(False)
        return True  # Hide, don't destroy: the app keeps syncing in the background.

    def _show(self, page):
        self.activate()
        self.window.show_page(page)

    def _status_changed(self, st):
        if st.phone and st.phone.get("name") and st.phone["name"] != self.settings["last_phone_name"]:
            self.settings["last_phone_name"] = st.phone["name"]

    # -- notifications --

    @staticmethod
    def _nid(key: str) -> str:
        return "phone-" + GLib.compute_checksum_for_string(GLib.ChecksumType.SHA1, key, -1)

    def _post(self, _st, info):
        title, body, key = info
        if key and not self.settings["mirror_notifications"]:
            return
        n = Gio.Notification.new(title)
        n.set_body(body)
        if key:
            notif = self.state.notification(key)
            png = self.state.icons.get(notif.pkg) if notif else None
            if png:
                n.set_icon(Gio.BytesIcon.new(GLib.Bytes.new(png)))
            n.set_default_action("app.show-notifications")
            if notif:
                for a in notif.actions[:3]:
                    i = a.get("i", 0)
                    if a.get("reply") and not a.get("opens"):
                        n.add_button_with_target("Reply", "app.reply", GLib.Variant("(si)", (key, i)))
                    elif not a.get("reply"):
                        n.add_button_with_target(a.get("title", ""), "app.notif-action",
                                                 GLib.Variant("(si)", (key, i)))
            if notif and notif.is_call:
                n.set_priority(Gio.NotificationPriority.URGENT)
            self.send_notification(self._nid(key), n)
        else:
            n.set_default_action("app.show-files" if title.startswith("Received") else "app.show")
            self.send_notification(None, n)

    def _reply(self, key, i):
        self.activate()
        self.window.reply_dialog(key, i)

    # -- clipboard --

    def set_clipboard(self, value):
        if not self._clipboard:
            return
        self._last_set = value
        if isinstance(value, str):
            provider = Gdk.ContentProvider.new_for_bytes("text/plain;charset=utf-8", GLib.Bytes.new(value.encode()))
        else:
            provider = Gdk.ContentProvider.new_for_bytes("image/png", GLib.Bytes.new(value))
        self._clipboard.set_content(provider)

    def read_clipboard(self, callback):
        """Reads the clipboard: text, or an image as PNG bytes."""
        cb = self._clipboard
        if not cb:
            return
        formats = cb.get_formats()
        if formats.contain_mime_type("text/plain") or formats.contain_mime_type("text/plain;charset=utf-8"):
            def got_text(c, r):
                try:
                    text = c.read_text_finish(r)
                except GLib.Error:
                    return
                if text:
                    callback(text)
            cb.read_text_async(None, got_text)
        elif formats.contain_gtype(Gdk.Texture):
            def got_texture(c, r):
                try:
                    tex = c.read_texture_finish(r)
                except GLib.Error:
                    return
                if tex:
                    callback(tex.save_to_png_bytes().get_data())
            cb.read_texture_async(None, got_texture)

    def _clipboard_changed(self, cb):
        # Our own change (something from the phone) isn't sent back.
        if cb.is_local() or not self.settings["sync_clipboard"]:
            return

        def got(value):
            if value == self._last_set:
                return
            self.state.local_copy(value)

        self.read_clipboard(got)

    # -- preferences and about --

    def _preferences(self):
        self.activate()
        d = Adw.PreferencesDialog()
        page = Adw.PreferencesPage(title="General", icon_name="preferences-system-symbolic")
        g = Adw.PreferencesGroup(title="Notifications")
        g.add(self._switch("Show phone notifications on this computer", "mirror_notifications"))
        page.add(g)
        g = Adw.PreferencesGroup(title="Clipboard",
                                 description="Text and images you copy on this computer are sent to your phone, and "
                                             "the last 20 copies from both are kept in Clipboard history.")
        g.add(self._switch("Sync clipboard automatically", "sync_clipboard"))
        page.add(g)
        g = Adw.PreferencesGroup(title="Phone Left Behind",
                                 description="An alert if this computer moves to a different network while your phone "
                                             "stays behind (it can't measure distance, only Wi-Fi). Your phone alerts "
                                             "you the other way round, when you leave the Wi-Fi network without it.")
        g.add(self._switch("Alert me if I leave my phone behind", "left_behind"))
        page.add(g)
        g = Adw.PreferencesGroup(title="Startup",
                                 description="Mac n Droid keeps running after you close its window, so notifications "
                                             "and files keep arriving.")
        g.add(self._switch("Open at login", "open_at_login", self._autostart))
        page.add(g)
        d.add(page)
        d.present(self.window)

    def _switch(self, title, key, after=None):
        row = Adw.SwitchRow(title=title, active=self.settings[key])

        def changed(r, _p):
            self.settings[key] = r.get_active()
            if after:
                after(r.get_active())

        row.connect("notify::active", changed)
        return row

    def _autostart(self, on: bool):
        if os.path.exists("/.flatpak-info"):
            # Inside Flatpak, the Background portal asks the user and adds the login item.
            try:
                bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
                opts = {"reason": GLib.Variant("s", "Keep notifications and files arriving from your phone"),
                        "autostart": GLib.Variant("b", on),
                        "commandline": GLib.Variant("as", ["macndroid", "--background"])}
                bus.call_sync("org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop",
                              "org.freedesktop.portal.Background", "RequestBackground",
                              GLib.Variant("(sa{sv})", ("", opts)), None, Gio.DBusCallFlags.NONE, 5000, None)
            except GLib.Error as e:
                log.warning("Couldn't change Open at login: %s", e.message)
            return
        path = os.path.join(GLib.get_user_config_dir(), "autostart", f"{APP_ID}.desktop")
        if on:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w") as f:
                f.write(f"[Desktop Entry]\nType=Application\nName=Mac n Droid\n"
                        f"Exec=env PYTHONPATH={os.path.dirname(os.path.dirname(__file__))} {sys.executable} -m macndroid --background\n"
                        f"Icon={APP_ID}\nX-GNOME-Autostart-enabled=true\n")
        elif os.path.exists(path):
            os.remove(path)

    def _about(self):
        d = Adw.AboutDialog(application_name="Mac n Droid", application_icon=APP_ID, version=__version__,
                            developer_name="Design n Build Studio", license_type=Gtk.License.GPL_3_0,
                            comments="Your Android phone and your computer, together.",
                            website="https://github.com/santosh-mudragada-1/mac-n-droid",
                            issue_url="https://github.com/santosh-mudragada-1/mac-n-droid/issues")
        d.present(self.window)


def main():
    logging.basicConfig(level=logging.INFO if os.environ.get("MACNDROID_DEBUG") else logging.WARNING)
    app = Application()
    # Started at login: keep syncing without showing the window.
    app.start_hidden = "--background" in sys.argv
    return app.run([a for a in sys.argv if a != "--background"])
