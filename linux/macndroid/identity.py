"""This computer's identity and the pairing secret shared with phones (same format as the Mac app)."""
import base64
import json
import os
import socket
import subprocess
import uuid
from pathlib import Path
from urllib.parse import urlencode, quote

PLAY_LINK = "https://play.google.com/store/apps/details?id=app.macndroid#"


def config_dir() -> Path:
    base = os.environ.get("MACNDROID_CONFIG") or os.path.join(
        os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config"), "macndroid")
    p = Path(base)
    p.mkdir(parents=True, exist_ok=True)
    return p


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


class Identity:
    """`key` is the invite key in the QR code; it's replaced once a phone has paired with it, so the code works
    once. `phone` is the paired phone: {"id", "name", "key" (its own key, or None for an older app), "confirmed"}."""

    def __init__(self, pc_id: str, key: bytes, phone: dict | None = None):
        self.pc_id = pc_id
        self.key = key
        self.phone = phone

    @staticmethod
    def path() -> Path:
        return config_dir() / "identity.json"

    @classmethod
    def load(cls) -> "Identity":
        try:
            d = json.loads(cls.path().read_text())
            key = base64.b64decode(d["key"])
            if len(key) == 32:
                phone = d.get("phone")
                if isinstance(phone, dict) and phone.get("id"):
                    phone = {"id": phone["id"], "name": phone.get("name", ""), "confirmed": bool(phone.get("confirmed")),
                             "key": base64.b64decode(phone["key"]) if phone.get("key") else None}
                else:
                    phone = None
                return cls(d["macId"], key, phone)
        except (OSError, ValueError, KeyError):
            pass
        ident = cls(str(uuid.uuid4()), os.urandom(32))
        ident.save()
        return ident

    def reset(self):
        self.key = os.urandom(32)
        self.phone = None
        self.save()

    def replace_invite_key(self):
        self.key = os.urandom(32)
        self.save()

    def save(self):
        p = self.path()
        tmp = p.with_suffix(".tmp")
        # "macId" keeps the file and the protocol identical to the Mac app's.
        d = {"macId": self.pc_id, "key": base64.b64encode(self.key).decode()}
        if self.phone:
            ph = self.phone
            d["phone"] = {"id": ph["id"], "name": ph["name"], "confirmed": ph["confirmed"],
                          "key": base64.b64encode(ph["key"]).decode() if ph.get("key") else None}
        tmp.write_text(json.dumps(d))
        os.chmod(tmp, 0o600)
        tmp.replace(p)

    def pairing_uri(self, port: int, hosts: list[str], name: str) -> str:
        q = urlencode({"v": 1, "id": self.pc_id, "k": b64url(self.key), "p": port,
                       "h": ",".join(hosts), "n": name, "o": "linux"}, quote_via=quote)
        return "macndroid://pair?" + q

    def qr_text(self, port: int, hosts: list[str], name: str) -> str:
        """A Google Play link, so a phone without the app opens its store page. The pairing details follow
        the "#" (a fragment, never included in web requests)."""
        return PLAY_LINK + self.pairing_uri(port, hosts, name)


def computer_name() -> str:
    try:
        out = subprocess.run(["hostnamectl", "--pretty"], capture_output=True, text=True, timeout=2).stdout.strip()
        if out:
            return out
    except (OSError, subprocess.SubprocessError):
        pass
    return socket.gethostname().split(".")[0] or "Linux PC"


def local_ipv4() -> list[str]:
    """IPv4 addresses of this computer on the local network, the one used for the internet first."""
    found: list[str] = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("192.0.2.1", 9))  # No packet is sent; this only picks the outgoing interface.
        found.append(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        out = subprocess.run(["hostname", "-I"], capture_output=True, text=True, timeout=2).stdout.split()
        found += [a for a in out if a.count(".") == 3]
    except (OSError, subprocess.SubprocessError):
        pass
    seen, result = set(), []
    for a in found:
        if a not in seen and not a.startswith("127.") and not a.startswith("169.254."):
            seen.add(a)
            result.append(a)
    return result
