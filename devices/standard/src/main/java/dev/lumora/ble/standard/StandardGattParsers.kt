package dev.lumora.ble.standard

import dev.lumora.ble.core.BatteryLevel
import dev.lumora.ble.core.HeartRateSample
import dev.lumora.ble.transport.u16le
import dev.lumora.ble.transport.u8
import java.time.Instant

/**
 * Parsers for SIG standard measurement characteristics.
 *
 * Pure functions over bytes, with no Android types, so they are unit-tested on
 * the JVM. Each returns null on a malformed packet rather than throwing: a
 * single bad notification from a peripheral must not tear down a live session.
 */
object StandardGattParsers {

    /**
     * Heart Rate Measurement (0x2A37).
     *
     * Layout is variable and driven by the leading flags byte:
     * ```
     * bit 0    value format: 0 = uint8 BPM, 1 = uint16 LE BPM
     * bits 1-2 sensor contact: 0b11 = supported and detected
     * bit 3    energy expended present (uint16 LE, kJ)
     * bit 4    RR intervals present (uint16 LE each, units of 1/1024 s)
     * ```
     *
     * Getting bit 0 wrong is the classic bug here: assuming uint8 reads a
     * plausible but wrong BPM from a device reporting uint16, and desynchronises
     * every field after it.
     */
    fun parseHeartRate(data: ByteArray, at: Instant = Instant.now()): HeartRateSample? {
        if (data.size < 2) return null

        val flags = data.u8(0)
        val isUint16 = flags and 0x01 != 0
        val hasEnergy = flags and 0x08 != 0
        val hasRr = flags and 0x10 != 0

        var offset = 1
        val bpm = if (isUint16) {
            if (data.size < offset + 2) return null
            data.u16le(offset).also { offset += 2 }
        } else {
            data.u8(offset).also { offset += 1 }
        }

        // A peripheral that has lost skin contact reports 0; emitting it would
        // look like cardiac arrest in the consuming app.
        if (bpm <= 0 || bpm > 300) return null

        if (hasEnergy) offset += 2

        val rr = buildList {
            if (hasRr) {
                while (offset + 1 < data.size) {
                    // RR is in 1/1024 s; convert to ms so ibiMs means what it says.
                    add((data.u16le(offset) * 1000) / 1024)
                    offset += 2
                }
            }
        }

        return HeartRateSample(timestamp = at, bpm = bpm, ibiMs = rr)
    }

    /**
     * Sensor contact status, when the peripheral reports it.
     *
     * Bits 1-2: 0b00/0b01 = not supported, 0b10 = supported but not detected,
     * 0b11 = supported and detected. Null means the device does not report it.
     */
    fun sensorContact(data: ByteArray): Boolean? {
        if (data.isEmpty()) return null
        return when ((data.u8(0) shr 1) and 0x03) {
            0b10 -> false
            0b11 -> true
            else -> null
        }
    }

    /** Battery Level (0x2A19): a single percentage byte. */
    fun parseBattery(data: ByteArray): BatteryLevel? {
        if (data.isEmpty()) return null
        val percent = data.u8(0)
        if (percent > 100) return null
        return BatteryLevel(percent = percent)
    }

    /**
     * Temperature Measurement (0x2A1C).
     *
     * Flags bit 0 selects the unit (0 = Celsius, 1 = Fahrenheit); the value is
     * an IEEE-11073 32-bit FLOAT, not an IEEE-754 one — mantissa in the low 24
     * bits (signed), base-10 exponent in the top byte.
     */
    fun parseTemperature(data: ByteArray): Double? {
        if (data.size < 5) return null
        val fahrenheit = data.u8(0) and 0x01 != 0
        val celsius = parseFloat11073(data, 1) ?: return null
        return if (fahrenheit) (celsius - 32.0) * 5.0 / 9.0 else celsius
    }

    /**
     * PLX Continuous Measurement (0x2A5F): SpO2 then pulse rate, both as
     * IEEE-11073 16-bit SFLOATs immediately after the flags byte.
     */
    fun parseSpO2(data: ByteArray): Double? {
        if (data.size < 3) return null
        val spo2 = parseSFloat11073(data, 1) ?: return null
        if (spo2 <= 0.0 || spo2 > 100.0) return null
        return spo2
    }

    /** IEEE-11073 32-bit FLOAT: 24-bit signed mantissa + 8-bit base-10 exponent. */
    private fun parseFloat11073(data: ByteArray, offset: Int): Double? {
        if (data.size < offset + 4) return null
        val raw = data.u8(offset) or (data.u8(offset + 1) shl 8) or (data.u8(offset + 2) shl 16)
        // Sign-extend the 24-bit mantissa.
        val mantissa = if (raw and 0x800000 != 0) raw or 0xFF000000.toInt() else raw
        val exponent = data[offset + 3].toInt()
        // 0x007FFFFF is the reserved NaN mantissa.
        if (mantissa == 0x007FFFFF) return null
        return mantissa * Math.pow(10.0, exponent.toDouble())
    }

    /** IEEE-11073 16-bit SFLOAT: 12-bit signed mantissa + 4-bit signed exponent. */
    private fun parseSFloat11073(data: ByteArray, offset: Int): Double? {
        if (data.size < offset + 2) return null
        val raw = data.u16le(offset)
        var mantissa = raw and 0x0FFF
        var exponent = (raw shr 12) and 0x0F
        // Special values: NaN, NRes, +/-Infinity all live in the 0x07FE..0x0802 range.
        if (exponent == 0x0F && mantissa in 0x7FE..0x802) return null
        if (mantissa and 0x0800 != 0) mantissa = mantissa or 0xFFFFF000.toInt()
        if (exponent > 7) exponent -= 16
        return mantissa * Math.pow(10.0, exponent.toDouble())
    }
}
