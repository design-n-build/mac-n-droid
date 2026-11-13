# Layout of the DMG window, for dmgbuild (used by scripts/build-app.sh).
#   dmgbuild -s Resources/dmg/settings.py -D app="path/to/Mac n Droid.app" -D dir=Resources/dmg "Mac n Droid" out.dmg
import os
app = defines["app"]
here = defines["dir"]

format = "UDZO"
files = [app]
symlinks = {"Applications": "/Applications"}
background = os.path.join(here, "background.tiff")
window_rect = ((200, 140), (660, 440))
default_view = "icon-view"
show_status_bar = show_tab_view = show_toolbar = show_pathbar = show_sidebar = False
icon_size = 128
text_size = 13
icon_locations = {os.path.basename(app): (170, 172), "Applications": (490, 172)}
hide_extensions = [os.path.basename(app)]
