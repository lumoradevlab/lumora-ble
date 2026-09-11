package dev.lumora.ble.core

import java.time.Instant

/** Identifies a physical device across scan/connect/reconnect cycles. */
data class DeviceId(val address: String, val kind: DeviceKind)

enum class DeviceKind { OURA_RING, LIBRE_SENSOR, DEXCOM_SENSOR }

data class DiscoveredDevice(
    val id: DeviceId,
    val name: String?,
    val rssi: Int,
    val serial: String? = null,
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Scanning : ConnectionState
    data object Connecting : ConnectionState
    /** Link is up but the device has not yet been authenticated. */
    data object Authenticating : ConnectionState
    data class Ready(val device: DiscoveredDevice) : ConnectionState
    data class Failed(val reason: DeviceError) : ConnectionState
}

/**
 * Every failure mode the three protocols can produce. Kept as a closed set so
 * callers can exhaustively handle them instead of catching raw exceptions.
 */
sealed interface DeviceError {
    /** Bluetooth is off, unsupported, or runtime permissions were denied. */
    data class BluetoothUnavailable(val detail: String) : DeviceError
    data class ScanTimeout(val kind: DeviceKind) : DeviceError
    /** Android GATT status code; 133 is the notorious generic failure. */
    data class GattFailure(val status: Int, val operation: String) : DeviceError
    /** Device refused our credentials — wrong/absent key, expired session. */
    data class AuthRejected(val detail: String) : DeviceError
    /** We have no stored credential and cannot obtain one over BLE alone. */
    data class PairingRequired(val instruction: String) : DeviceError
    data class ProtocolViolation(val detail: String) : DeviceError
    data class OperationTimeout(val operation: String) : DeviceError
}

class DeviceException(val error: DeviceError) : Exception(error.toString())

/** A single glucose measurement. Value is always mg/dL; convert at the UI edge. */
data class GlucoseReading(
    val timestamp: Instant,
    val mgdl: Int,
    val trend: GlucoseTrend,
    /** mg/dL per minute, when the sensor reports it. */
    val rateOfChange: Double? = null,
    val source: DeviceKind,
    /** True for backfilled history, false for a live push. */
    val isHistorical: Boolean = false,
)

enum class GlucoseTrend {
    RISING_RAPIDLY, RISING, RISING_SLIGHTLY, FLAT,
    FALLING_SLIGHTLY, FALLING, FALLING_RAPIDLY, UNKNOWN,
}

data class HeartRateSample(
    val timestamp: Instant,
    val bpm: Int,
    /** Inter-beat intervals in milliseconds, when available. */
    val ibiMs: List<Int> = emptyList(),
)

data class BatteryLevel(val percent: Int, val charging: Boolean = false)

/** Union of everything a device can emit. Consumers filter by type. */
sealed interface DeviceReading {
    val timestamp: Instant

    data class Glucose(val reading: GlucoseReading) : DeviceReading {
        override val timestamp: Instant get() = reading.timestamp
    }
    data class HeartRate(val sample: HeartRateSample) : DeviceReading {
        override val timestamp: Instant get() = sample.timestamp
    }
    data class Battery(val level: BatteryLevel, override val timestamp: Instant) : DeviceReading
    data class Temperature(val celsius: Double, override val timestamp: Instant) : DeviceReading
    data class SpO2(val percent: Double, override val timestamp: Instant) : DeviceReading
}
