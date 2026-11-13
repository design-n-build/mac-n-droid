import CryptoKit
import Foundation

enum CryptoFailure: Error { case badFrame }

/// AES-256-GCM session cipher with counter nonces (see PROTOCOL.md).
struct SessionCipher {
    static let phoneToMac: UInt32 = 1
    static let macToPhone: UInt32 = 2

    let key: SymmetricKey
    private var sendCounter: UInt64 = 0
    private var recvCounter: UInt64 = 0

    init(pairingKey: Data, phoneNonce: Data, macNonce: Data) {
        var msg = Data("mnd-v1".utf8)
        msg.append(phoneNonce)
        msg.append(macNonce)
        let mac = HMAC<SHA256>.authenticationCode(for: msg, using: SymmetricKey(data: pairingKey))
        key = SymmetricKey(data: Data(mac))
    }

    /// v2: also mixes in a one-time P-256 key exchange, so a leaked pairing key can't decrypt recorded traffic.
    init(pairingKey: Data, phoneNonce: Data, macNonce: Data, phoneEph: Data, macEph: Data, shared: Data) {
        var msg = Data("mnd-v2".utf8)
        for part in [phoneNonce, macNonce, phoneEph, macEph, shared] { msg.append(part) }
        let mac = HMAC<SHA256>.authenticationCode(for: msg, using: SymmetricKey(data: pairingKey))
        key = SymmetricKey(data: Data(mac))
    }

    private static func nonce(_ direction: UInt32, _ counter: UInt64) throws -> AES.GCM.Nonce {
        var d = Data(capacity: 12)
        withUnsafeBytes(of: direction.bigEndian) { d.append(contentsOf: $0) }
        withUnsafeBytes(of: counter.bigEndian) { d.append(contentsOf: $0) }
        return try AES.GCM.Nonce(data: d)
    }

    mutating func seal(_ plain: Data) throws -> Data {
        let n = try Self.nonce(Self.macToPhone, sendCounter)
        sendCounter += 1
        let box = try AES.GCM.seal(plain, using: key, nonce: n)
        return box.ciphertext + box.tag
    }

    mutating func open(_ data: Data) throws -> Data {
        guard data.count >= 16 else { throw CryptoFailure.badFrame }
        let n = try Self.nonce(Self.phoneToMac, recvCounter)
        recvCounter += 1
        let bytes = Data(data)
        let box = try AES.GCM.SealedBox(
            nonce: n,
            ciphertext: bytes.prefix(bytes.count - 16),
            tag: bytes.suffix(16)
        )
        return try AES.GCM.open(box, using: key)
    }
}

extension Data {
    init?(base64URL s: String) {
        var b = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b.count % 4 != 0 { b += "=" }
        self.init(base64Encoded: b)
    }

    var base64URL: String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func random(_ count: Int) -> Data {
        var d = Data(count: count)
        _ = d.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, count, $0.baseAddress!) }
        return d
    }
}
