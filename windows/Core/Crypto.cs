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
        _aes.Encrypt(Nonce(PcToPhone, _send++), plain, output.AsSpan(0, plain.Length), output.AsSpan(plain.Length));
        return output;
    }

    public byte[] Open(byte[] data)
    {
        if (data.Length < 16) throw new CryptographicException("Frame too short");
        var plain = new byte[data.Length - 16];
        _aes.Decrypt(Nonce(PhoneToPc, _recv++), data.AsSpan(0, plain.Length), data.AsSpan(plain.Length), plain);
        return plain;
    }
}
