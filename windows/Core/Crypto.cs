using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace MacNDroid.Core;

/// <summary>AES-256-GCM session cipher with counter nonces (see PROTOCOL.md).</summary>
public sealed class SessionCipher
{
    const uint PhoneToPc = 1, PcToPhone = 2;
    readonly AesGcm _aes;
    ulong _send, _recv;

    public SessionCipher(byte[] pairingKey, byte[] phoneNonce, byte[] pcNonce)
    {
        var msg = Encoding.ASCII.GetBytes("mnd-v1").Concat(phoneNonce).Concat(pcNonce).ToArray();
        _aes = new AesGcm(HMACSHA256.HashData(pairingKey, msg), 16);
    }

    /// <summary>v2: also mixes in a one-time P-256 key exchange, so a leaked key can't decrypt recorded traffic.</summary>
    public SessionCipher(byte[] pairingKey, byte[] phoneNonce, byte[] pcNonce, byte[] phoneEph, byte[] pcEph, byte[] shared)
    {
        var msg = Encoding.ASCII.GetBytes("mnd-v2").Concat(phoneNonce).Concat(pcNonce).Concat(phoneEph).Concat(pcEph).Concat(shared).ToArray();
        _aes = new AesGcm(HMACSHA256.HashData(pairingKey, msg), 16);
    }

    static byte[] Nonce(uint direction, ulong counter)
    {
        var n = new byte[12];
        BinaryPrimitives.WriteUInt32BigEndian(n, direction);
        BinaryPrimitives.WriteUInt64BigEndian(n.AsSpan(4), counter);
        return n;
    }

    public byte[] Seal(byte[] plain)
    {
        var output = new byte[plain.Length + 16];
        // AesGcm isn't safe to use from two threads at once, and sending and receiving run on different ones.
        lock (_aes) _aes.Encrypt(Nonce(PcToPhone, _send++), plain, output.AsSpan(0, plain.Length), output.AsSpan(plain.Length));
        return output;
    }

    public byte[] Open(byte[] data)
    {
        if (data.Length < 16) throw new CryptographicException("Frame too short");
        var plain = new byte[data.Length - 16];
        lock (_aes) _aes.Decrypt(Nonce(PhoneToPc, _recv++), data.AsSpan(0, plain.Length), data.AsSpan(plain.Length), plain);
        return plain;
    }
}

/// <summary>A one-time P-256 key pair for one connection. Public keys travel as X9.63 (65 bytes, uncompressed).</summary>
public sealed class Ephemeral : IDisposable
{
    readonly ECDiffieHellman _key = ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);

    public byte[] Public
    {
        get
        {
            var q = _key.ExportParameters(false).Q;
            return [4, .. Pad32(q.X!), .. Pad32(q.Y!)];
        }
    }

    /// <summary>The shared secret's x coordinate (32 bytes). Points that aren't on the curve are refused.</summary>
    public byte[] Shared(byte[] peer)
    {
        if (peer.Length != 65 || peer[0] != 4) throw new CryptographicException("Bad public key");
        using var other = ECDiffieHellman.Create(new ECParameters
        {
            Curve = ECCurve.NamedCurves.nistP256,
            Q = new ECPoint { X = peer[1..33], Y = peer[33..65] },
        });
        return _key.DeriveRawSecretAgreement(other.PublicKey);
    }

    static byte[] Pad32(byte[] b) => b.Length >= 32 ? b[^32..] : [.. new byte[32 - b.Length], .. b];

    public void Dispose() => _key.Dispose();
}
