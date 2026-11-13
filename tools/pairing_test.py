"""Pairing test: single-use codes, device keys, approving new phones and forward secrecy, against a running
computer app with its own config folder. Needs `pip install cryptography`.

    MACNDROID_CONFIG=/tmp/mnd MACNDROID_PORT=47950 <start the computer app or its core>
    python3 tools/pairing_test.py /tmp/mnd 47950

Never point it at your real config: it pairs fake phones and replaces the pairing code.
"""
import json, socket, struct, base64, os, hmac, hashlib, sys, time
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization
CFG, PORT = sys.argv[1], int(sys.argv[2])
def ident(): return json.load(open(os.path.join(CFG, "identity.json")))
def fsend(s, b): s.sendall(struct.pack(">I", len(b)) + b)
def frecv(s):
    h = b""
    while len(h) < 4:
        c = s.recv(4 - len(h))
        if not c: raise EOFError
        h += c
    n = struct.unpack(">I", h)[0]; d = b""
    while len(d) < n:
        c = s.recv(n - len(d))
        if not c: raise EOFError
        d += c
    return d
class Phone:
    def __init__(self, pid, key, use, v2=True):
        self.s = socket.create_connection(("127.0.0.1", PORT), 3); self.s.settimeout(8)
        pn = os.urandom(16); hello = {"t":"hello","v":2 if v2 else 1,"phoneId":pid,"nonce":base64.b64encode(pn).decode()}
        if v2:
            self.priv = ec.generate_private_key(ec.SECP256R1())
            pub = self.priv.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
            hello.update(use=use, eph=base64.b64encode(pub).decode())
        fsend(self.s, json.dumps(hello).encode())
        h = json.loads(frecv(self.s)); mn = base64.b64decode(h["nonce"])
        if v2 and h.get("eph"):
            peer = base64.b64decode(h["eph"])
            shared = self.priv.exchange(ec.ECDH(), ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), peer))
            self.k = hmac.new(key, b"mnd-v2" + pn + mn + pub + peer + shared, hashlib.sha256).digest()
        else:
            self.k = hmac.new(key, b"mnd-v1" + pn + mn, hashlib.sha256).digest()
        self.sc = 0; self.rc = 0
        self.send({"t":"device","name":pid})
    def send(self, m):
        fsend(self.s, AESGCM(self.k).encrypt(struct.pack(">IQ", 1, self.sc), json.dumps(m).encode(), None)); self.sc += 1
    def recv(self):
        m = json.loads(AESGCM(self.k).decrypt(struct.pack(">IQ", 2, self.rc), frecv(self.s), None)); self.rc += 1; return m
    def recv_type(self, *types):
        while True:
            m = self.recv()
            if m["t"] in types: return m
ok = True
def check(name, cond):
    global ok; ok &= bool(cond); print(("PASS " if cond else "FAIL ") + name, flush=True)
inv0 = base64.b64decode(ident()["key"])
# 1. first pairing
A = Phone("phone-A", inv0, "invite")
w = A.recv_type("welcome"); check("first pairing gets its own key", "deviceKey" in w)
akey = base64.b64decode(w["deviceKey"]); A.send({"t":"key_saved"}); time.sleep(0.8)
inv1 = base64.b64decode(ident()["key"]); check("QR key replaced after pairing", inv1 != inv0)
check("phone recorded and confirmed", ident()["phone"]["confirmed"] and ident()["phone"]["id"] == "phone-A")
A.s.close(); time.sleep(0.3)
# 2. reconnect with device key
A = Phone("phone-A", akey, "device"); w = A.recv_type("welcome"); check("reconnects with its own key", "deviceKey" not in w)
# 3. stale screenshot
try:
    C = Phone("phone-C", inv0, "invite"); C.recv(); check("old QR refused", False)
except Exception: check("old QR refused", True)
# 4. new phone, denied by the paired phone
B = Phone("phone-B", inv1, "invite"); m = B.recv(); check("new phone told to wait for approval", m["t"] == "approval" and m["by"] == "phone-A")
r = A.recv_type("approve_request"); check("paired phone asked", r["name"] == "phone-B")
A.send({"t":"approve","id":r["id"],"allow":False})
check("new phone denied", B.recv()["t"] == "denied"); time.sleep(0.5)
inv2 = base64.b64decode(ident()["key"]); check("QR key replaced after denial", inv2 != inv1)
# 5. new phone, allowed by the paired phone
B = Phone("phone-B", inv2, "invite"); check("asks again", B.recv()["t"] == "approval")
r = A.recv_type("approve_request"); A.send({"t":"approve","id":r["id"],"allow":True})
check("old phone told it was replaced", A.recv_type("unpaired")["by"] == "phone-B")
w = B.recv_type("welcome"); check("new phone admitted with its own key", "deviceKey" in w)
bkey = base64.b64decode(w["deviceKey"]); B.send({"t":"key_saved"}); time.sleep(0.8)
check("new phone is the paired one", ident()["phone"]["id"] == "phone-B")
try:
    A2 = Phone("phone-A", akey, "device"); A2.recv(); check("replaced phone can't reconnect", False)
except Exception: check("replaced phone can't reconnect", True)
# 6. older app while another phone is paired
try:
    L = Phone("phone-L", base64.b64decode(ident()["key"]), None, v2=False); m = L.recv(); check("older app turned away", m["t"] == "denied")
except Exception: check("older app turned away", True)
print("ALL PASS" if ok else "SOME FAILED")
sys.exit(0 if ok else 1)
