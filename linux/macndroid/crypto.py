"""AES-256-GCM session cipher (see PROTOCOL.md), using the system's OpenSSL through ctypes.

Every Linux desktop ships OpenSSL's libcrypto, so the app needs no compiled Python packages.
"""
import ctypes
import ctypes.util
import hashlib
import hmac
import os
import struct

PHONE_TO_PC = 1
PC_TO_PHONE = 2


def _load_libcrypto():
    names = [os.environ.get("MACNDROID_LIBCRYPTO"), ctypes.util.find_library("crypto"),
             "libcrypto.so.3", "libcrypto.so.1.1", "libcrypto.so",
             "/opt/homebrew/opt/openssl@3/lib/libcrypto.dylib", "/usr/local/opt/openssl@3/lib/libcrypto.dylib"]
    for name in names:
        if not name:
            continue
        try:
            return ctypes.CDLL(name)
        except OSError:
            continue
    raise OSError("OpenSSL (libcrypto) wasn't found")


_lib = _load_libcrypto()
_lib.EVP_CIPHER_CTX_new.restype = ctypes.c_void_p
_lib.EVP_CIPHER_CTX_free.argtypes = [ctypes.c_void_p]
_lib.EVP_aes_256_gcm.restype = ctypes.c_void_p
for _f in ("EVP_EncryptInit_ex", "EVP_DecryptInit_ex"):
    getattr(_lib, _f).argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p]
for _f in ("EVP_EncryptUpdate", "EVP_DecryptUpdate"):
    getattr(_lib, _f).argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.POINTER(ctypes.c_int),
                                  ctypes.c_char_p, ctypes.c_int]
for _f in ("EVP_EncryptFinal_ex", "EVP_DecryptFinal_ex"):
    getattr(_lib, _f).argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.POINTER(ctypes.c_int)]
_lib.EVP_CIPHER_CTX_ctrl.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_void_p]

_GCM_SET_IVLEN, _GCM_GET_TAG, _GCM_SET_TAG = 0x9, 0x10, 0x11


class CryptoError(Exception):
    pass


def _check(ok):
    if ok != 1:
        raise CryptoError("OpenSSL call failed")


def seal(key: bytes, nonce: bytes, plain: bytes) -> bytes:
    ctx = _lib.EVP_CIPHER_CTX_new()
    try:
        _check(_lib.EVP_EncryptInit_ex(ctx, _lib.EVP_aes_256_gcm(), None, None, None))
        _check(_lib.EVP_CIPHER_CTX_ctrl(ctx, _GCM_SET_IVLEN, len(nonce), None))
        _check(_lib.EVP_EncryptInit_ex(ctx, None, None, key, nonce))
        out = ctypes.create_string_buffer(len(plain) + 16)
        n = ctypes.c_int(0)
        _check(_lib.EVP_EncryptUpdate(ctx, out, ctypes.byref(n), plain, len(plain)))
        total = n.value
        tail = ctypes.create_string_buffer(16)
        _check(_lib.EVP_EncryptFinal_ex(ctx, tail, ctypes.byref(n)))
        total += n.value
        tag = ctypes.create_string_buffer(16)
        _check(_lib.EVP_CIPHER_CTX_ctrl(ctx, _GCM_GET_TAG, 16, tag))
        return out.raw[:total] + tag.raw
    finally:
        _lib.EVP_CIPHER_CTX_free(ctx)


def open_(key: bytes, nonce: bytes, data: bytes) -> bytes:
    if len(data) < 16:
        raise CryptoError("Frame too short")
    body, tag = data[:-16], data[-16:]
    ctx = _lib.EVP_CIPHER_CTX_new()
    try:
        _check(_lib.EVP_DecryptInit_ex(ctx, _lib.EVP_aes_256_gcm(), None, None, None))
        _check(_lib.EVP_CIPHER_CTX_ctrl(ctx, _GCM_SET_IVLEN, len(nonce), None))
        _check(_lib.EVP_DecryptInit_ex(ctx, None, None, key, nonce))
        out = ctypes.create_string_buffer(len(body) + 16)
        n = ctypes.c_int(0)
        _check(_lib.EVP_DecryptUpdate(ctx, out, ctypes.byref(n), body, len(body)))
        total = n.value
        tagbuf = ctypes.create_string_buffer(tag, 16)
        _check(_lib.EVP_CIPHER_CTX_ctrl(ctx, _GCM_SET_TAG, 16, tagbuf))
        tail = ctypes.create_string_buffer(16)
        if _lib.EVP_DecryptFinal_ex(ctx, tail, ctypes.byref(n)) != 1:
            raise CryptoError("Authentication failed")
        return out.raw[:total + n.value]
    finally:
        _lib.EVP_CIPHER_CTX_free(ctx)


class SessionCipher:
    """Session key = HMAC-SHA256(pairingKey, "mnd-v1" ‖ phoneNonce ‖ pcNonce). Nonce = direction ‖ counter."""

    def __init__(self, pairing_key: bytes, phone_nonce: bytes, pc_nonce: bytes):
        self.key = hmac.new(pairing_key, b"mnd-v1" + phone_nonce + pc_nonce, hashlib.sha256).digest()
        self.send_counter = 0
        self.recv_counter = 0

    @staticmethod
    def _nonce(direction: int, counter: int) -> bytes:
        return struct.pack(">IQ", direction, counter)

    def seal(self, plain: bytes) -> bytes:
        n = self._nonce(PC_TO_PHONE, self.send_counter)
        self.send_counter += 1
        return seal(self.key, n, plain)

    def open(self, data: bytes) -> bytes:
        n = self._nonce(PHONE_TO_PC, self.recv_counter)
        self.recv_counter += 1
        return open_(self.key, n, data)
