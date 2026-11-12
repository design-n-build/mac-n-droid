"""End-to-end test of the Linux app's core, with tools/fake_phone.py as the phone (no window needed).

    MACNDROID_CONFIG=/tmp/mnd MACNDROID_PORT=47900 MACNDROID_DOWNLOADS=/tmp/mnd/dl python3 linux/tests/e2e.py &
    python3 tools/fake_phone.py --identity /tmp/mnd/identity.json --port 47900 --video /tmp/clip.h264

When the phone connects, it does what a person would do in the window (open Messages, search contacts, send a
text, show the phone screen, send a file and a folder, control the phone's music), acts as the browser extension,
then checks what happened and exits with 1 on a failure.
"""
import base64
import json
import os
import socket
import struct
import sys
import tempfile
from pathlib import Path

# Never the real extension link: a Mac n Droid app already running on this computer owns 47809.
os.environ.setdefault("MACNDROID_BRIDGE_PORT", "47819")
PORT = int(os.environ["MACNDROID_BRIDGE_PORT"])

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from gi.repository import GLib  # noqa: E402

from macndroid import mirror  # noqa: E402
from macndroid.state import AppState, downloads_dir  # noqa: E402

checks = {}
frames = [0]


def check(name, ok):
    checks[name] = bool(ok)


class Sink(mirror.Decoder):
    def _show(self):
        with self._lock:
            pending, self._pending, self._idle = self._pending, None, 0
        if pending:
            frames[0] += 1
        return False


class WS:
    """A browser extension (or a web page, with a web origin) connecting to the extension link."""

    def __init__(self, origin):
        self.s = socket.create_connection(("127.0.0.1", PORT), timeout=3)
        key = base64.b64encode(os.urandom(16)).decode()
        self.s.sendall((f"GET / HTTP/1.1\r\nHost: 127.0.0.1:{PORT}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\nOrigin: {origin}\r\n\r\n").encode())
        self.buf = b""
        while b"\r\n\r\n" not in self.buf:
            chunk = self.s.recv(4096)
            if not chunk:
                break
            self.buf += chunk
        head, _, self.buf = self.buf.partition(b"\r\n\r\n")
        self.status_line = head.split(b"\r\n")[0]

    def _exact(self, n):
        while len(self.buf) < n:
            chunk = self.s.recv(65536)
            if not chunk:
                raise EOFError
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def send(self, msg):
        data = json.dumps(msg).encode()
        mask = os.urandom(4)
        n = len(data)
        head = struct.pack(">BB", 0x81, 0x80 | n) if n < 126 else struct.pack(">BBH", 0x81, 0x80 | 126, n)
        self.s.sendall(head + mask + bytes(b ^ mask[i & 3] for i, b in enumerate(data)))

    def recv(self):
        _b1, b2 = self._exact(2)
        n = b2 & 0x7F
        if n == 126:
            (n,) = struct.unpack(">H", self._exact(2))
        return json.loads(self._exact(n))


st = AppState({"left_behind": True})
st.mirror_sink = Sink(lambda tex: None, st.request_key_frame)
st.start()
check("extension link listening on its test port", st.bridge._sock is not None)
print(f"listening on {st.server.port}", flush=True)
ext = {}


def on_connected(*_):
    if not st.connected or "started" in ext:
        return
    ext["started"] = True
    st.refresh_sms()
    st.search_contacts("pri")
    st.start_mirror()
    folder = Path(tempfile.mkdtemp()) / "From computer"
    (folder / "sub").mkdir(parents=True)
    (folder / "a.txt").write_text("a" * 5000)
    (folder / "sub" / "b.txt").write_text("b" * 300)
    (folder / ".DS_Store").write_text("x")
    single = Path(tempfile.mkdtemp()) / "note.txt"
    single.write_text("hello phone\n")
    st.send_files([str(folder), str(single)])
    st.open_on_phone("https://example.com/from-computer")
    GLib.timeout_add(1500, later)


def later():
    st.open_sms_thread("1")
    st.send_sms("", "On my way", "1")
    st.media_command("toggle")
    st.action("0|com.whatsapp|1", 0, "Yes!")
    # The browser extension: a page can't connect, an extension can.
    check("web page refused by extension link", b"403" in WS("https://evil.example").status_line)
    s = WS("chrome-extension://abcdefghijklmnop")
    check("extension accepted", b"101" in s.status_line)
    status = s.recv()
    check("extension told the phone is connected", status.get("connected") is True and status.get("phone"))
    s.send({"t": "media", "tab": 7, "site": "YouTube", "title": "Browser Video", "artist": "", "album": "",
                "playing": True, "dur": 300, "pos": 12, "canNext": True})
    s.send({"t": "send_url", "url": "https://example.com/from-extension"})
    ext["sock"] = s
    GLib.timeout_add(800, phone_controls_browser)
    return False


def phone_controls_browser():
    # What the phone sees as "music on your computer" is now the browser tab; a command goes to the tab.
    st._computer_media_command("pause")
    s = ext["sock"]
    s.s.settimeout(2)
    try:
        cmd = s.recv()
    except (OSError, ValueError, EOFError):
        cmd = {}
    check("phone's pause reaches the browser tab", cmd == {"t": "cmd", "tab": 7, "cmd": "pause"})
    return False


st.connect("status-changed", on_connected)


def finish():
    dl = downloads_dir()
    check("battery", st.battery == 76)
    check("notifications", len(st.notifications) == 2)
    check("call notification", any(n.is_call for n in st.notifications))
    check("live activity", "timer1" in st.live)
    check("phone media", st.now_playing and st.now_playing.title == "Test Song")
    check("clipboard from phone", st.clips and st.clips[0].text == "Hello from the fake phone")
    check("file from phone", (dl / "from-phone-test.txt").stat().st_size == 620000)
    got = dl / "Holiday photos"
    check("folder from phone unpacked", (got / "beach.txt").exists() and (got / "day 2" / "hill.txt").exists())
    check("folder can't escape Downloads", not (dl / "escape.txt").exists() and not (dl.parent / "escape.txt").exists()
          and not list(dl.rglob("__MACOSX")))
    check("texts loaded", len(st.sms_threads) == 2 and len(st.sms_messages.get("1", [])) >= 2)
    check("contact search", st.contacts and st.contacts[0]["name"] == "Priya")
    check("transfers to phone done: " + ", ".join(f"{t.name}={t.state}" for t in st.transfers if t.to_phone),
          all(t.state == "done" for t in st.transfers if t.to_phone) and sum(t.to_phone for t in st.transfers) == 2)
    if mirror.decoder_available() and os.environ.get("MIRROR_VIDEO"):
        check(f"screen mirroring decoded frames ({frames[0]})", frames[0] > 10)
    for k, v in checks.items():
        print(("✓ " if v else "✗ ") + k, flush=True)
    loop.quit()
    sys.exit(0 if all(checks.values()) else 1)


# Checked while the phone is still connected (disconnecting clears battery, notifications and media).
GLib.timeout_add_seconds(int(os.environ.get("E2E_SECONDS", "8")), finish)
loop = GLib.MainLoop()
loop.run()
