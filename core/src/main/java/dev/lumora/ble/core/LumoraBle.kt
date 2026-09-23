package dev.lumora.ble.core

import kotlinx.coroutines.flow.Flow

/**
 * The single entry point third-party apps integrate against.
 *
 * This is deliberately the whole public surface of the SDK: one interface that
 * hides which transport, protocol, or vendor a device uses. The Flutter plugin
 * is a thin binding over exactly this — no protocol logic lives above it — so
 * native and Flutter consumers get identical behaviour.
 *
 * Usage:
 * ```
 * val sdk = LumoraBle.create(context)
 * sdk.scan(DeviceKind.OURA_RING).collect { found -> sdk.connect(found) }
 * sdk.readings.collect { reading -> ... }
 * ```
 */
interface LumoraBle {

    /** Devices this build can actually talk to, with their current support level. */
    val supportedDevices: List<DeviceSupport>

    /** Emits discovered devices until the flow is cancelled. */
    fun scan(kind: DeviceKind): Flow<DiscoveredDevice>

    /** Connection state for every managed device, keyed by id. */
    val connections: Flow<Map<DeviceId, ConnectionState>>

    /** Merged live readings from all connected devices. */
    val readings: Flow<DeviceReading>

    suspend fun connect(device: DiscoveredDevice)

    suspend fun disconnect(id: DeviceId)

    suspend fun disconnectAll()

    /** Pulls stored history from one device. */
    suspend fun backfill(id: DeviceId, sinceEpochMillis: Long): List<DeviceReading>

    /**
     * Supplies the Dexcom transmitter serial (6 characters, printed on the
     * applicator) and the current sensor session start.
     *
     * The serial IS the credential for a G6 — the encryption key is derived from
     * it — so this must be set before connecting to a Dexcom sensor.
     */
    fun setDexcomTransmitter(serial: String, sessionStart: java.time.Instant)

    /**
     * True if every runtime permission BLE needs is granted.
     *
     * This only reports status; prompting requires an Activity, so the host app
     * (or the Flutter plugin's activity binding) must request them.
     */
    fun requestPermissions(): Boolean

    companion object
}

/**
 * Honest per-device capability reporting.
 *
 * Exposed at runtime so an integrating app can disable a device in its UI rather
 * than discovering at connect time that the protocol is blocked.
 */
data class DeviceSupport(
    val kind: DeviceKind,
    val status: SupportStatus,
    /** What the user must do out-of-band before the device will work, if anything. */
    val prerequisite: String? = null,
    /** Why the device is limited, when it is. */
    val limitation: String? = null,
    /**
     * How this device is reached. Not every supported device is BLE — Libre 1/2
     * are NFC — and the distinction changes the UX completely: NFC is a
     * deliberate tap-to-scan, BLE is a background connection.
     */
    val transport: Transport = Transport.BLE,
)

enum class Transport {
    BLE,

    /** A tap-to-read exchange, not a persistent connection. */
    NFC,

    /**
     * An OS-mediated health store rather than a radio link — HealthKit on iOS.
     *
     * Changes the UX as sharply as NFC does: there is nothing to scan for and
     * no connection to maintain. The user grants permission once and the
     * system delivers data.
     */
    HEALTH_KIT,
}

enum class SupportStatus {
    /** Implemented and usable. */
    SUPPORTED,

    /** Implemented but needs a user step first (NFC activation, factory reset). */
    REQUIRES_SETUP,

    /**
     * Protocol is mapped but a blocking dependency is missing — a vendor-issued
     * credential or an unavailable crypto primitive.
     */
    BLOCKED,
}
