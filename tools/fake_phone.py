#!/usr/bin/env python3
"""A fake Android phone for testing the computer apps end to end, without a phone (see PROTOCOL.md).

    python3 tools/fake_phone.py --identity /tmp/mnd/identity.json --port 47900 [--video clip.h264] [--seconds 20]

It pairs with the identity file's key, then behaves like the Mac n Droid Android app: it sends a battery level,
notifications, a live activity, a playing song, a clipboard copy, a file and a folder; and it answers what the
computer asks for (texts and conversations, contact search, sending a text, screen mirroring with a real H.264
stream from --video, files and folders sent to it). At the end it prints what it received and exits with 1 if
something expected didn't arrive (--expect t1,t2,…).

A test video for --video:
    ffmpeg -f lavfi -i testsrc=size=540x1200:rate=30 -t 2 -c:v libx264 -pix_fmt yuv420p -profile:v baseline \
           -x264-params keyint=30:aud=1 -f h264 clip.h264

Encryption uses linux/macndroid/crypto.py (OpenSSL through ctypes), so it runs anywhere OpenSSL is installed.
"""
import argparse
import base64
import io
import json
import os
import socket
import struct
import sys
import time
import zipfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "linux"))
from macndroid import crypto  # noqa: E402

import hashlib  # noqa: E402
import hmac  # noqa: E402


class Phone:
    def __init__(self, host, port, key):
        self.sock = socket.create_connection((host, port), timeout=10)
        self.key = key
        self.out_n = 0
        self.in_n = 0
        self.session = None

    def _write(self, payload: bytes):
        self.sock.sendall(struct.pack(">I", len(payload)) + payload)

    def _read(self) -> bytes:
        def exact(n):
            buf = b""
            while len(buf) < n:
                c = self.sock.recv(n - len(buf))
                if not c:
                    raise EOFError
                buf += c
            return buf
        (n,) = struct.unpack(">I", exact(4))
        return exact(n)

    def handshake(self):
        pn = os.urandom(16)
        self._write(json.dumps({"t": "hello", "v": 1, "phoneId": "fake", "nonce": base64.b64encode(pn).decode()}).encode())
        hello = json.loads(self._read())
        mn = base64.b64decode(hello["nonce"])
        self.session = hmac.new(self.key, b"mnd-v1" + pn + mn, hashlib.sha256).digest()
        self.send({"t": "device", "name": "Test Pixel", "model": "Google Pixel 9", "os": "Android 16"})
        welcome = self.recv()
        return hello, welcome

    def send(self, msg: dict):
        nonce = struct.pack(">IQ", 1, self.out_n)
        self.out_n += 1
        self._write(crypto.seal(self.session, nonce, json.dumps(msg, separators=(",", ":")).encode()))

    def recv(self) -> dict:
        data = self._read()
        nonce = struct.pack(">IQ", 2, self.in_n)
        self.in_n += 1
        return json.loads(crypto.open_(self.session, nonce, data))


def access_units(h264: bytes):
    """Splits an Annex B stream (made with x264 `aud=1`) into (config, [(key, au)])."""
    starts = []
    i = 0
    while True:
        j = h264.find(b"\x00\x00\x01", i)
        if j < 0:
            break
        s = j - 1 if j > 0 and h264[j - 1] == 0 else j
        starts.append((s, j + 3))
        i = j + 3
    nals = []
    for k, (s, body) in enumerate(starts):
        end = starts[k + 1][0] if k + 1 < len(starts) else len(h264)
        nals.append((h264[body] & 0x1F, h264[s:end]))
    cfg = b"".join(n for t, n in nals if t in (7, 8))
    aus, cur, key = [], b"", False
    for t, n in nals:
        if t == 9:  # access unit delimiter
            if cur:
                aus.append((key, cur))
            cur, key = b"", False
        elif t in (7, 8, 6):
            continue
        else:
            cur += n
            key = key or t == 5
    if cur:
        aus.append((key, cur))
    return cfg, aus


def make_folder_zip() -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("Holiday photos/", "")
        z.writestr("Holiday photos/beach.txt", "sand\n" * 1000)
        z.writestr("Holiday photos/day 2/hill.txt", "green\n" * 500)
        z.writestr("Holiday photos/../../escape.txt", "must not escape")
        z.writestr("__MACOSX/Holiday photos/._beach.txt", "junk")
    return buf.getvalue()


