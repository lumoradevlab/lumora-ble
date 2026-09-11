package dev.lumora.ble.libre

import dev.lumora.ble.core.DeviceKind
import dev.lumora.ble.core.GlucoseTrend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LibreSensorTypeTest {

    @Test
    fun `identifies sensor variants by patch info prefix`() {
        fun typeOf(prefix: Int) =
            LibreSensorType.fromPatchInfo(byteArrayOf(prefix.toByte(), 0, 0))

        assertEquals(LibreSensorType.LIBRE_1, typeOf(0xDF))
        assertEquals(LibreSensorType.LIBRE_1_A2, typeOf(0xA2))
        assertEquals(LibreSensorType.LIBRE_2, typeOf(0x9D))
        assertEquals(LibreSensorType.LIBRE_2_EU, typeOf(0xC5))
        assertEquals(LibreSensorType.LIBRE_US, typeOf(0xE5))
        assertEquals(LibreSensorType.LIBRE_PRO_H, typeOf(0x70))
    }

    @Test
    fun `unrecognized and empty patch info degrade to unknown`() {
        assertEquals(LibreSensorType.UNKNOWN,
            LibreSensorType.fromPatchInfo(byteArrayOf(0x11)))
        assertEquals(LibreSensorType.UNKNOWN,
            LibreSensorType.fromPatchInfo(ByteArray(0)))
    }

    @Test
    fun `only Pro uses the alternate FRAM layout`() {
        assertTrue(LibreSensorType.LIBRE_PRO_H.usesProLayout)
        assertFalse(LibreSensorType.LIBRE_1.usesProLayout)
        assertFalse(LibreSensorType.LIBRE_2.usesProLayout)
    }

    @Test
    fun `sensor state maps from FRAM codes`() {
        assertEquals(LibreSensorState.READY, LibreSensorState.fromCode(0x03))
        assertEquals(LibreSensorState.EXPIRED, LibreSensorState.fromCode(0x04))
        assertEquals(LibreSensorState.FAILURE, LibreSensorState.fromCode(0x06))
        assertEquals(LibreSensorState.UNKNOWN, LibreSensorState.fromCode(0x7F))
    }

    @Test
    fun `only a ready sensor produces glucose`() {
        assertTrue(LibreSensorState.READY.producesGlucose)
        assertFalse(LibreSensorState.STARTING.producesGlucose)
        assertFalse(LibreSensorState.EXPIRED.producesGlucose)
        assertFalse(LibreSensorState.FAILURE.producesGlucose)
    }
}

class LibreFramTest {

    private val now: Instant = Instant.parse("2026-09-11T12:00:00Z")

    /**
     * Builds a synthetic FRAM image.
     *
     * [trendRaws] are written newest-first, matching how the parser walks
     * backwards from the ring index.
     */
    private fun fram(
        state: Int = LibreSensorState.READY.code,
        ageMinutes: Int = 600,
        trendIndex: Int = 5,
        trendRaws: List<Int> = emptyList(),
    ): ByteArray {
        val f = ByteArray(LibreFram.FRAM_SIZE)
        f[4] = state.toByte()
        f[26] = trendIndex.toByte()
        f[27] = 0
        f[316] = (ageMinutes and 0xFF).toByte()
        f[317] = ((ageMinutes shr 8) and 0xFF).toByte()

        trendRaws.forEachIndexed { i, raw ->
            var slot = trendIndex - i - 1
            if (slot < 0) slot += 16
            val offset = slot * 6 + 28
            f[offset] = (raw and 0xFF).toByte()
            f[offset + 1] = ((raw shr 8) and 0x1F).toByte()
        }
        return f
    }

    @Test
    fun `decodes sensor state and age`() {
        val result = LibreFram.parse(fram(ageMinutes = 600), LibreSensorType.LIBRE_1, now)

        assertEquals(LibreSensorState.READY, result.state)
        assertEquals(600, result.sensorAgeMinutes)
    }

    @Test
    fun `converts raw counts to plausible mg per dL`() {
        // raw 100 -> 100 * 117.64705 / 100 = 117 mg/dL
        val result = LibreFram.parse(
            fram(trendRaws = listOf(100)), LibreSensorType.LIBRE_1, now)

        assertEquals(117, result.current!!.mgdl)
        assertEquals(DeviceKind.LIBRE_SENSOR, result.current!!.source)
    }

