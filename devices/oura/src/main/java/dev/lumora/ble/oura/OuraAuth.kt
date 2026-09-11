package dev.lumora.ble.oura

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Oura's challenge-response: the ring sends a 15-byte nonce, the client returns
 * it encrypted under a 16-byte shared key.
 *
 * ECB with PKCS5 padding is the ring's choice, not ours — a 15-byte plaintext
 * pads to exactly one 16-byte block. Do not reuse this helper for anything else.
 */
object OuraAuth {

    private const val TRANSFORM = "AES/ECB/PKCS5Padding"

    fun encryptNonce(nonce: ByteArray, key: ByteArray): ByteArray {
        require(nonce.size == 15) { "nonce must be 15 bytes, was ${nonce.size}" }
        require(key.size == 16) { "auth key must be 16 bytes, was ${key.size}" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(nonce).also {
            check(it.size == 16) { "expected 16-byte ciphertext, got ${it.size}" }
        }
    }

    /**
     * Derives a 16-byte key from a UUID, little-endian, MSB first — matching the
     * key-generation path in Oura's own app.
     */
    fun keyFromUuid(uuid: UUID): ByteArray = ByteBuffer.allocate(16)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putLong(uuid.mostSignificantBits)
        .putLong(uuid.leastSignificantBits)
        .array()

    fun generateKey(): ByteArray = keyFromUuid(UUID.randomUUID())
}
