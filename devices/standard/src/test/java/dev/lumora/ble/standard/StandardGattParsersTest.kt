package dev.lumora.ble.standard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Heart Rate Measurement layout is variable and driven by its flags byte,
 * which is where implementations usually go wrong: assuming uint8 BPM reads a
 * plausible-but-incorrect value from a uint16 device and desynchronises every
 * field after it. These cases pin each flag combination.
 */
class StandardGattParsersTest {

    @Test
    fun `uint8 heart rate`() {
        val sample = StandardGattParsers.parseHeartRate(byteArrayOf(0x00, 72))
        assertEquals(72, sample!!.bpm)
        assertTrue(sample.ibiMs.isEmpty())
    }

    @Test
    fun `uint16 heart rate reads both bytes little-endian`() {
        // flags bit0 = 1 -> uint16. 0x0130 = 304 would be rejected, so use 200.
        val sample = StandardGattParsers.parseHeartRate(
            byteArrayOf(0x01, 0xC8.toByte(), 0x00))
        assertEquals(200, sample!!.bpm)
    }

    @Test
    fun `uint8 device is not misread as uint16`() {
        // If bit0 were ignored and two bytes consumed, this would read 0x4838.
        val sample = StandardGattParsers.parseHeartRate(byteArrayOf(0x00, 0x38, 0x48))
        assertEquals(0x38, sample!!.bpm)
    }

    @Test
    fun `RR intervals are converted from 1024ths to milliseconds`() {
        // flags: uint8 BPM + RR present (0x10). RR = 1024 -> exactly 1000 ms.
        val sample = StandardGattParsers.parseHeartRate(
            byteArrayOf(0x10, 60, 0x00, 0x04))
        assertEquals(listOf(1000), sample!!.ibiMs)
    }

    @Test
    fun `multiple RR intervals are all collected`() {
        val sample = StandardGattParsers.parseHeartRate(
            byteArrayOf(0x10, 60, 0x00, 0x04, 0x00, 0x02))
        assertEquals(listOf(1000, 500), sample!!.ibiMs)
    }

    @Test
    fun `energy expended field is skipped before RR intervals`() {
        // flags 0x18 = energy present + RR present. Energy (2 bytes) must be
        // stepped over, or the RR values decode from the wrong offset.
        val sample = StandardGattParsers.parseHeartRate(
            byteArrayOf(0x18, 65, 0xE8.toByte(), 0x03, 0x00, 0x04))
        assertEquals(65, sample!!.bpm)
        assertEquals(listOf(1000), sample.ibiMs)
    }

    @Test
    fun `zero bpm is rejected rather than reported as cardiac arrest`() {
        assertNull(StandardGattParsers.parseHeartRate(byteArrayOf(0x00, 0)))
    }

    @Test
    fun `implausible bpm is rejected`() {
        // 0x0190 = 400 bpm. 300 is the inclusive upper bound and stays valid.
        assertNull(StandardGattParsers.parseHeartRate(
            byteArrayOf(0x01, 0x90.toByte(), 0x01)))
        assertEquals(300, StandardGattParsers.parseHeartRate(
            byteArrayOf(0x01, 0x2C, 0x01))!!.bpm)
    }

    @Test
    fun `truncated packets return null instead of throwing`() {
        assertNull(StandardGattParsers.parseHeartRate(byteArrayOf()))
        assertNull(StandardGattParsers.parseHeartRate(byteArrayOf(0x00)))
        // Claims uint16 but supplies one byte.
        assertNull(StandardGattParsers.parseHeartRate(byteArrayOf(0x01, 0x48)))
    }

    @Test
    fun `sensor contact is reported only when supported`() {
        // bits 1-2 = 0b11 -> supported and detected
        assertEquals(true, StandardGattParsers.sensorContact(byteArrayOf(0x06)))
        // bits 1-2 = 0b10 -> supported, not detected
        assertEquals(false, StandardGattParsers.sensorContact(byteArrayOf(0x04)))
        // bits 1-2 = 0b00 -> not supported
        assertNull(StandardGattParsers.sensorContact(byteArrayOf(0x00)))
    }

    @Test
    fun `battery level parses and rejects out of range`() {
        assertEquals(87, StandardGattParsers.parseBattery(byteArrayOf(87))!!.percent)
        assertEquals(0, StandardGattParsers.parseBattery(byteArrayOf(0))!!.percent)
        assertNull(StandardGattParsers.parseBattery(byteArrayOf(101)))
        assertNull(StandardGattParsers.parseBattery(byteArrayOf()))
    }

    @Test
    fun `temperature parses IEEE-11073 float in celsius`() {
        // mantissa 3650, exponent -2 -> 36.50 C
        val data = byteArrayOf(0x00, 0x42, 0x0E, 0x00, 0xFE.toByte())
        assertEquals(36.5, StandardGattParsers.parseTemperature(data)!!, 0.001)
    }

    @Test
    fun `fahrenheit temperature is converted to celsius`() {
        // flags bit0 = 1 -> Fahrenheit. mantissa 9770, exp -2 -> 97.70 F = 36.5 C
        val data = byteArrayOf(0x01, 0x2A, 0x26, 0x00, 0xFE.toByte())
        assertEquals(36.5, StandardGattParsers.parseTemperature(data)!!, 0.01)
    }

    @Test
    fun `spo2 parses IEEE-11073 sfloat`() {
        // mantissa 98, exponent 0 -> 98%
        val data = byteArrayOf(0x00, 0x62, 0x00)
        assertEquals(98.0, StandardGattParsers.parseSpO2(data)!!, 0.001)
    }

    @Test
    fun `spo2 above 100 percent is rejected`() {
        // mantissa 150, exponent 0
        assertNull(StandardGattParsers.parseSpO2(byteArrayOf(0x00, 0x96.toByte(), 0x00)))
    }

    @Test
    fun `sig uuids expand to the standard base uuid`() {
        assertEquals(
            "0000180d-0000-1000-8000-00805f9b34fb",
            StandardGattProfiles.HEART_RATE_SERVICE.toString(),
        )
        assertEquals(
            "00002a37-0000-1000-8000-00805f9b34fb",
            StandardGattProfiles.HEART_RATE_MEASUREMENT.toString(),
        )
        assertEquals(
            "0000180f-0000-1000-8000-00805f9b34fb",
            StandardGattProfiles.BATTERY_SERVICE.toString(),
        )
    }
}
