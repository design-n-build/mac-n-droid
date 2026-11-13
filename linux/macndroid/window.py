"""The main window, built from stock GNOME (libadwaita) parts so it looks like any other GNOME app.

Same pages as the Mac app: Overview, Notifications, Messages, Phone Screen, Clipboard, Files, and Pair a Phone.
"""
import time

from gi.repository import Adw, Gdk, Gio, GLib, Gtk, Pango

from . import mirror as mirroring
from .qr import QRCodeView
from .state import can_text, number_key

PAGES = [
    ("overview", "Overview", "go-home-symbolic"),
    ("notifications", "Notifications", "preferences-system-notifications-symbolic"),
    ("messages", "Messages", "mail-unread-symbolic"),
    ("screen", "Phone Screen", "video-display-symbolic"),
    ("clipboard", "Clipboard", "edit-paste-symbolic"),
    ("files", "Files", "folder-download-symbolic"),
]

CSS = """
.bubble { border-radius: 18px; padding: 7px 12px; }
.bubble.out { background: @accent_bg_color; color: @accent_fg_color; }
.bubble.in { background: alpha(@window_fg_color, 0.08); }
.bubble.failed { background: alpha(@error_bg_color, 0.85); color: @error_fg_color; }
.art { border-radius: 8px; }
.mirror-frame { background: black; border-radius: 12px; }
"""


def short_time(ms: float) -> str:
    t = (ms or 0) / 1000
    if not t:
        return ""
    now = time.localtime()
    lt = time.localtime(t)
    if lt.tm_yday == now.tm_yday and lt.tm_year == now.tm_year:
        return time.strftime("%H:%M", lt)
    if time.time() - t < 6 * 86400:
        return time.strftime("%a", lt)
    return time.strftime("%d %b", lt)
SETUP = [("pair", "Pair a Phone", "phone-symbolic")]


def human_size(n: int) -> str:
    for unit in ("bytes", "KB", "MB", "GB"):
        if n < 1000 or unit == "GB":
            return f"{n:.0f} {unit}" if unit == "bytes" else f"{n:.1f} {unit}"
        n /= 1000
    return ""


def ago(t: float) -> str:
    s = int(time.time() - t)
    if s < 60:
        return "Now"
    if s < 3600:
        return f"{s // 60} min ago"
    return time.strftime("%H:%M", time.localtime(t))


def texture(png: bytes):
    try:
        return Gdk.Texture.new_from_bytes(GLib.Bytes.new(png))
    except GLib.Error:
        return None


def clear(box: Gtk.Widget):
    child = box.get_first_child()
    while child:
        nxt = child.get_next_sibling()
        box.remove(child)
        child = nxt


