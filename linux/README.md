# Mac n Droid for Linux (beta)

A GNOME app (GTK 4 + libadwaita), so it looks and behaves like GNOME's own apps. It works on other desktops
too (KDE, Cinnamon, Xfce…). Same pages as the Mac app: Overview, Notifications, Messages, Phone Screen,
Clipboard, Files and Pair a Phone, and it keeps running after you close the window.

**Everything the Mac app does:** pairing (QR code), battery, notifications with Reply (and Answer/Decline for
calls), **Messages** (read and send texts), **Phone Screen** (see and use your phone with mouse and keyboard),
music on the phone shown with controls, **music on this computer controlled from the phone** (any MPRIS
player: Spotify, Rhythmbox, VLC, Firefox, Chrome…), clipboard both ways with history, files and **folders**
both ways, links both ways, ring your phone, the **browser extension**, and the **left-behind alert**.
The webcam is Android's own USB webcam mode, which Linux supports without Mac n Droid.

## Install
**Flatpak** (any distribution): download `MacNDroid-Linux-x86_64.flatpak` (or `-aarch64` for ARM computers)
from GitHub Releases, then
```
flatpak install --user MacNDroid-Linux-x86_64.flatpak
flatpak run app.macndroid.Linux
```

**From source.** Needs Python 3.10+, GTK 4.14+ and libadwaita 1.5+ (Ubuntu 24.04, Fedora 40, Debian 13 or
newer). GStreamer is only for Phone Screen:
```
sudo apt install python3-gi gir1.2-gtk-4.0 gir1.2-adw-1 python3-qrcode \
  gir1.2-gstreamer-1.0 gstreamer1.0-plugins-bad gstreamer1.0-libav            # Ubuntu / Debian
sudo dnf install python3-gobject gtk4 libadwaita python3-qrcode \
  gstreamer1-plugins-bad-free gstreamer1-plugin-openh264                      # Fedora
./install.sh            # installs for your user in ~/.local, no sudo
macndroid
```

## Good to know
- **Firewall:** Fedora and some others block incoming connections. Allow TCP port 47800:
  `sudo firewall-cmd --add-port=47800/tcp --permanent && sudo firewall-cmd --reload`.
- **Clipboard on GNOME (Wayland):** GNOME lets apps read and set the clipboard only while their window is
  focused. On KDE, X11 and most other desktops it works in the background.
- **Finding the computer again** after its address changes uses Avahi, which most distributions run.
- **Phone Screen** needs an H.264 decoder for GStreamer (above). The Flatpak brings its own.

## Test without a phone
```
MACNDROID_CONFIG=/tmp/mnd MACNDROID_PORT=47900 MACNDROID_DOWNLOADS=/tmp/mnd/dl python3 linux/tests/e2e.py &
python3 tools/fake_phone.py --identity /tmp/mnd/identity.json --port 47900 --video clip.h264
```
`tools/fake_phone.py` explains how to make `clip.h264` with ffmpeg. `.github/workflows/desktop.yml` runs both
tests on every release.