def send_file(p: Phone, fid, name, data, mime, **extra):
    p.send({"t": "file_start", "id": fid, "name": name, "size": len(data), "mime": mime, **extra})
    for off in range(0, len(data), 256 * 1024):
        p.send({"t": "file_chunk", "id": fid, "data": base64.b64encode(data[off:off + 256 * 1024]).decode()})
    p.send({"t": "file_end", "id": fid})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=47800)
    ap.add_argument("--identity", required=True)
    ap.add_argument("--video", help="Annex B H.264 for screen mirroring (x264 with aud=1)")
    ap.add_argument("--seconds", type=float, default=15)
    ap.add_argument("--expect", default="")
    a = ap.parse_args()

    ident = json.load(open(a.identity))
    key = base64.b64decode(ident["key"])
    p = Phone(a.host, a.port, key)
    hello, welcome = p.handshake()
    print("← hello", {k: hello.get(k) for k in ("name", "os")}, "← welcome", welcome.get("t"))
    assert welcome.get("t") == "welcome", welcome

    now = int(time.time() * 1000)
    p.send({"t": "battery", "level": 76, "charging": True})
    p.send({"t": "notif", "key": "0|com.whatsapp|1", "pkg": "com.whatsapp", "app": "WhatsApp", "title": "Priya",
            "text": "Are we still on for dinner tonight?", "time": now, "call": False, "cat": "messages",
            "actions": [{"i": 0, "title": "Reply", "reply": True}, {"i": 1, "title": "Mark as read", "reply": False}]})
    p.send({"t": "notif", "key": "0|com.google.android.dialer|9", "pkg": "com.google.android.dialer", "app": "Phone",
            "title": "Incoming call", "text": "Amma", "time": now, "call": True, "cat": "calls",
            "actions": [{"i": 0, "title": "Decline", "reply": False}, {"i": 1, "title": "Answer", "reply": False}]})
    p.send({"t": "live", "key": "timer1", "pkg": "com.google.android.deskclock", "app": "Clock", "kind": "timer",
            "title": "Timer", "text": "Pasta", "when": now + 300000})
    p.send({"t": "media", "id": "com.spotify.music", "pkg": "com.spotify.music", "app": "Spotify", "active": True,
            "title": "Test Song", "artist": "Test Artist", "album": "Test Album", "duration": 200000,
            "playing": True, "position": 1000, "positionAt": now, "speed": 1.0, "since": now,
            "canPrev": True, "canNext": True, "canSeek": True})
    p.send({"t": "mac_media_want", "on": True})
    p.send({"t": "clip", "text": "Hello from the fake phone", "explicit": True})
    send_file(p, "f1", "from-phone-test.txt", b"This file came from the phone.\n" * 20000, "text/plain")
    send_file(p, "f2", "Holiday photos.zip", make_folder_zip(), "application/zip", folder=True)
    p.send({"t": "ping"})
    print("→ sent battery, 2 notifications, live timer, media, clipboard, file, folder, ping")

    cfg, aus = access_units(open(a.video, "rb").read()) if a.video else (b"", [])
    received = {}
    incoming = {}
    p.sock.settimeout(0.2)
    until = time.time() + a.seconds
    mirroring, frame_i = False, 0
    while time.time() < until:
        if mirroring and aus:
            k, au = aus[frame_i % len(aus)]
            if frame_i == 0 or (frame_i % len(aus) == 0):
                p.send({"t": "mirror_cfg", "w": 540, "h": 1200, "d": base64.b64encode(cfg).decode()})
            p.send({"t": "mirror_frame", "k": k, "d": base64.b64encode(au).decode()})
            frame_i += 1
            if frame_i >= len(aus) * 2:
                mirroring = False
                p.send({"t": "mirror_stopped"})
        try:
            m = p.recv()
        except socket.timeout:
            continue
        except (EOFError, ConnectionResetError):
            # The test app may finish its own checks and quit before our time is up; what it sent still counts.
            print("× computer closed the connection")
            break
        t = m.get("t")
        received[t] = received.get(t, 0) + 1
        if t not in ("file_chunk", "pong", "mirror_key"):
            brief = {k: (v if not isinstance(v, str) or len(v) < 80 else v[:40] + "…") for k, v in m.items()
                     if k not in ("items", "art", "data")}
            print("←", brief)
        if t == "sms_threads":
            p.send({"t": "sms_threads", "threads": [
                {"id": "1", "address": "+91 98480 12345", "name": "Priya", "snippet": "See you at 8",
                 "date": now - 60000, "unread": 1, "out": False, "reply": True},
                {"id": "2", "address": "AD-HDFCBK-S", "name": "", "snippet": "Your OTP is 4829",
                 "date": now - 3600000, "unread": 0, "out": False, "reply": False}]})
        elif t == "sms_thread":
            p.send({"t": "sms_thread", "id": m["id"], "messages": [
                {"id": "a", "body": "Dinner tonight?", "date": now - 120000, "out": True},
                {"id": "b", "body": "See you at 8", "date": now - 60000, "out": False}]})
        elif t == "contacts_search":
            p.send({"t": "contacts", "q": m.get("q"), "permission": True,
                    "results": [{"name": "Priya", "number": "+91 98480 12345", "label": "Mobile"}]})
        elif t == "sms_send":
            p.send({"t": "sms_sent", "id": m.get("id"), "ok": True})
            p.send({"t": "sms_changed"})
        elif t == "mirror_start":
            mirroring, frame_i = bool(aus), 0
            if not aus:
                p.send({"t": "mirror_stopped", "reason": "denied"})
        elif t == "mirror_stop":
            mirroring = False
        elif t == "file_start":
            incoming[m["id"]] = (m, bytearray())
        elif t == "file_chunk":
            if m.get("id") in incoming:
                incoming[m["id"]][1].extend(base64.b64decode(m["data"]))
        elif t == "file_end":
            start, data = incoming.pop(m["id"], ({}, b""))
            ok = len(data) == start.get("size")
            kind = "folder" if start.get("folder") else "file"
            if start.get("folder"):
                try:
                    names = zipfile.ZipFile(io.BytesIO(bytes(data))).namelist()
                except zipfile.BadZipFile:
                    names, ok = [], False
                print(f"  ✓ {kind} {start.get('name')} ({len(data)} bytes, {len(names)} entries: {names[:4]})"
                      if ok else f"  × {kind} size mismatch")
                received["folder_ok"] = received.get("folder_ok", 0) + int(ok and bool(names))
            else:
                print(f"  ✓ {kind} {start.get('name')} ({len(data)} bytes)" if ok else f"  × {kind} size mismatch")
                received["file_ok"] = received.get("file_ok", 0) + int(ok)
        elif t == "ping":
            p.send({"t": "pong"})
    p.sock.close()
    print("received:", ", ".join(f"{k}×{v}" for k, v in sorted(received.items())))
    missing = [t for t in a.expect.split(",") if t and t not in received]
    if missing:
        print("✗ missing:", ", ".join(missing))
        sys.exit(1)
    print("✓ done")


if __name__ == "__main__":
    main()
