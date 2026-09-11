package dev.lumora.ble.oura

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun String.hexToBytes(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

class OuraProtocolTest {

    /** Captured from a real ring: request 2f012b, response 2f102c + 15-byte nonce. */
    @Test
    fun `parses nonce from captured response`() {
        val response = "2f102c490a55be3b8169e3f24aa279f1e55a".hexToBytes()

        val nonce = OuraProtocol.parseNonce(response)

        assertEquals("490a55be3b8169e3f24aa279f1e55a", nonce!!.toHex())
        assertEquals(15, nonce.size)
    }

    @Test
    fun `request nonce frame matches spec`() {
        assertEquals("2f012b", OuraProtocol.REQUEST_NONCE.toHex())
    }

    @Test
    fun `rejects nonce frame with wrong extended tag`() {
        val wrongTag = "2f102b490a55be3b8169e3f24aa279f1e55a".hexToBytes()
        assertNull(OuraProtocol.parseNonce(wrongTag))
    }

    @Test
    fun `rejects truncated nonce frame`() {
        assertNull(OuraProtocol.parseNonce("2f102c490a".hexToBytes()))
    }

    @Test
    fun `authenticate frame is 2f 11 2d plus 16 bytes`() {
        val encrypted = ByteArray(16) { it.toByte() }

        val frame = OuraProtocol.authenticate(encrypted)

        assertEquals(19, frame.size)
        assertEquals("2f112d", frame.copyOfRange(0, 3).toHex())
        assertArrayEquals(encrypted, frame.copyOfRange(3, 19))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `authenticate rejects wrong ciphertext length`() {
        OuraProtocol.authenticate(ByteArray(15))
    }

    @Test
    fun `set auth key frame is 24 10 plus key`() {
        val key = ByteArray(16) { 0xAB.toByte() }

        val frame = OuraProtocol.setAuthKey(key)

        assertEquals(18, frame.size)
        assertEquals("2410", frame.copyOfRange(0, 2).toHex())
    }

    @Test
    fun `auth result distinguishes success from rejection`() {
        assertEquals(true, OuraProtocol.parseAuthResult("2f022e00".hexToBytes()))
        assertEquals(false, OuraProtocol.parseAuthResult("2f022e01".hexToBytes()))
        assertNull(OuraProtocol.parseAuthResult("2f0220".hexToBytes()))
    }

    @Test
    fun `events request matches documented opcode`() {
        assertEquals("100900000008ffffffff", OuraProtocol.requestEvents().toHex())
    }

    @Test
    fun `ble mode toggles fast heart rate byte`() {
        assertEquals("160101", OuraProtocol.bleMode(fastHeartRate = true).toHex())
        assertEquals("160100", OuraProtocol.bleMode(fastHeartRate = false).toHex())
    }
}

class OuraAuthTest {

    @Test
    fun `encrypting 15-byte nonce yields exactly one 16-byte block`() {
        val nonce = "490a55be3b8169e3f24aa279f1e55a".hexToBytes()
        val key = "4431967d8bacc2659743142b68391d9a".hexToBytes()

        val ciphertext = OuraAuth.encryptNonce(nonce, key)

        assertEquals(16, ciphertext.size)
    }

    @Test
    fun `encryption is deterministic for the same key and nonce`() {
        val nonce = ByteArray(15) { it.toByte() }
        val key = ByteArray(16) { (it * 7).toByte() }

        assertArrayEquals(
            OuraAuth.encryptNonce(nonce, key),
            OuraAuth.encryptNonce(nonce, key),
        )
    }

    @Test
    fun `different keys produce different ciphertext`() {
        val nonce = ByteArray(15) { it.toByte() }
        val a = OuraAuth.encryptNonce(nonce, ByteArray(16) { 1 })
        val b = OuraAuth.encryptNonce(nonce, ByteArray(16) { 2 })

        assertTrue("ciphertexts must differ", !a.contentEquals(b))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects nonce of wrong length`() {
        OuraAuth.encryptNonce(ByteArray(16), ByteArray(16))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects key of wrong length`() {
        OuraAuth.encryptNonce(ByteArray(15), ByteArray(32))
    }

    @Test
    fun `generated keys are 16 bytes and distinct`() {
        val a = OuraAuth.generateKey()
        val b = OuraAuth.generateKey()

        assertEquals(16, a.size)
        assertEquals(16, b.size)
        assertTrue("keys must not repeat", !a.contentEquals(b))
    }

    @Test
    fun `uuid key derivation is little-endian msb first`() {
        val uuid = java.util.UUID(0x0102030405060708L, 0x090a0b0c0d0e0f10L)

        val key = OuraAuth.keyFromUuid(uuid)

        assertEquals("0807060504030201100f0e0d0c0b0a09", key.toHex())
    }
}
