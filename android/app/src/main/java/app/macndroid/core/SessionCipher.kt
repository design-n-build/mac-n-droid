package app.macndroid.core

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM with per-direction counter nonces; the session key is
 * HMAC-SHA256(pairingKey, "mnd-v1" ‖ phoneNonce ‖ macNonce). See PROTOCOL.md.
 */
class SessionCipher(pairingKey: ByteArray, phoneNonce: ByteArray, macNonce: ByteArray) {
    private val key: SecretKeySpec
    private var sendCounter = 0L
    private var recvCounter = 0L

    init {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pairingKey, "HmacSHA256"))
        mac.update("mnd-v1".toByteArray())
        mac.update(phoneNonce)
        mac.update(macNonce)
        key = SecretKeySpec(mac.doFinal(), "AES")
    }

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
    }
}
