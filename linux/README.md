# Mac n Droid for Linux (preview)

A GNOME app (GTK 4 + libadwaita), so it looks and behaves like GNOME's own apps. It works on other desktops
too (KDE, Cinnamon, Xfce…). Same pages and flows as the Mac app: Overview, Notifications, Clipboard, Files
and Pair a Phone, and it keeps running after you close the window.

**Works now:** pairing (QR code), phone battery, notifications with Reply and the app's own buttons,
clipboard both ways with history, files both ways, links both ways, ring your phone.
**Not yet:** Messages, music controls, screen mirroring, the browser extension link.

## Install
**Flatpak** (any distribution): download `MacNDroid-Linux-x86_64.flatpak`, then
```
flatpak install --user MacNDroid-Linux-x86_64.flatpak
```

**From source** (also for ARM computers). Needs Python 3.10+, GTK 4.14+ and libadwaita 1.5+
(Ubuntu 24.04, Fedora 40, Debian 13 or newer):
```
sudo apt install python3-gi gir1.2-gtk-4.0 gir1.2-adw-1 python3-qrcode   # Ubuntu / Debian
sudo dnf install python3-gobject gtk4 libadwaita python3-qrcode           # Fedora
./install.sh            # installs for your user in ~/.local, no sudo
macndroid
```

## Good to know
- **Firewall:** Fedora and some others block incoming connections. Allow TCP port 47800:
  `sudo firewall-cmd --add-port=47800/tcp --permanent && sudo firewall-cmd --reload`.
- **Clipboard on GNOME (Wayland):** GNOME lets apps read and set the clipboard only while their window is
  focused. On KDE, X11 and most other desktops it works in the background.
- **Finding the computer again** after its address changes uses Avahi, which most distributions run.

## Test without a phone
```
MACNDROID_CONFIG=/tmp/mnd MACNDROID_PORT=47900 python3 -m macndroid
java -Didentity=/tmp/mnd/identity.json ../tools/FakePhone.java 127.0.0.1 47900 --full
```
