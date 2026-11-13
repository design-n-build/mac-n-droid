"""TCP server and encrypted phone sessions (see PROTOCOL.md). The computer is the server, the phone the client.

Networking runs on background threads. Callbacks are handed to `dispatch` (GLib.idle_add in the app), so
they always run on the UI thread.
"""
import base64
import json
import logging
import os
import socket
import struct
import threading
import uuid

from .crypto import CryptoError, Ephemeral, SessionCipher

log = logging.getLogger("macndroid")
MAX_FRAME = 8 * 1024 * 1024
DEFAULT_PORT = 47800


class Session:
    def __init__(self, sock: socket.socket, server: "Server"):
        self.id = uuid.uuid4().hex
        self.sock = sock
        self.server = server
        self.cipher: SessionCipher | None = None
        # Admitted: its messages are handled. "keyed": its device info decrypted, waiting to be admitted.
        self.authenticated = False
        self.keyed = False
        self.phone_id = ""
        self.used_device_key = False
        self.legacy = True
        self.closed = False
        self.device: dict = {}
        self._send_lock = threading.Lock()

    # -- reading --

    def run(self):
        self.sock.settimeout(10)  # The handshake has to finish within 10 seconds.
        reason = None
        try:
            while not self.closed:
                header = self._read_exact(4)
                (length,) = struct.unpack(">I", header)
                if length > MAX_FRAME:
                    raise ConnectionError("Frame too large")
                self._handle(self._read_exact(length))
        except (OSError, ConnectionError, CryptoError, ValueError) as e:
            reason = str(e) or e.__class__.__name__
        self.close(reason)

    def _read_exact(self, n: int) -> bytes:
        buf = bytearray()
        while len(buf) < n:
            chunk = self.sock.recv(min(n - len(buf), 1 << 20))
            if not chunk:
                raise ConnectionError("Disconnected")
            buf += chunk
        return bytes(buf)

    def _handle(self, payload: bytes):
        if self.cipher is None:
            msg = json.loads(payload)
            nonce = base64.b64decode(msg.get("nonce", ""))
            if msg.get("t") != "hello" or len(nonce) != 16:
                raise ConnectionError("Bad hello")
            ident = self.server.identity
            self.phone_id = str(msg.get("phoneId", ""))
            use = msg.get("use")
            self.legacy = use is None
            # Which key it holds: its own device key (must be the paired phone), or the QR's invite key.
            if use == "device":
                p = ident.phone
                if not p or p["id"] != self.phone_id or not p.get("key"):
                    raise ConnectionError("A phone that's no longer paired tried to connect.")
                base = p["key"]
                self.used_device_key = True
            else:
                base = ident.key
            pc_nonce = os.urandom(16)
            reply = {"t": "hello", "v": 1, "macId": ident.pc_id,
                     "nonce": base64.b64encode(pc_nonce).decode(), "name": self.server.name,
                     "os": self.server.os_name}
            if msg.get("eph"):
                # Forward secrecy: a one-time key exchange mixed into the session key.
                peer = base64.b64decode(msg["eph"])
                eph = Ephemeral()
                self.cipher = SessionCipher(base, nonce, pc_nonce, peer, eph.public, eph.shared(peer))
                reply["v"] = 2
                reply["eph"] = base64.b64encode(eph.public).decode()
            else:
                self.cipher = SessionCipher(base, nonce, pc_nonce)
            self._write(json.dumps(reply).encode())
            return
        try:
            plain = self.cipher.open(payload)
        except CryptoError:
            raise ConnectionError("Decryption failed" if self.authenticated
                                  else "A phone with an old pairing code tried to connect. Pair it again.")
        msg = json.loads(plain)
        if not self.authenticated:
            if self.keyed:
                return  # Waiting to be admitted: nothing else counts yet.
            if msg.get("t") != "device":
                raise ConnectionError("Expected device info")
            self.keyed = True
            self.device = msg
            self.server.dispatch(self.server.on_device, self)
            return
        self.server.dispatch(self.server.on_message, self, msg)

    # -- admitting --

    def admit(self, device_key: bytes | None = None):
        """Let it in: `welcome` (with its new device key, if any), then normal messages."""
        if self.closed or not self.keyed or self.authenticated:
            return
        self.authenticated = True
        self.sock.settimeout(90)  # Phones ping every 20 seconds.
        welcome = {"t": "welcome", "name": self.server.name}
        if device_key:
            welcome["deviceKey"] = base64.b64encode(device_key).decode()
        self.send(welcome)
        self.server.dispatch(self.server.on_authenticated, self)

    def await_approval(self, by: str, seconds: int):
        """A new phone waiting for approval: tell it, and give it time."""
        if self.closed or not self.keyed:
            return
        self.sock.settimeout(seconds + 10)
        self._send_sealed({"t": "approval", "by": by})

    def deny(self, reason: str):
        if self.keyed:
            self._send_sealed({"t": "denied"})
        threading.Timer(0.3, lambda: self.close(reason)).start()

    # -- writing --

    def send(self, msg: dict) -> bool:
        """Thread-safe. Blocks until the frame is handed to the network, so callers get simple flow control."""
        if self.closed or self.cipher is None or not self.authenticated:
            return False
        return self._send_sealed(msg)

    def _send_sealed(self, msg: dict) -> bool:
        if self.closed or self.cipher is None:
            return False
        data = json.dumps(msg, separators=(",", ":")).encode()
        try:
            with self._send_lock:
                self._write(self.cipher.seal(data))
            return True
        except (OSError, CryptoError):
            self.close("Send failed")
            return False

    def _write(self, payload: bytes):
        self.sock.sendall(struct.pack(">I", len(payload)) + payload)

    def close(self, reason: str | None = None):
        if self.closed:
            return
        self.closed = True
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.sock.close()
        log.info("Connection closed (authenticated=%s): %s", self.authenticated, reason)
        self.server.dispatch(self.server.on_closed, self, reason)


class Server:
    def __init__(self, identity, name: str, os_name: str, dispatch):
        self.identity = identity
        self.name = name
        self.os_name = os_name
        self.dispatch = dispatch
        self.port = 0
        self.on_device = lambda s: s.admit()
        self.on_authenticated = lambda s: None
        self.on_message = lambda s, m: None
        self.on_closed = lambda s, r: None
        self._sock: socket.socket | None = None

    def start(self, preferred: int = DEFAULT_PORT) -> int:
        preferred = int(os.environ.get("MACNDROID_PORT", preferred))
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind(("0.0.0.0", preferred))
        except OSError:
            sock.bind(("0.0.0.0", 0))  # Port busy: any free port (the QR code carries it).
        sock.listen(4)
        self._sock = sock
        self.port = sock.getsockname()[1]
        threading.Thread(target=self._accept_loop, name="accept", daemon=True).start()
        return self.port

    def _accept_loop(self):
        while True:
            try:
                conn, _ = self._sock.accept()
            except OSError:
                return
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            conn.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
            threading.Thread(target=Session(conn, self).run, name="session", daemon=True).start()
