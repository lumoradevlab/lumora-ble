package dev.lumora.ble.dexcom

import dev.lumora.ble.core.DeviceKind
import dev.lumora.ble.core.GlucoseReading
import dev.lumora.ble.core.GlucoseTrend
import dev.lumora.ble.transport.i16le
import dev.lumora.ble.transport.u16le
import dev.lumora.ble.transport.u32le
import dev.lumora.ble.transport.u8
import java.time.Instant

/** Transmitter response to `01` — our echoed token plus its own challenge. */
data class AuthChallenge(val encryptedToken: ByteArray, val challenge: ByteArray) {
    companion object {
        fun parse(bytes: ByteArray): AuthChallenge? {
            if (bytes.size < 17 || bytes[0] != DexcomProtocol.Opcode.AUTH_CHALLENGE_RX) return null
            return AuthChallenge(
                encryptedToken = bytes.copyOfRange(1, 9),
                challenge = bytes.copyOfRange(9, 17),
            )
        }
    }

    override fun equals(other: Any?) = other is AuthChallenge &&
        encryptedToken.contentEquals(other.encryptedToken) &&
        challenge.contentEquals(other.challenge)

    override fun hashCode() = 31 * encryptedToken.contentHashCode() + challenge.contentHashCode()
}

/**
 * Transmitter response to `04`.
 *
 * [bonded] is tri-state in practice: 2 means "authenticated, now bond me",
 * 1 means already bonded and ready for data.
 */
data class AuthStatus(val authenticated: Int, val bonded: Int) {
    val isAuthenticated: Boolean get() = authenticated == 1
    val needsBonding: Boolean get() = isAuthenticated && bonded == 2
    val isReady: Boolean get() = isAuthenticated && bonded == 1

    companion object {
        fun parse(bytes: ByteArray): AuthStatus? {
            if (bytes.size < 3 || bytes[0] != DexcomProtocol.Opcode.AUTH_STATUS_RX) return null
            return AuthStatus(bytes.u8(1), bytes.u8(2))
        }
    }
}

/** Sensor lifecycle, which decides whether a reading is usable. */
enum class CalibrationState(val code: Int) {
    STOPPED(0x01),
    WARMING_UP(0x02),
    NEEDS_CALIBRATION(0x04),
    OK(0x06),
    NEEDS_SECOND_CALIBRATION(0x05),
    SENSOR_FAILED(0x0b),
    UNKNOWN(-1);

    /** Only OK yields a trustworthy glucose value. */
    val producesGlucose: Boolean get() = this == OK

    companion object {
        fun from(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * Glucose message (`0x31`).
 *
 * Layout, little-endian:
 *   0     opcode
 *   1     transmitter status
 *   2..5  sequence
 *   6..9  timestamp, seconds since transmitter activation
 *   10..11 glucose; bit 0xF000 flags display-only, low 12 bits are mg/dL
 *   12    calibration state
 *   13    trend, signed, mg/dL per minute
 */
data class GlucoseMessage(
    val status: Int,
    val sequence: Long,
    val transmitterSeconds: Long,
    val glucoseMgdl: Int,
    val isDisplayOnly: Boolean,
    val state: CalibrationState,
    val trendRaw: Int,
) {
    fun toReading(activationTime: Instant): GlucoseReading? {
        if (!state.producesGlucose) return null
        if (glucoseMgdl !in MIN_MGDL..MAX_MGDL) return null
        val rate = trendRaw.toDouble()
        return GlucoseReading(
            timestamp = activationTime.plusSeconds(transmitterSeconds),
            mgdl = glucoseMgdl,
            trend = trendFrom(rate),
            rateOfChange = rate,
            source = DeviceKind.DEXCOM_SENSOR,
            isHistorical = false,
        )
    }

    companion object {
        private const val MIN_MGDL = 20
        private const val MAX_MGDL = 500

        fun parse(bytes: ByteArray): GlucoseMessage? {
            if (bytes.size < 14 || bytes[0] != DexcomProtocol.Opcode.GLUCOSE_RX) return null
            val rawGlucose = bytes.u16le(10)
            return GlucoseMessage(
                status = bytes.u8(1),
                sequence = bytes.u32le(2),
                transmitterSeconds = bytes.u32le(6),
                glucoseMgdl = rawGlucose and 0x0FFF,
                isDisplayOnly = (rawGlucose and 0xF000) > 0,
                state = CalibrationState.from(bytes.u8(12)),
                // Signed: negative means falling.
                trendRaw = bytes[13].toInt(),
            )
        }

        fun trendFrom(mgdlPerMinute: Double): GlucoseTrend = when {
            mgdlPerMinute >= 3.0 -> GlucoseTrend.RISING_RAPIDLY
            mgdlPerMinute >= 2.0 -> GlucoseTrend.RISING
            mgdlPerMinute >= 1.0 -> GlucoseTrend.RISING_SLIGHTLY
            mgdlPerMinute > -1.0 -> GlucoseTrend.FLAT
            mgdlPerMinute > -2.0 -> GlucoseTrend.FALLING_SLIGHTLY
            mgdlPerMinute > -3.0 -> GlucoseTrend.FALLING
            else -> GlucoseTrend.FALLING_RAPIDLY
        }
    }
}

/** Battery/voltage summary carried in the status byte. */
enum class TransmitterStatus(val code: Int) {
    OK(0x00), LOW_BATTERY(0x81), BRICKED(0x83), UNKNOWN(-1);

    companion object {
        fun from(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
