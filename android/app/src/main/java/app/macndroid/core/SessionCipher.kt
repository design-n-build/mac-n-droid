package app.macndroid.core

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM with per-direction counter nonces; the session key is
 * HMAC-SHA256(pairingKey, "mnd-v1" ‖ phoneNonce ‖ macNonce). See PROTOCOL.md.
 */
class SessionCipher private constructor(private val key: SecretKeySpec) {
    private var sendCounter = 0L
    private var recvCounter = 0L

    /** v1: HMAC-SHA256(pairingKey, "mnd-v1" ‖ phoneNonce ‖ macNonce). */
    constructor(pairingKey: ByteArray, phoneNonce: ByteArray, macNonce: ByteArray) :
        this(derive(pairingKey, "mnd-v1", phoneNonce, macNonce))

    /** v2 also mixes in a one-time P-256 key exchange, so a leaked key can't decrypt recorded traffic. */
    constructor(pairingKey: ByteArray, phoneNonce: ByteArray, macNonce: ByteArray, phoneEph: ByteArray, macEph: ByteArray, shared: ByteArray) :
        this(derive(pairingKey, "mnd-v2", phoneNonce, macNonce, phoneEph, macEph, shared))

    private fun nonce(direction: Int, counter: Long): ByteArray =
        ByteBuffer.allocate(12).putInt(direction).putLong(counter).array()

    @Synchronized
    fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(PHONE_TO_MAC, sendCounter++)))
        return c.doFinal(plain)
    }

    @Synchronized
    fun open(sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(MAC_TO_PHONE, recvCounter++)))
        return c.doFinal(sealed)
    }

    companion object {
        const val PHONE_TO_MAC = 1
        const val MAC_TO_PHONE = 2

        private fun derive(pairingKey: ByteArray, label: String, vararg parts: ByteArray): SecretKeySpec {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(pairingKey, "HmacSHA256"))
            mac.update(label.toByteArray())
            parts.forEach { mac.update(it) }
            return SecretKeySpec(mac.doFinal(), "AES")
        }
    }
}

/** A one-time P-256 key pair for one connection (forward secrecy). Public keys travel as X9.63 (65 bytes). */
class Ephemeral {
    private val pair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val pub = pair.public as ECPublicKey

    val publicX963: ByteArray = byteArrayOf(4) + pad32(pub.w.affineX) + pad32(pub.w.affineY)

    /** The shared secret's x coordinate (32 bytes), as CryptoKit, .NET and Python compute it. */
    fun shared(peerX963: ByteArray): ByteArray {
        require(peerX963.size == 65 && peerX963[0] == 4.toByte())
        val point = ECPoint(BigInteger(1, peerX963.copyOfRange(1, 33)), BigInteger(1, peerX963.copyOfRange(33, 65)))
        val peer = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, pub.params))
        return KeyAgreement.getInstance("ECDH").run { init(pair.private); doPhase(peer, true); generateSecret() }
    }

    private fun pad32(n: BigInteger): ByteArray {
        val b = n.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return ByteArray(32 - b.size) + b
    }
}
