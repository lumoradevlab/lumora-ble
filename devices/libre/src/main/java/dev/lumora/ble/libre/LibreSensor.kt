package dev.lumora.ble.libre

/**
 * FreeStyle Libre sensor identity and lifecycle.
 *
 * Libre 1/2 are read over **NFC** (ISO 15693), not BLE — the 344-byte FRAM image
 * is transferred in one pass. Libre 3 moved to a BLE-only, certificate-gated
 * transport that this SDK does not implement; see docs/PROTOCOL-STATUS.md.
 */
enum class LibreSensorType(val patchPrefix: String, val displayName: String) {
    LIBRE_1("DF", "Libre 1"),
    LIBRE_1_A2("A2", "Libre 1 (new)"),
    LIBRE_2("9D", "Libre 2"),
    LIBRE_2_EU("C5", "Libre 2 EU"),
    LIBRE_US("E5", "Libre US"),
    LIBRE_PRO_H("70", "Libre Pro/H"),
    UNKNOWN("", "Unknown");

    /**
     * Pro/H stores its counters at different FRAM offsets than every other
     * variant, which is the one branch the parser has to make.
     */
    val usesProLayout: Boolean get() = this == LIBRE_PRO_H

    companion object {
        fun fromPatchInfo(patchInfo: ByteArray): LibreSensorType {
            if (patchInfo.isEmpty()) return UNKNOWN
            val prefix = "%02X".format(patchInfo[0])
            return entries.firstOrNull { it.patchPrefix == prefix } ?: UNKNOWN
        }
    }
}

/** Sensor lifecycle, read from FRAM byte 4. */
enum class LibreSensorState(val code: Int) {
    NOT_YET_STARTED(0x01),
    STARTING(0x02),
    READY(0x03),
    EXPIRED(0x04),
    SHUTDOWN(0x05),
    FAILURE(0x06),
    UNKNOWN(0x00);

    /** Only a READY sensor produces trustworthy glucose. */
    val producesGlucose: Boolean get() = this == READY

    companion object {
        fun fromCode(code: Int): LibreSensorState =
            entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** Calibration parameters derived per sensor; without them, values are raw. */
data class Libre1CalibrationParameters(
    val slopeSlope: Double,
    val slopeOffset: Double,
    val offsetOffset: Double,
    val offsetSlope: Double,
    val isValidForFooterWithReverseCRCs: Int,
)
