"""Link to the Mac n Droid browser extension (PROTOCOL.md, "Browser extension link").

A small WebSocket server on 127.0.0.1:47809. It accepts only browser extensions (by their Origin), never web
pages, and never anything from the network. Callbacks run on the UI thread through `dispatch`.
"""
import base64
import hashlib
import json
import logging
import os
import socket
import struct
import threading

log = logging.getLogger("macndroid")
PORT = int(os.environ.get("MACNDROID_BRIDGE_PORT", 47809))  # Tests use another port.
ORIGINS = ("chrome-extension://", "moz-extension://", "extension://", "safari-web-extension://")
GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


class _Client:
    def __init__(self, sock):
        self.sock = sock
        self.lock = threading.Lock()
        self.closed = False

    def send(self, msg: dict):
        data = json.dumps(msg, separators=(",", ":")).encode()
        n = len(data)
        if n < 126:
            head = struct.pack(">BB", 0x81, n)
        elif n < 65536:
            head = struct.pack(">BBH", 0x81, 126, n)
        else:
            head = struct.pack(">BBQ", 0x81, 127, n)
        try:
            with self.lock:
                self.sock.sendall(head + data)
        except OSError:
            self.close()

    def close(self):
        if not self.closed:
            self.closed = True
            try:
                self.sock.close()
            except OSError:
                pass


class BrowserBridge:
    def __init__(self, dispatch):
        self.dispatch = dispatch
        self.on_message = lambda client_id, msg: None  # (client id, message) on the UI thread
        self.on_closed = lambda client_id: None
        self._clients: dict[int, _Client] = {}
        self._status = {"t": "status", "connected": False, "phone": ""}
        self._sock = None

    def start(self) -> bool:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind(("127.0.0.1", PORT))
        except OSError as e:
            log.warning("Browser extension link unavailable: %s", e)
            return False
        s.listen(8)
        self._sock = s
        threading.Thread(target=self._accept, name="bridge", daemon=True).start()
        return True

    def set_status(self, connected: bool, phone: str):
        self._status = {"t": "status", "connected": connected, "phone": phone}
        for c in list(self._clients.values()):
            c.send(self._status)

    def send(self, client_id: int, msg: dict):
        c = self._clients.get(client_id)
        if c:
            threading.Thread(target=c.send, args=(msg,), daemon=True).start()

    @property
    def has_clients(self) -> bool:
        return bool(self._clients)

    # -- networking (background threads) --

    def _accept(self):
        while True:
            try:
                conn, _ = self._sock.accept()
            except OSError:
                return
            threading.Thread(target=self._serve, args=(conn,), daemon=True).start()

    def _serve(self, sock):
        sock.settimeout(10)
        try:
            head = b""
            while b"\r\n\r\n" not in head:
                chunk = sock.recv(4096)
                if not chunk or len(head) > 16384:
                    raise ConnectionError
                head += chunk
            lines = head.split(b"\r\n\r\n")[0].decode("latin-1").split("\r\n")
            headers = {}
            for line in lines[1:]:
                k, _, v = line.partition(":")
                headers[k.strip().lower()] = v.strip()
            origin = headers.get("origin", "")
            key = headers.get("sec-websocket-key", "")
            if not lines[0].startswith("GET ") or not key or not origin.startswith(ORIGINS) \
                    or "websocket" not in headers.get("upgrade", "").lower():
                sock.sendall(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                raise ConnectionError
            accept = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
            sock.sendall(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                          f"Sec-WebSocket-Accept: {accept}\r\n\r\n").encode())
        except (OSError, ConnectionError, UnicodeError):
            sock.close()
            return
        sock.settimeout(120)  # The extension pings every 20 seconds while connected.
        client = _Client(sock)
        cid = id(client)
        self._clients[cid] = client
        client.send(self._status)
        try:
            self._read_loop(client, cid)
        except (OSError, ConnectionError, ValueError):
            pass
        client.close()
        self._clients.pop(cid, None)
        self.dispatch(self.on_closed, cid)

    def _read_loop(self, client, cid):
        def exact(n):
            buf = b""
            while len(buf) < n:
                chunk = client.sock.recv(n - len(buf))
                if not chunk:
                    raise ConnectionError
                buf += chunk
            return buf

        message = b""
        while not client.closed:
            b1, b2 = exact(2)
            fin, opcode = b1 & 0x80, b1 & 0x0F
            n = b2 & 0x7F
            if n == 126:
                (n,) = struct.unpack(">H", exact(2))
            elif n == 127:
                (n,) = struct.unpack(">Q", exact(8))
            if n > 8 * 1024 * 1024 or not b2 & 0x80:  # Browsers always mask; limit artwork size.
                raise ConnectionError
            mask = exact(4)
            raw = exact(n)
            data = (int.from_bytes(raw, "big") ^ int.from_bytes((mask * (n // 4 + 1))[:n], "big")).to_bytes(n, "big") \
                if n else b""
            if opcode == 0x8:
                return
            if opcode == 0x9:
                with client.lock:
                    client.sock.sendall(struct.pack(">BB", 0x8A, len(data)) + bytes(data[:125]))
                continue
            if opcode in (0x1, 0x2, 0x0):
                message += data
                if fin:
                    try:
                        msg = json.loads(message)
                    except ValueError:
                        msg = None
                    message = b""
                    if isinstance(msg, dict):
                        self.dispatch(self.on_message, cid, msg)
