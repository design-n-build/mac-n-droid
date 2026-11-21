#!/usr/bin/env python3
"""Builds video/series/captures/index.html: one page to look through every capture.
   python3 tools/video/gallery.py   (both capture scripts run it at the end)"""
import html, json, pathlib

root = pathlib.Path(__file__).resolve().parents[2] / "video/series/captures"
mac, android = root / "mac", root / "android"
out = []

def fig(src, caption, cls=""):
    out.append(f'<figure class="{cls}"><a href="{html.escape(src)}"><img loading="lazy" src="{html.escape(src)}"></a>'
               f'<figcaption>{html.escape(caption)}</figcaption></figure>')

def section(title, note=""):
    out.append(f"</div><h2>{html.escape(title)}</h2><p>{html.escape(note)}</p><div class='grid'>")

if (mac / "manifest.json").exists():
    info = json.loads((mac / "manifest.json").read_text())
    shots = [s for s in info["shots"] if "reply-typing/" not in s["file"]]
    typing = [s for s in info["shots"] if "reply-typing/" in s["file"]]
    groups = [("Mac: the notch", "notch/", "Transparent, 3×. Each image is the whole panel: its top centre is the top centre of the screen."),
              ("Mac: dragging files to the notch", "drop/", "The target lights up in the Mac's accent colour (blue unless the Mac is set otherwise)."),
              ("Mac: music beside the notch", "live/", ""),
              ("Mac: menu bar", "menubar/", "The popover's glass panel belongs to macOS; here it sits on the plain window colour."),
              ("Mac: windows", "window/", "Photographed by macOS's own window capture at 2×, light and dark.")]
    for title, prefix, note in groups:
        section(title, note)
        for s in shots:
            if s["file"].startswith(prefix):
                fig("mac/" + s["file"], f'{s["file"]} · {s["note"]}', "wide" if prefix == "window/" else ("pop" if "popover" in s["file"] else "notch"))
        if prefix == "notch/" and typing:
            fig("mac/" + typing[len(typing) // 2]["file"], f'notch/04-priya-reply-typing/ · {len(typing)} frames, one per typed character', "notch")

if android.exists():
    section("Android: screens", "A Pixel 8 screen at 3× (1236 × 2745), light and dark, in the Monochrome wallpaper colours. No status bar: that belongs to Android, not the app.")
    for p in sorted(android.glob("*.png")):
        fig("android/" + p.name, p.name, "phone")
    for d in sorted(x for x in android.iterdir() if x.is_dir()):
        frames = sorted(d.glob("*.png"))
        section(f"Android: real animation frames ({d.name})", f"{len(frames)} frames, 16 ms apart: connecting, then the tick and confetti. Every 12th shown.")
        for p in frames[::12]:
            fig(f"android/{d.name}/{p.name}", p.name, "phone small")

page = """<!doctype html><meta charset="utf-8"><title>Mac n Droid captures</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body{margin:0;padding:24px;font:14px -apple-system,system-ui,sans-serif;background:#f4f4f5;color:#18181b}
@media(prefers-color-scheme:dark){body{background:#111113;color:#e4e4e7}figure{background:#1c1c1f!important}}
h1{font-size:22px;margin:0 0 4px}h2{font-size:16px;margin:36px 0 4px}p{margin:0 0 12px;opacity:.7;max-width:70ch}
.grid{display:flex;flex-wrap:wrap;gap:14px;align-items:flex-start}
figure{margin:0;padding:10px;border-radius:10px;background:#fff;max-width:100%}
img{display:block;max-width:100%;height:auto;border-radius:4px}
.notch img{width:420px;background:linear-gradient(#8e8e93,#636366)}
.pop img{width:300px}.wide img{width:560px}.phone img{width:230px}.small img{width:150px}
figcaption{font-size:12px;opacity:.75;margin-top:8px;max-width:560px}
.notch figcaption{max-width:420px}.pop figcaption{max-width:300px}.phone figcaption{max-width:230px}.small figcaption{max-width:150px}
</style>
<h1>Mac n Droid: captures for the video series</h1>
<p>Every image comes from the apps' own code with the demo data in STORY.md, section 2. Click one to open it at full size.</p><div>
""" + "\n".join(out) + "</div>"
root.mkdir(parents=True, exist_ok=True)
(root / "index.html").write_text(page)
print(f"✓ {root / 'index.html'}")