    @Test
    fun `reads the trend ring newest first`() {
        val result = LibreFram.parse(
            fram(trendRaws = listOf(120, 110, 100)), LibreSensorType.LIBRE_1, now)

        val values = result.trend.map { it.mgdl }
        assertEquals(141, values[0]) // 120 raw, newest
        assertEquals(129, values[1]) // 110 raw
        assertEquals(117, values[2]) // 100 raw
    }

    @Test
    fun `wraps correctly when the ring index is near zero`() {
        // trendIndex 1 forces the walk to wrap around the 16-slot buffer.
        val result = LibreFram.parse(
            fram(trendIndex = 1, trendRaws = listOf(120, 110)),
            LibreSensorType.LIBRE_1, now,
        )

        assertEquals(141, result.trend[0].mgdl)
        assertEquals(129, result.trend[1].mgdl)
    }

    @Test
    fun `skips empty and implausible slots`() {
        // raw 0 is an unwritten slot; raw 5 is below the plausible floor.
        val result = LibreFram.parse(
            fram(trendRaws = listOf(100, 0, 5, 110)), LibreSensorType.LIBRE_1, now)

        assertEquals(listOf(117, 129), result.trend.map { it.mgdl })
    }

    @Test
    fun `derives a rising trend from the recent slope`() {
        // Steadily rising over the last several minutes.
        val raws = listOf(200, 190, 180, 170, 160, 150)
        val result = LibreFram.parse(
            fram(trendIndex = 8, trendRaws = raws), LibreSensorType.LIBRE_1, now)

        val current = result.current!!
        assertNotNull(current.rateOfChange)
        assertTrue("expected a rising trend, got ${current.trend}",
            current.trend in setOf(
                GlucoseTrend.RISING, GlucoseTrend.RISING_RAPIDLY,
                GlucoseTrend.RISING_SLIGHTLY))
    }

    @Test
    fun `timestamps are anchored to sensor age`() {
        val result = LibreFram.parse(
            fram(ageMinutes = 600, trendRaws = listOf(100)),
            LibreSensorType.LIBRE_1, now,
        )

        // Newest trend reading sits at the sensor's current age, i.e. now.
        assertEquals(now, result.current!!.timestamp)
    }

    @Test
    fun `flags expired sensors`() {
        val expired = LibreFram.parse(
            fram(state = LibreSensorState.EXPIRED.code), LibreSensorType.LIBRE_1, now)
        assertTrue(expired.isExpired)

        // Past 14 days even a READY sensor is untrustworthy.
        val tooOld = LibreFram.parse(
            fram(ageMinutes = 15 * 24 * 60), LibreSensorType.LIBRE_1, now)
        assertTrue(tooOld.isExpired)

        val fresh = LibreFram.parse(fram(ageMinutes = 600), LibreSensorType.LIBRE_1, now)
        assertFalse(fresh.isExpired)
    }

    @Test
    fun `uses the alternate layout for Pro sensors`() {
        val f = ByteArray(LibreFram.FRAM_SIZE)
        f[4] = LibreSensorState.READY.code.toByte()
        f[76] = 3                       // Pro trend index
        f[74] = (500 and 0xFF).toByte() // Pro sensor age
        f[75] = ((500 shr 8) and 0xFF).toByte()

        val result = LibreFram.parse(f, LibreSensorType.LIBRE_PRO_H, now)

        assertEquals(500, result.sensorAgeMinutes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a truncated FRAM image`() {
        LibreFram.parse(ByteArray(100), LibreSensorType.LIBRE_1, now)
    }

    @Test
    fun `maps rates to display trends`() {
        assertEquals(GlucoseTrend.RISING_RAPIDLY, LibreFram.trendFrom(3.5))
        assertEquals(GlucoseTrend.FLAT, LibreFram.trendFrom(0.0))
        assertEquals(GlucoseTrend.FALLING_RAPIDLY, LibreFram.trendFrom(-4.0))
    }
}
