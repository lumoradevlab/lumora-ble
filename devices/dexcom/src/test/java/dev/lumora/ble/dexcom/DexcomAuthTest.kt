package dev.lumora.ble.dexcom

import dev.lumora.ble.core.DeviceException
import dev.lumora.ble.core.GlucoseTrend
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DexcomAuthTest {

    private val serial = "8UMS7E"

    @Test
    fun `key is the serial padded to exactly 16 bytes`() {
        val key = DexcomAuth.keyFor(serial)

        assertEquals(16, key.size)
        assertEquals("008UMS7E008UMS7E", String(key))
    }

    @Test
    fun `serial is normalized to uppercase`() {
        assertArrayEquals(DexcomAuth.keyFor("8ums7e"), DexcomAuth.keyFor("8UMS7E"))
    }

    @Test
    fun `rejects a serial of the wrong length`() {
        listOf("ABC", "TOOLONGSERIAL", "").forEach { bad ->
            val thrown = runCatching { DexcomAuth.validateSerial(bad) }.exceptionOrNull()
            assertTrue("'$bad' should be rejected", thrown is DeviceException)
        }
    }

    @Test
    fun `encrypted challenge is 8 bytes and deterministic`() {
        val challenge = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        val a = DexcomAuth.encryptChallenge(challenge, serial)
        val b = DexcomAuth.encryptChallenge(challenge, serial)

        assertEquals(8, a.size)
        assertArrayEquals(a, b)
    }

    /**
     * Pinned against an independent AES-ECB implementation. If this breaks, the
     * key derivation or block handling has drifted and no transmitter will
     * authenticate.
     */
    @Test
    fun `encryption matches the known-good vector`() {
        val challenge = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        val encrypted = DexcomAuth.encryptChallenge(challenge, serial)

        assertEquals(
            "2f181db9f6219181",
            encrypted.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `different serials produce different ciphertext`() {
        val challenge = ByteArray(8) { it.toByte() }

        val a = DexcomAuth.encryptChallenge(challenge, "8UMS7E")
        val b = DexcomAuth.encryptChallenge(challenge, "9ABC12")

        assertFalse("ciphertexts must differ", a.contentEquals(b))
    }

    @Test
    fun `verifies a transmitter that echoes our token correctly`() {
        val token = DexcomAuth.generateToken()
        val echoed = DexcomAuth.encryptChallenge(token, serial)

        assertTrue(DexcomAuth.verifyTransmitter(token, echoed, serial))
    }

    @Test
    fun `rejects a transmitter holding a different key`() {
        val token = DexcomAuth.generateToken()
        // An impostor encrypting with someone else's serial.
        val forged = DexcomAuth.encryptChallenge(token, "ZZZZ99")

        assertFalse(DexcomAuth.verifyTransmitter(token, forged, serial))
    }

    @Test
    fun `generated tokens are 8 bytes and not repeated`() {
        val a = DexcomAuth.generateToken()
        val b = DexcomAuth.generateToken()

        assertEquals(8, a.size)
        assertFalse("tokens must not repeat", a.contentEquals(b))
    }

    @Test
    fun `rejects a G7 by its advertised name rather than hanging`() {
        val thrown = runCatching { DexcomAuth.requireG6("DXCM01") }.exceptionOrNull()

        assertTrue(thrown is DeviceException)
        assertTrue(thrown!!.message!!.contains("G7"))
    }

    @Test
    fun `accepts a G6 advertised name`() {
        DexcomAuth.requireG6("Dexcom7E") // must not throw
    }

    @Test
    fun `advertised name uses the last two serial characters`() {
        assertEquals("Dexcom7E", DexcomProtocol.advertisedName("8UMS7E"))
    }
}

class DexcomMessagesTest {

    private val sessionStart: Instant = Instant.parse("2026-09-01T00:00:00Z")

    @Test
    fun `auth request frame is opcode token and slot`() {
        val token = ByteArray(8) { it.toByte() }

        val frame = DexcomProtocol.authRequest(token)

        assertEquals(10, frame.size)
        assertEquals(DexcomProtocol.Opcode.AUTH_REQUEST_TX, frame[0])
        assertArrayEquals(token, frame.copyOfRange(1, 9))
        assertEquals(DexcomProtocol.END_BYTE_STANDARD, frame[9])
    }

    @Test
    fun `parses an auth challenge`() {
        val bytes = byteArrayOf(DexcomProtocol.Opcode.AUTH_CHALLENGE_RX) +
            ByteArray(8) { 0xAA.toByte() } + ByteArray(8) { 0xBB.toByte() }

        val challenge = AuthChallenge.parse(bytes)!!

        assertEquals(8, challenge.encryptedToken.size)
        assertEquals(0xAA.toByte(), challenge.encryptedToken[0])
        assertEquals(0xBB.toByte(), challenge.challenge[0])
    }

    @Test
    fun `rejects a truncated auth challenge`() {
        assertNull(AuthChallenge.parse(byteArrayOf(0x03, 0x01)))
    }

    @Test
    fun `distinguishes bonding from ready in auth status`() {
        val needsBond = AuthStatus.parse(byteArrayOf(0x05, 1, 2))!!
        assertTrue(needsBond.needsBonding)
        assertFalse(needsBond.isReady)

        val ready = AuthStatus.parse(byteArrayOf(0x05, 1, 1))!!
        assertTrue(ready.isReady)
        assertFalse(ready.needsBonding)

        val rejected = AuthStatus.parse(byteArrayOf(0x05, 0, 0))!!
        assertFalse(rejected.isAuthenticated)
    }

    /** Builds a well-formed 0x31 glucose message. */
    private fun glucoseFrame(
        seconds: Long = 3600,
        mgdl: Int = 120,
        state: Int = CalibrationState.OK.code,
        trend: Int = 0,
        displayOnly: Boolean = false,
    ): ByteArray {
        val p = ByteArray(14)
        p[0] = DexcomProtocol.Opcode.GLUCOSE_RX
        p[1] = 0
        for (i in 0 until 4) p[6 + i] = ((seconds shr (8 * i)) and 0xFF).toByte()
        val raw = mgdl or (if (displayOnly) 0xF000 else 0)
        p[10] = (raw and 0xFF).toByte()
        p[11] = ((raw shr 8) and 0xFF).toByte()
        p[12] = state.toByte()
        p[13] = trend.toByte()
        return p
    }

    @Test
    fun `parses a glucose message`() {
        val message = GlucoseMessage.parse(glucoseFrame(mgdl = 120))!!

        assertEquals(120, message.glucoseMgdl)
        assertEquals(CalibrationState.OK, message.state)
        assertFalse(message.isDisplayOnly)

        val reading = message.toReading(sessionStart)!!
        assertEquals(Instant.parse("2026-09-01T01:00:00Z"), reading.timestamp)
        assertEquals(GlucoseTrend.FLAT, reading.trend)
    }

    @Test
    fun `masks the display-only flag out of the glucose value`() {
        val message = GlucoseMessage.parse(glucoseFrame(mgdl = 100, displayOnly = true))!!

        assertEquals(100, message.glucoseMgdl)
        assertTrue(message.isDisplayOnly)
    }

    @Test
    fun `suppresses readings while the sensor is warming up`() {
        val message = GlucoseMessage.parse(
            glucoseFrame(state = CalibrationState.WARMING_UP.code))!!

        assertFalse(message.state.producesGlucose)
        assertNull("warm-up must not yield a reading", message.toReading(sessionStart))
    }

    @Test
    fun `suppresses readings from a failed sensor`() {
        val message = GlucoseMessage.parse(
            glucoseFrame(state = CalibrationState.SENSOR_FAILED.code))!!

        assertNull(message.toReading(sessionStart))
    }

    @Test
    fun `decodes a negative trend as falling`() {
        val reading = GlucoseMessage.parse(glucoseFrame(trend = -2))!!
            .toReading(sessionStart)!!

        assertEquals(-2.0, reading.rateOfChange!!, 0.001)
        assertEquals(GlucoseTrend.FALLING, reading.trend)
    }

    @Test
    fun `rejects implausible glucose values`() {
        assertNull(GlucoseMessage.parse(glucoseFrame(mgdl = 5))!!.toReading(sessionStart))
        assertNull(GlucoseMessage.parse(glucoseFrame(mgdl = 900))!!.toReading(sessionStart))
    }

    @Test
    fun `rejects a truncated glucose frame`() {
        assertNull(GlucoseMessage.parse(ByteArray(6)))
    }
}
