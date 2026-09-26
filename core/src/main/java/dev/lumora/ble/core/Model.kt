package dev.lumora.ble.core

import java.time.Instant

/** Identifies a physical device across scan/connect/reconnect cycles. */
data class DeviceId(val address: String, val kind: DeviceKind)

enum class DeviceKind {
    OURA_RING,
    LIBRE_SENSOR,
    DEXCOM_SENSOR,

    /**
     * Any peripheral speaking the SIG standard GATT profiles (Heart Rate
     * 0x180D, Battery 0x180F, Health Thermometer 0x1809, Pulse Oximeter
     * 0x1822).
     *
     * Not a vendor: one kind covers Fitbit Charge 6, Fitbit Air, Pixel Watch
     * 2+, Polar, Wahoo, Garmin straps and generic oximeters, because the
     * protocol is published rather than reverse-engineered.
     */
    HEART_RATE_MONITOR,

    /**
     * A Fitbit tracker's own sync protocol — activity, sleep and stored
     * history. Blocked; see [SupportMatrix]. Distinct from
     * [HEART_RATE_MONITOR], which is how a Fitbit is actually reachable.
     */
    FITBIT_TRACKER,

    /**
     * A Pixel Watch as a full companion device. Blocked; see [SupportMatrix].
     * Its heart rate is reachable via [HEART_RATE_MONITOR].
     */
    PIXEL_WATCH,

    /**
     * An Apple Watch, read through HealthKit.
     *
     * Supported by the iOS build only. Present in this enum so both platforms
     * share one [DeviceKind] vocabulary across the Flutter channel; the
     * Android [SupportMatrix] reports it BLOCKED.
     */
    APPLE_WATCH,
}

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

    /**
     * Heart rate variability as a single SDNN figure in milliseconds.
     *
     * Distinct from [HeartRate.sample] and its `ibiMs`: those are raw
     * beat-to-beat intervals, whereas this is a statistic computed over a
     * window. Folding SDNN into `ibiMs` made HRV indistinguishable from a
     * heart-rate sample downstream, which is why it has its own type.
     */
    data class HeartRateVariability(
        val sdnnMs: Double,
        override val timestamp: Instant,
    ) : DeviceReading
}