class Window(Adw.ApplicationWindow):
    def __init__(self, app, state):
        super().__init__(application=app, title="Mac n Droid", default_width=920, default_height=640)
        self.state = state
        self.set_size_request(360, 400)
        provider = Gtk.CssProvider()
        provider.load_from_string(CSS) if hasattr(provider, "load_from_string") else provider.load_from_data(CSS.encode())
        Gtk.StyleContext.add_provider_for_display(Gdk.Display.get_default(), provider,
                                                  Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)

        self.split = Adw.NavigationSplitView()
        self.set_content(self.split)

        # Sidebar
        sidebar_view = Adw.ToolbarView()
        header = Adw.HeaderBar()
        menu = Gio.Menu()
        menu.append("Preferences", "app.preferences")
        menu.append("Keyboard Shortcuts", "win.show-help-overlay")
        menu.append("About Mac n Droid", "app.about")
        menu.append("Quit", "app.quit")
        header.pack_end(Gtk.MenuButton(icon_name="open-menu-symbolic", menu_model=menu, tooltip_text="Main Menu",
                                       primary=True))
        sidebar_view.add_top_bar(header)
        self.nav = Gtk.ListBox(css_classes=["navigation-sidebar"])
        self.nav.connect("row-selected", self._on_nav)
        self._rows = {}
        for pid, title, icon in PAGES + SETUP:
            row = Gtk.ListBoxRow()
            box = Gtk.Box(spacing=12, margin_start=6, margin_end=6, margin_top=4, margin_bottom=4)
            box.append(Gtk.Image(icon_name=icon))
            label = Gtk.Label(label=title, xalign=0, hexpand=True)
            box.append(label)
            if pid == "notifications":
                self.badge = Gtk.Label(css_classes=["dim-label", "numeric"], visible=False)
                box.append(self.badge)
            row.set_child(box)
            row.page_id = pid
            self._rows[pid] = row
            self.nav.append(row)
        scroller = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, vexpand=True)
        scroller.set_child(self.nav)
        side = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        side.append(scroller)
        self.footer = Gtk.Label(xalign=0, wrap=True, css_classes=["dim-label", "caption"],
                                margin_start=18, margin_end=18, margin_top=6, margin_bottom=12)
        side.append(self.footer)
        sidebar_view.set_content(side)
        self.split.set_sidebar(Adw.NavigationPage(title="Mac n Droid", child=sidebar_view))

        # Content
        self.stack = Gtk.Stack(transition_type=Gtk.StackTransitionType.CROSSFADE)
        self.content_title = Adw.WindowTitle()
        content_view = Adw.ToolbarView()
        self.content_header = Adw.HeaderBar(title_widget=self.content_title)
        content_view.add_top_bar(self.content_header)
        self.toasts = Adw.ToastOverlay(child=self.stack)
        content_view.set_content(self.toasts)
        self.content_page = Adw.NavigationPage(title="Overview", child=content_view)
        self.split.set_content(self.content_page)

        self.page_actions = {}
        self._build_overview()
        self._build_notifications()
        self._build_messages()
        self._build_screen()
        self._build_clipboard()
        self._build_files()
        self._build_pair()

        # Narrow windows (phones, tiled windows) collapse the sidebar like other GNOME apps.
        bp = Adw.Breakpoint.new(Adw.BreakpointCondition.parse("max-width: 600sp"))
        bp.add_setter(self.split, "collapsed", True)
        self.add_breakpoint(bp)

        # Drop files anywhere on the window to send them.
        drop = Gtk.DropTarget.new(Gdk.FileList, Gdk.DragAction.COPY)
        drop.connect("drop", self._on_drop)
        self.add_controller(drop)

        for sig in ("status-changed", "notifications-changed", "clips-changed", "transfers-changed",
                    "pairing-changed"):
            state.connect(sig, lambda *_: self.refresh())
        state.connect("media-changed", lambda *_: self.refresh_media())
        state.connect("live-changed", lambda *_: self.refresh_media())
        state.connect("sms-changed", lambda *_: self.refresh_sms())
        state.connect("contacts-changed", lambda *_: self._refresh_contacts())
        state.connect("mirror-changed", lambda *_: self.refresh_screen())
        state.connect("approval-request", lambda _s, req: self._approval(req))
        self._approval_dialog = None
        self.nav.select_row(self._rows["overview" if state.identity else "pair"])
        self.refresh()
        GLib.timeout_add_seconds(30, lambda: self.refresh() or True)

    # -- navigation --

    def _on_nav(self, _box, row):
        if not row:
            return
        self.show_page(row.page_id)

    def show_page(self, pid: str):
        self.stack.set_visible_child_name(pid)
        title = dict((p, t) for p, t, _ in PAGES + SETUP)[pid]
        self.content_title.set_title(title)
        self.content_page.set_title(title)
        for w in self.page_actions.values():
            w.set_visible(False)
        if pid in self.page_actions:
            self.page_actions[pid].set_visible(True)
        if self.nav.get_selected_row() is not self._rows[pid]:
            self.nav.select_row(self._rows[pid])
        self.split.set_show_content(True)
        if pid == "messages":
            self.state.refresh_sms()

    def toast(self, text: str):
        self.toasts.add_toast(Adw.Toast(title=text, timeout=3))

    def _header_button(self, pid, icon, tooltip, callback, start=False):
        b = Gtk.Button(icon_name=icon, tooltip_text=tooltip, visible=False)
        b.connect("clicked", lambda *_: callback())
        (self.content_header.pack_start if start else self.content_header.pack_end)(b)
        self.page_actions[pid] = b
        return b

    # -- pages --

    def _scroll_page(self, pid):
        page = Adw.PreferencesPage()
        self.stack.add_named(page, pid)
        return page

    def _build_overview(self):
        self.overview = Gtk.Stack()
        self.stack.add_named(self.overview, "overview")

        self.waiting = Adw.StatusPage(icon_name="phone-symbolic")
        pair_btn = Gtk.Button(label="Pair a Phone", halign=Gtk.Align.CENTER, css_classes=["pill", "suggested-action"])
        pair_btn.connect("clicked", lambda *_: self.show_page("pair"))
        self.waiting.set_child(pair_btn)
        self.overview.add_named(self.waiting, "waiting")

        page = Adw.PreferencesPage()
        self.overview.add_named(page, "connected")

        g = Adw.PreferencesGroup()
        self.phone_row = Adw.ActionRow(css_classes=["property"])
        self.phone_row.add_prefix(Gtk.Image(icon_name="phone-symbolic", pixel_size=32))
        self.battery_label = Gtk.Label(css_classes=["title-3", "numeric"])
        self.phone_row.add_suffix(self.battery_label)
        g.add(self.phone_row)
        page.add(g)

        g = Adw.PreferencesGroup(title="Quick Actions")
        actions = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, column_spacing=12, row_spacing=12,
                              homogeneous=True, max_children_per_line=4, min_children_per_line=2)
        self.ring_button = self._quick(actions, "Ring Phone", "audio-volume-high-symbolic", self.state.ring)
        self._quick(actions, "Send Files", "document-send-symbolic", self.choose_files)
        self._quick(actions, "Send Clipboard", "edit-paste-symbolic", self.send_clipboard)
        self._quick(actions, "Open Link on Phone", "web-browser-symbolic", self.open_link_dialog)
        self._quick(actions, "Phone Screen", "video-display-symbolic", lambda: self.show_page("screen"))
        self._quick(actions, "Messages", "mail-unread-symbolic", lambda: self.show_page("messages"))
        g.add(actions)
        page.add(g)

        self.live_group = Adw.PreferencesGroup(title="On Your Phone", visible=False)
        self.live_box = Gtk.ListBox(css_classes=["boxed-list"], selection_mode=Gtk.SelectionMode.NONE)
        self.live_group.add(self.live_box)
        page.add(self.live_group)

        self.recent = Adw.PreferencesGroup(title="Recent Notifications")
        show_all = Gtk.Button(label="Show All", css_classes=["flat"], valign=Gtk.Align.CENTER)
        show_all.connect("clicked", lambda *_: self.show_page("notifications"))
        self.recent.set_header_suffix(show_all)
        self.recent_box = Gtk.ListBox(css_classes=["boxed-list"], selection_mode=Gtk.SelectionMode.NONE)
        self.recent.add(self.recent_box)
        page.add(self.recent)

    def _quick(self, flow, label, icon, callback):
        b = Gtk.Button(css_classes=["card"], height_request=88)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, valign=Gtk.Align.CENTER)
        img = Gtk.Image(icon_name=icon, pixel_size=24)
        lbl = Gtk.Label(label=label, wrap=True, justify=Gtk.Justification.CENTER)
        box.append(img)
        box.append(lbl)
        b.set_child(box)
        b.label = lbl
        b.image = img
        b.connect("clicked", lambda *_: callback())
        flow.append(b)
        return b

    def _build_notifications(self):
        self.notif_stack = Gtk.Stack()
        self.stack.add_named(self.notif_stack, "notifications")
        self.notif_stack.add_named(Adw.StatusPage(icon_name="preferences-system-notifications-symbolic",
                                                  title="No Notifications",
                                                  description="Notifications from your phone appear here."), "empty")
        page = Adw.PreferencesPage()
        g = Adw.PreferencesGroup()
        self.notif_list = Gtk.ListBox(css_classes=["boxed-list"], selection_mode=Gtk.SelectionMode.NONE)
        g.add(self.notif_list)
        page.add(g)
        self.notif_stack.add_named(page, "list")

    def _build_messages(self):
        self.sms_stack = Gtk.Stack()
        self.stack.add_named(self.sms_stack, "messages")
        self.sms_status = Adw.StatusPage(icon_name="mail-unread-symbolic")
        self.sms_stack.add_named(self.sms_status, "status")

        paned = Gtk.Paned(orientation=Gtk.Orientation.HORIZONTAL, shrink_start_child=False, resize_start_child=False,
                          position=300)
        scroller = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, width_request=240)
        self.thread_list = Gtk.ListBox(css_classes=["navigation-sidebar"])
        self.thread_list.connect("row-selected", self._thread_selected)
        scroller.set_child(self.thread_list)
        paned.set_start_child(scroller)

        self.convo_stack = Gtk.Stack()
        self.convo_stack.add_named(Adw.StatusPage(icon_name="mail-unread-symbolic", title="No Conversation Selected",
                                                  description="Choose a conversation, or start a new one."), "none")
        convo = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.convo_scroll = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, vexpand=True)
        self.bubbles = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_start=18, margin_end=18,
                               margin_top=12, margin_bottom=12)
        clamp = Adw.Clamp(child=self.bubbles, maximum_size=720)
        self.convo_scroll.set_child(clamp)
        convo.append(self.convo_scroll)
        bar = Gtk.Box(spacing=8, margin_start=12, margin_end=12, margin_top=8, margin_bottom=12)
        self.sms_entry = Gtk.Entry(placeholder_text="Text message", hexpand=True)
        self.sms_entry.connect("activate", lambda *_: self._send_sms())
        self.sms_send = Gtk.Button(icon_name="mail-send-symbolic", tooltip_text="Send",
                                   css_classes=["circular", "suggested-action"])
        self.sms_send.connect("clicked", lambda *_: self._send_sms())
        bar.append(self.sms_entry)
        bar.append(self.sms_send)
        self.sms_bar = bar
        self.sms_cant_reply = Gtk.Label(label="You can't reply to this sender.", css_classes=["dim-label"],
                                        margin_top=8, margin_bottom=12, visible=False)
        convo.append(bar)
        convo.append(self.sms_cant_reply)
        self.convo_stack.add_named(convo, "thread")
        paned.set_end_child(self.convo_stack)
        self.sms_stack.add_named(paned, "list")
        self._thread_rows = {}
        self._header_button("messages", "list-add-symbolic", "New Message", self.new_message_dialog, start=True)

    def _build_screen(self):
        self.screen_stack = Gtk.Stack()
        self.stack.add_named(self.screen_stack, "screen")
        self.screen_status = Adw.StatusPage(icon_name="video-display-symbolic", title="See Your Phone's Screen")
        self.screen_button = Gtk.Button(label="Show Phone Screen", halign=Gtk.Align.CENTER,
                                        css_classes=["pill", "suggested-action"])
        self.screen_button.connect("clicked", lambda *_: self.state.start_mirror())
        self.screen_status.set_child(self.screen_button)
        self.screen_stack.add_named(self.screen_status, "start")

        live = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, margin_top=12, margin_bottom=12,
                       margin_start=12, margin_end=12)
        self.mirror_view = mirroring.MirrorView(lambda m: self.state.send_input(m))
        self.mirror_view.add_css_class("mirror-frame")
        live.append(self.mirror_view)
        self.mirror_hint = Gtk.Label(wrap=True, justify=Gtk.Justification.CENTER, css_classes=["dim-label", "caption"])
        live.append(self.mirror_hint)
        keys = Gtk.Box(spacing=6, halign=Gtk.Align.CENTER)
        for icon, tip, key in [("go-previous-symbolic", "Back", "back"), ("go-home-symbolic", "Home", "home"),
                               ("view-grid-symbolic", "Recent Apps", "recents"),
                               ("audio-volume-low-symbolic", "Volume Down", "volume_down"),
                               ("audio-volume-high-symbolic", "Volume Up", "volume_up"),
                               ("system-lock-screen-symbolic", "Lock", "lock")]:
            b = Gtk.Button(icon_name=icon, tooltip_text=tip, css_classes=["circular", "flat"])
            b.connect("clicked", lambda *_, k=key: self.state.send_input({"key": k}))
            keys.append(b)
        stop = Gtk.Button(label="Stop", css_classes=["pill", "destructive-action"], margin_start=12)
        stop.connect("clicked", lambda *_: self.state.stop_mirror())
        keys.append(stop)
        live.append(keys)
        self.screen_stack.add_named(live, "live")

        self.decoder = mirroring.Decoder(lambda tex: self.mirror_view.set_paintable(tex), self.state.request_key_frame)
        self.state.mirror_sink = self.decoder

    def _build_clipboard(self):
        self.clip_stack = Gtk.Stack()
        self.stack.add_named(self.clip_stack, "clipboard")
        self.clip_stack.add_named(Adw.StatusPage(icon_name="edit-paste-symbolic", title="Nothing Copied Yet",
                                                 description="The last 20 things you copy on this computer or your "
                                                             "phone appear here, on both."), "empty")
        page = Adw.PreferencesPage()
        g = Adw.PreferencesGroup(description="Click an item to copy it again.")
        self.clip_list = Gtk.ListBox(css_classes=["boxed-list"], selection_mode=Gtk.SelectionMode.NONE)
        self.clip_list.connect("row-activated", lambda _b, row: self._use_clip(row.clip))
        g.add(self.clip_list)
        page.add(g)
        self.clip_stack.add_named(page, "list")
        self._header_button("clipboard", "edit-clear-all-symbolic", "Clear History", self.confirm_clear)

    def _build_files(self):
        self.files_stack = Gtk.Stack()
        self.stack.add_named(self.files_stack, "files")
        empty = Adw.StatusPage(icon_name="folder-download-symbolic", title="Send Files",
                               description="Drop files or folders on this window to send them to your phone. "
                                           "Files from your phone are saved in Downloads.")
        btns = Gtk.Box(spacing=12, halign=Gtk.Align.CENTER)
        btn = Gtk.Button(label="Send Files…", css_classes=["pill", "suggested-action"])
        btn.connect("clicked", lambda *_: self.choose_files())
        fbtn = Gtk.Button(label="Send Folder…", css_classes=["pill"])
        fbtn.connect("clicked", lambda *_: self.choose_folder())
        btns.append(btn)
        btns.append(fbtn)
        empty.set_child(btns)
        self.files_stack.add_named(empty, "empty")
        page = Adw.PreferencesPage()
        g = Adw.PreferencesGroup()
        self.files_list = Gtk.ListBox(css_classes=["boxed-list"], selection_mode=Gtk.SelectionMode.NONE)
        g.add(self.files_list)
        page.add(g)
        self.files_stack.add_named(page, "list")
        self._header_button("files", "list-add-symbolic", "Send Files…", self.choose_files, start=True)

    def _build_pair(self):
        page = self._scroll_page("pair")
        g = Adw.PreferencesGroup()
        box = Gtk.Box(spacing=24, margin_top=12, margin_bottom=12)
        self.qr = QRCodeView()
        frame = Gtk.Frame(child=self.qr, css_classes=["card"], valign=Gtk.Align.START)
        box.append(frame)
        steps = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12, hexpand=True, valign=Gtk.Align.CENTER)
        steps.append(Gtk.Label(label="Pair Your Android Phone", xalign=0, css_classes=["title-2"], wrap=True))
        for i, text in enumerate(["No app yet? Scan this code with your phone's camera to get Mac n Droid from "
                                  "Google Play.",
                                  "Open Mac n Droid and tap <b>Scan QR code</b>.",
                                  "Point your phone at this code."], 1):
            row = Gtk.Box(spacing=10)
            num = Gtk.Label(label=str(i), css_classes=["numeric", "accent"], valign=Gtk.Align.START)
            row.append(num)
            row.append(Gtk.Label(label=text, use_markup=True, wrap=True, xalign=0, hexpand=True))
            steps.append(row)
        steps.append(Gtk.Label(label="Both devices need to be on the same Wi-Fi network. Everything stays on your "
                                     "network and is end-to-end encrypted.",
                               wrap=True, xalign=0, css_classes=["dim-label"]))
        box.append(steps)
        g.add(box)
        page.add(g)

        g = Adw.PreferencesGroup(title="Connection")
        self.row_name = Adw.ActionRow(title="This Computer", css_classes=["property"])
        self.row_addr = Adw.ActionRow(title="Address", css_classes=["property"])
        self.row_port = Adw.ActionRow(title="Port", css_classes=["property"])
        self.row_status = Adw.ActionRow(title="Status", css_classes=["property"])
        for r in (self.row_name, self.row_addr, self.row_port, self.row_status):
            g.add(r)
        page.add(g)

        g = Adw.PreferencesGroup(description="Resetting creates a new code. Phones paired with the old code are "
                                             "disconnected and must scan again.")
        copy = Adw.ActionRow(title="Copy Pairing Link", activatable=True)
        copy.add_suffix(Gtk.Image(icon_name="edit-copy-symbolic"))
        copy.connect("activated", self._copy_link)
        reset = Adw.ActionRow(title="Reset Pairing…", activatable=True, css_classes=["error"])
        reset.connect("activated", lambda *_: self.confirm_reset())
        g.add(copy)
        g.add(reset)
        page.add(g)

    # -- refresh --

    def refresh(self):
        st = self.state
        phone = st.phone_name
        n = len(st.notifications)
        self.badge.set_label(str(n))
        self.badge.set_visible(n > 0)
        if st.connected:
            self.footer.set_label(f"● Connected to {phone}")
        elif st.last_error:
            self.footer.set_label(st.last_error)
        else:
            self.footer.set_label(f"Waiting for {phone}…" if st.last_phone_name else "No phone paired")

        # Overview
        if st.connected:
            self.overview.set_visible_child_name("connected")
            p = st.phone or {}
            self.phone_row.set_title(GLib.markup_escape_text(phone))
            self.phone_row.set_subtitle(GLib.markup_escape_text(" · ".join(x for x in (p.get("model"), p.get("os")) if x)))
            if st.battery is not None:
                self.battery_label.set_label(f"{'⚡ ' if st.charging else ''}{st.battery}%")
            self.battery_label.set_visible(st.battery is not None)
            self.ring_button.label.set_label("Stop Ringing" if st.ringing else "Ring Phone")
            self.ring_button.image.set_from_icon_name("audio-volume-muted-symbolic" if st.ringing
                                                      else "audio-volume-high-symbolic")
            clear(self.recent_box)
            for notif in st.notifications[:5]:
                self.recent_box.append(self._notif_row(notif, compact=True))
            if not st.notifications:
                self.recent_box.append(Adw.ActionRow(title="No notifications", css_classes=["dim-label"]))
        else:
            self.overview.set_visible_child_name("waiting")
            if st.last_phone_name:
                self.waiting.set_title(f"Waiting for {phone}")
                self.waiting.set_description("Make sure Mac n Droid is open on your phone and both devices are on "
                                             "the same Wi-Fi network.")
            else:
                self.waiting.set_title("Pair Your Phone")
                self.waiting.set_description("Scan a code with the Mac n Droid app on your Android phone to get "
                                             "started.")

        # Notifications
        clear(self.notif_list)
        for notif in st.notifications:
            self.notif_list.append(self._notif_row(notif))
        self.notif_stack.set_visible_child_name("list" if st.notifications else "empty")

        # Clipboard
        clear(self.clip_list)
        for c in st.clips:
            self.clip_list.append(self._clip_row(c))
        self.clip_stack.set_visible_child_name("list" if st.clips else "empty")
        self.page_actions["clipboard"].set_sensitive(bool(st.clips))

        # Files
        clear(self.files_list)
        for t in st.transfers[:50]:
            self.files_list.append(self._transfer_row(t))
        self.files_stack.set_visible_child_name("list" if st.transfers else "empty")

        self.refresh_media()
        self.refresh_sms()
        self.refresh_screen()

        # Pair
        self.qr.set_text(st.qr_text() if st.server.port else "")
        self.row_name.set_subtitle(GLib.markup_escape_text(st.name))
        self.row_addr.set_subtitle(", ".join(st.addresses) or "No network")
        self.row_port.set_subtitle(str(st.server.port or "—"))
        self.row_status.set_subtitle(f"Connected to {GLib.markup_escape_text(phone)}" if st.connected
                                     else "Waiting for phone")

    # -- now playing and live activities --

    def refresh_media(self):
        st = self.state
        clear(self.live_box)
        rows = 0
        for p in st.players[:3]:
            row = Adw.ActionRow(title=GLib.markup_escape_text(p.title),
                                subtitle=GLib.markup_escape_text(" · ".join(x for x in (p.artist, p.app) if x)),
                                title_lines=1, subtitle_lines=1)
            art = st.player_art.get(p.id)
            tex = texture(art) if art else None
            if tex:
                row.add_prefix(Gtk.Picture(paintable=tex, width_request=44, height_request=44, can_shrink=True,
                                           content_fit=Gtk.ContentFit.COVER, css_classes=["art"],
                                           valign=Gtk.Align.CENTER))
            else:
                row.add_prefix(self._app_icon(p.pkg))
            for icon, tip, cmd, on in [("media-skip-backward-symbolic", "Previous", "prev", p.can_prev),
                                       ("media-playback-pause-symbolic" if p.playing else
                                        "media-playback-start-symbolic", "Pause" if p.playing else "Play", "toggle",
                                        True),
                                       ("media-skip-forward-symbolic", "Next", "next", p.can_next)]:
                b = Gtk.Button(icon_name=icon, tooltip_text=tip, valign=Gtk.Align.CENTER, sensitive=on,
                               css_classes=["flat", "circular"])
                b.connect("clicked", lambda *_, c=cmd, i=p.id: st.media_command(c, i))
                row.add_suffix(b)
            self.live_box.append(row)
            rows += 1
        for a in list(st.live.values())[:4]:
            kind = a.get("kind", "")
            sub = a.get("text") or a.get("short") or ""
            row = Adw.ActionRow(title=GLib.markup_escape_text(a.get("title") or a.get("app", "")),
                                subtitle=GLib.markup_escape_text(sub), title_lines=1, subtitle_lines=1)
            row.add_prefix(self._app_icon(a.get("pkg", "")))
            if kind == "progress" and a.get("max"):
                row.add_suffix(Gtk.ProgressBar(fraction=min(1, (a.get("progress") or 0) / a["max"]),
                                               valign=Gtk.Align.CENTER, width_request=100))
            elif a.get("when") and kind in ("call", "timer", "stopwatch"):
                row.add_suffix(self._chronometer(a))
            self.live_box.append(row)
            rows += 1
        self.live_group.set_visible(rows > 0 and st.connected)

    def _chronometer(self, a):
        label = Gtk.Label(css_classes=["numeric", "dim-label"])
        when = a["when"] / 1000
        countdown = a.get("kind") == "timer"
        paused = a.get("paused")

        def tick():
            if paused is not None and paused >= 0:
                secs = paused / 1000
            else:
                secs = (when - time.time()) if countdown else (time.time() - when)
            secs = max(0, int(secs))
            h, rem = divmod(secs, 3600)
            label.set_label(f"{h}:{rem // 60:02d}:{rem % 60:02d}" if h else f"{rem // 60}:{rem % 60:02d}")
            return label.get_root() is not None

        tick()
        GLib.timeout_add_seconds(1, tick)
        return label

    # -- Messages --

    def refresh_sms(self):
        st = self.state
        phone = st.phone_name
        if not st.connected:
            self.sms_status.set_title("Messages")
            self.sms_status.set_description(f"Connect {GLib.markup_escape_text(phone)} to read and send texts.")
            self.sms_stack.set_visible_child_name("status")
            return
        if st.sms_state in ("off", "permission"):
            self.sms_status.set_title("Turn On Messages" if st.sms_state == "off" else "Allow SMS Access")
            self.sms_status.set_description(
                "On your phone, open Mac n Droid and turn on <b>Messages</b>, then allow access to text messages."
                if st.sms_state == "off" else
                "Mac n Droid on your phone needs access to text messages. Open it on your phone to allow it.")
            self.sms_stack.set_visible_child_name("status")
            return
        if st.sms_state in ("unknown", "loading") and not st.sms_threads:
            self.sms_status.set_title("Loading Messages…")
            self.sms_status.set_description("")
            self.sms_stack.set_visible_child_name("status")
            return
        self.sms_stack.set_visible_child_name("list")
        # Thread list, keeping the selection.
        selected = st.sms_open
        if st.sms_select_address:
            match = next((x for x in st.sms_threads
                          if number_key(x.get("address", "")) == number_key(st.sms_select_address)), None)
            if match:
                selected = match["id"]
                st.sms_select_address = ""
                st.open_sms_thread(selected)
        self._updating_threads = True
        clear(self.thread_list)
        self._thread_rows = {}
        for th in st.sms_threads:
            row = Gtk.ListBoxRow()
            box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, margin_start=6, margin_end=6,
                          margin_top=6, margin_bottom=6)
            top = Gtk.Box(spacing=6)
            name = Gtk.Label(label=th.get("name") or th.get("address", ""), xalign=0, hexpand=True,
                             ellipsize=Pango.EllipsizeMode.END,
                             css_classes=["heading"] if th.get("unread") else [])
            top.append(name)
            top.append(Gtk.Label(label=short_time(th.get("date", 0)), css_classes=["dim-label", "caption"]))
            box.append(top)
            snippet = ("You: " if th.get("out") else "") + (th.get("snippet") or "")
            box.append(Gtk.Label(label=snippet, xalign=0, ellipsize=Pango.EllipsizeMode.END, lines=2, wrap=True,
                                 css_classes=["dim-label", "caption"]))
            row.set_child(box)
            row.thread_id = th["id"]
            self._thread_rows[th["id"]] = row
            self.thread_list.append(row)
        if selected in self._thread_rows:
            self.thread_list.select_row(self._thread_rows[selected])
        self._updating_threads = False
        self._show_thread(selected if selected in self._thread_rows else None)

    _updating_threads = False

    def _thread_selected(self, _box, row):
        if self._updating_threads:
            return
        tid = row.thread_id if row else None
        if tid != self.state.sms_open:
            self.state.open_sms_thread(tid)
        self._show_thread(tid)

    def _show_thread(self, tid):
        st = self.state
        th = st.sms_thread(tid) if tid else None
        if not th:
            self.convo_stack.set_visible_child_name("none")
            return
        self.convo_stack.set_visible_child_name("thread")
        replyable = th.get("reply")
        can = replyable if replyable is not None else can_text(th.get("address", ""))
        self.sms_bar.set_visible(bool(can))
        self.sms_cant_reply.set_visible(not can)
        clear(self.bubbles)
        for m in st.sms_messages.get(tid, []):
            out = bool(m.get("out"))
            text = m.get("body", "")
            lbl = Gtk.Label(label=text, wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR, xalign=0, selectable=True,
                            max_width_chars=48, css_classes=["bubble", "out" if out else "in"])
            if m.get("failed"):
                lbl.add_css_class("failed")
            col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2,
                          halign=Gtk.Align.END if out else Gtk.Align.START)
            col.append(lbl)
            note = "Not sent" if m.get("failed") else ("Sending…" if m.get("pending") else short_time(m.get("date", 0)))
            col.append(Gtk.Label(label=note, css_classes=["dim-label", "caption"],
                                 halign=Gtk.Align.END if out else Gtk.Align.START))
            self.bubbles.append(col)
        GLib.idle_add(self._scroll_to_end)

    def _scroll_to_end(self):
        adj = self.convo_scroll.get_vadjustment()
        adj.set_value(adj.get_upper())
        return False

    def _send_sms(self):
        tid = self.state.sms_open
        th = self.state.sms_thread(tid)
        text = self.sms_entry.get_text().strip()
        if not th or not text:
            return
        self.state.send_sms(th.get("address", ""), text, tid)
        self.sms_entry.set_text("")

    def new_message_dialog(self):
        if not self.state.connected:
            self.toast(f"Connect {self.state.phone_name} first")
            return
        d = Adw.AlertDialog(heading="New Message")
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        to = Gtk.Entry(placeholder_text="Name or phone number", input_purpose=Gtk.InputPurpose.PHONE)
        results = Gtk.ListBox(css_classes=["boxed-list"], visible=False)
        body = Gtk.Entry(placeholder_text="Text message", activates_default=True)
        box.append(to)
        box.append(results)
        box.append(body)
        d.set_extra_child(box)
        d.add_response("cancel", "Cancel")
        d.add_response("send", "Send")
        d.set_response_appearance("send", Adw.ResponseAppearance.SUGGESTED)
        d.set_default_response("send")
        self._contact_box = results
        self._contact_entry = to
        to.connect("changed", lambda e: self.state.search_contacts(e.get_text()))
        results.connect("row-activated", lambda _b, r: (to.set_text(r.number), results.set_visible(False),
                                                        body.grab_focus()))

        def respond(_d, r):
            self._contact_box = None
            address = to.get_text().strip()
            if r == "send" and address and body.get_text().strip():
                self.state.send_sms(address, body.get_text(), None)

        d.connect("response", respond)
        d.present(self)
        to.grab_focus()

    _contact_box = None

    def _refresh_contacts(self):
        box = self._contact_box
        if not box:
            return
        clear(box)
        st = self.state
        if not st.contacts_allowed:
            box.append(Adw.ActionRow(title="Contacts access is off on your phone", css_classes=["dim-label"]))
        typed = self._contact_entry.get_text().strip()
        for c in st.contacts[:6]:
            if c.get("number") == typed:
                continue
            row = Adw.ActionRow(title=GLib.markup_escape_text(c.get("name") or c["number"]),
                                subtitle=GLib.markup_escape_text(" · ".join(x for x in (c["number"], c.get("label"))
                                                                            if x)),
                                activatable=True)
            row.number = c["number"]
            box.append(row)
        box.set_visible(box.get_first_child() is not None)

    # -- phone screen --

    def refresh_screen(self):
        st = self.state
        status = self.screen_status
        if not mirroring.decoder_available():
            status.set_title("Video Decoder Needed")
            status.set_description("To see your phone's screen, install GStreamer's H.264 decoder: "
                                   "<b>gstreamer1.0-libav</b> on Ubuntu and Debian, "
                                   "<b>gstreamer1-plugin-openh264</b> on Fedora. Then restart Mac n Droid.")
            self.screen_button.set_visible(False)
            self.screen_stack.set_visible_child_name("start")
            return
        self.screen_button.set_visible(True)
        if st.mirror == "live":
            self.screen_stack.set_visible_child_name("live")
            self.mirror_hint.set_label(
                "Phone control is off. On your phone, turn on Mac n Droid clipboard and controls in Accessibility settings."
                if st.control_unavailable else
                "Click to tap, drag to swipe. Right-click is Back. Type to enter text.")
            self.mirror_view.grab_focus()
            return
        self.screen_stack.set_visible_child_name("start")
        self.screen_button.set_sensitive(st.connected and st.mirror != "starting")
        self.screen_button.set_label("Waiting for your phone…" if st.mirror == "starting" else "Show Phone Screen")
        if not st.connected:
            status.set_title("See Your Phone's Screen")
            status.set_description(f"Connect {GLib.markup_escape_text(st.phone_name)} first.")
        elif st.mirror == "starting":
            status.set_title("Allow on Your Phone")
            status.set_description("Your phone asks before sharing its screen. Tap <b>Start</b> on the phone.")
        elif st.mirror == "denied":
            status.set_title("Screen Sharing Declined")
            status.set_description("Your phone didn't allow sharing its screen. Try again and tap Start.")
        else:
            status.set_title("See Your Phone's Screen")
            status.set_description("Use your phone from this computer: click to tap, drag to swipe, and type to "
                                   "enter text. Your phone asks before sharing its screen.")

    def _app_icon(self, pkg, size=32):
        png = self.state.icons.get(pkg)
        tex = texture(png) if png else None
        img = Gtk.Image(pixel_size=size)
        if tex:
            img.set_from_paintable(tex)
        else:
            img.set_from_icon_name("application-x-executable-symbolic")
        return img

    def _notif_row(self, notif, compact=False):
        row = Adw.ActionRow(title=GLib.markup_escape_text(notif.title or notif.app),
                            subtitle=GLib.markup_escape_text(notif.text),
                            subtitle_lines=1 if compact else 3, title_lines=1)
        row.add_prefix(self._app_icon(notif.pkg))
        if compact:
            row.add_suffix(Gtk.Label(label=ago(notif.time), css_classes=["dim-label", "caption"]))
            return row
        for a in notif.actions[:3]:
            label = a.get("title", "")
            if a.get("reply") and a.get("opens"):
                label = "Reply on Phone"
            b = Gtk.Button(label=label, valign=Gtk.Align.CENTER)
            if a.get("reply") and not a.get("opens"):
                b.connect("clicked", lambda *_, k=notif.key, i=a.get("i", 0): self.reply_dialog(k, i))
            else:
                b.connect("clicked", lambda *_, k=notif.key, i=a.get("i", 0): self.state.action(k, i))
            row.add_suffix(b)
        close = Gtk.Button(icon_name="window-close-symbolic", valign=Gtk.Align.CENTER, tooltip_text="Dismiss",
                           css_classes=["flat", "circular"])
        close.connect("clicked", lambda *_, k=notif.key: self.state.dismiss(k))
        row.add_suffix(close)
        return row

    def _clip_row(self, c):
        who = "From your phone" if c.from_phone else "From this computer"
        if c.kind == "text":
            row = Adw.ActionRow(title=GLib.markup_escape_text(c.text.strip()[:300]), title_lines=2,
                                subtitle=f"{who} · {ago(c.time)}", activatable=True)
            row.add_prefix(Gtk.Image(icon_name="text-x-generic-symbolic"))
        else:
            row = Adw.ActionRow(title="Image", subtitle=f"{who} · {ago(c.time)}", activatable=True)
            tex = texture(c.png)
            pic = Gtk.Picture(paintable=tex, can_shrink=True, width_request=48, height_request=48,
                              content_fit=Gtk.ContentFit.COVER)
            row.add_prefix(pic)
        row.add_suffix(Gtk.Image(icon_name="edit-copy-symbolic"))
        row.clip = c
        return row

    def _transfer_row(self, t):
        direction = "To your phone" if t.to_phone else "From your phone"
        if t.state == "active" and t.folder and not t.size:
            detail = f"{direction} · Preparing folder…"
        elif t.state == "active":
            detail = f"{direction} · {human_size(t.bytes)} of {human_size(t.size)}"
        elif t.state == "failed":
            detail = f"{direction} · Didn't finish"
        else:
            detail = f"{direction} · {human_size(t.size)}"
        row = Adw.ActionRow(title=GLib.markup_escape_text(t.name), subtitle=detail)
        row.add_prefix(Gtk.Image(icon_name="folder-symbolic" if t.folder else
                                 ("go-up-symbolic" if t.to_phone else "go-down-symbolic")))
        if t.state == "active" and t.size:
            bar = Gtk.ProgressBar(fraction=min(1, t.bytes / t.size), valign=Gtk.Align.CENTER, width_request=120)
            row.add_suffix(bar)
        elif t.state == "done" and not t.to_phone:
            b = Gtk.Button(icon_name="folder-open-symbolic", valign=Gtk.Align.CENTER,
                           tooltip_text="Open Folder" if t.folder else "Show in Folder", css_classes=["flat"])
            b.connect("clicked", lambda *_, p=t.path: self._show_file(p))
            row.add_suffix(b)
        return row

    # -- actions --

    def _show_file(self, path):
        f = Gio.File.new_for_path(path)
        try:
            Gtk.FileLauncher.new(f).open_containing_folder(self, None, None)
        except (AttributeError, GLib.Error):
            Gio.AppInfo.launch_default_for_uri(f.get_parent().get_uri(), None)

    def choose_files(self):
        if not self.state.connected:
            self.toast(f"Connect {self.state.phone_name} first")
            return
        dialog = Gtk.FileDialog(title="Send Files to Phone", accept_label="Send")

        def done(d, result):
            try:
                files = d.open_multiple_finish(result)
            except GLib.Error:
                return
            self.state.send_files([files.get_item(i).get_path() for i in range(files.get_n_items())])
            self.show_page("files")

        dialog.open_multiple(self, None, done)

    def choose_folder(self):
        if not self.state.connected:
            self.toast(f"Connect {self.state.phone_name} first")
            return
        dialog = Gtk.FileDialog(title="Send a Folder to Phone", accept_label="Send")

        def done(d, result):
            try:
                folder = d.select_folder_finish(result)
            except GLib.Error:
                return
            if folder and folder.get_path():
                self.state.send_files([folder.get_path()])
                self.show_page("files")

        dialog.select_folder(self, None, done)

    def _on_drop(self, _target, value, _x, _y):
        if not self.state.connected:
            self.toast(f"Connect {self.state.phone_name} first")
            return False
        self.state.send_files([f.get_path() for f in value.get_files() if f.get_path()])
        self.show_page("files")
        return True

    def send_clipboard(self):
        self.get_application().read_clipboard(lambda v: (self.state.send_clipboard_now(v),
                                                         self.toast(f"Sent clipboard to {self.state.phone_name}")))

    def _use_clip(self, c):
        self.state.use_clip(c)
        self.toast("Copied")

    def _copy_link(self, *_):
        self.get_clipboard().set(self.state.pairing_link())
        self.toast("Pairing link copied")

    def confirm_clear(self):
        d = Adw.AlertDialog(heading="Clear Clipboard History?",
                            body="This clears the list on this computer and on your phone.")
        d.add_response("cancel", "Cancel")
        d.add_response("clear", "Clear")
        d.set_response_appearance("clear", Adw.ResponseAppearance.DESTRUCTIVE)
        d.connect("response", lambda _d, r: r == "clear" and self.state.clear_history())
        d.present(self)

    def _approval(self, req):
        """Another phone scanned the code while one is paired: allow it? (The paired phone is asked too.)"""
        rid, name, paired = req
        if self._approval_dialog:
            d, self._approval_dialog = self._approval_dialog, None
            d.force_close()
        if not rid:
            return
        d = Adw.AlertDialog(
            heading=f"Allow \u201c{name}\u201d to connect?",
            body=f"It scanned this computer's pairing code. If you allow it, it replaces {paired}, which will be "
                 "disconnected. Only allow a phone that's yours.")
        d.add_response("deny", "Don't Allow")
        d.add_response("allow", "Allow")
        d.set_response_appearance("allow", Adw.ResponseAppearance.SUGGESTED)
        d.set_close_response("deny")

        def answered(_d, r):
            if self._approval_dialog is d:
                self._approval_dialog = None
                self.state.resolve_approval(rid, r == "allow")
        d.connect("response", answered)
        self._approval_dialog = d
        d.present(self)
        self.present()

    def confirm_reset(self):
        d = Adw.AlertDialog(heading="Reset Pairing?", body="All paired phones will need to scan the new QR code.")
        d.add_response("cancel", "Cancel")
        d.add_response("reset", "Reset Pairing")
        d.set_response_appearance("reset", Adw.ResponseAppearance.DESTRUCTIVE)
        d.connect("response", lambda _d, r: r == "reset" and self.state.reset_pairing())
        d.present(self)

    def reply_dialog(self, key, i):
        notif = self.state.notification(key)
        d = Adw.AlertDialog(heading=f"Reply to {notif.title}" if notif else "Reply",
                            body=notif.text if notif else "")
        entry = Gtk.Entry(placeholder_text="Message", activates_default=True)
        d.set_extra_child(entry)
        d.add_response("cancel", "Cancel")
        d.add_response("send", "Send")
        d.set_response_appearance("send", Adw.ResponseAppearance.SUGGESTED)
        d.set_default_response("send")
        d.connect("response", lambda _d, r: r == "send" and entry.get_text().strip()
                  and self.state.action(key, i, entry.get_text().strip()))
        d.present(self)
        entry.grab_focus()

    def open_link_dialog(self):
        d = Adw.AlertDialog(heading="Open Link on Phone", body=f"The link opens in {self.state.phone_name}'s browser.")
        entry = Gtk.Entry(placeholder_text="https://", activates_default=True, input_purpose=Gtk.InputPurpose.URL)
        d.set_extra_child(entry)
        d.add_response("cancel", "Cancel")
        d.add_response("open", "Open")
        d.set_response_appearance("open", Adw.ResponseAppearance.SUGGESTED)
        d.set_default_response("open")

        def respond(_d, r):
            url = entry.get_text().strip()
            if r != "open" or not url:
                return
            if "://" not in url:
                url = "https://" + url
            if url.startswith(("http://", "https://")):
                self.state.open_on_phone(url)
                self.toast(f"Opened on {self.state.phone_name}")

        d.connect("response", respond)
        d.present(self)
        entry.grab_focus()
