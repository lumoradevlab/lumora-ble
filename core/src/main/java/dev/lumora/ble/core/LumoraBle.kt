package dev.lumora.ble.core

import kotlinx.coroutines.flow.Flow
import java.time.Instant

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

    /**
     * Pulls stored history from one device.
     *
     * Returns an empty list for devices that keep no history — the standard
     * GATT profiles define none — rather than throwing, so a caller can poll
     * every connected device uniformly.
     */
    suspend fun backfill(id: DeviceId, since: Instant): List<DeviceReading>

    /**
     * Reads a FreeStyle Libre 1/2 sensor from an NFC tag.
     *
     * Libre is the one supported device that is not reached over BLE, so it
     * does not appear in [scan] or [connect]: the user taps the phone against
     * the sensor and the whole reading history transfers in one pass. Pass the
     * `android.nfc.Tag` delivered by your NFC intent or reader-mode callback.
     *
     * Emits the decoded readings on [readings] as well as returning them, so a
     * consumer observing one merged stream sees Libre data alongside everything
     * else rather than having to special-case it.
     *
     * [tag] is typed loosely because this interface stays free of Android
     * framework types; anything other than an `android.nfc.Tag` is rejected
     * with [DeviceError.ProtocolViolation].
     *
     * @throws DeviceException if the tag is not a Libre sensor or the read
     *   fails part-way — a partial NFC transfer is never returned as data.
     */
    suspend fun readLibreTag(tag: Any): List<DeviceReading>

    /**
     * True if every runtime permission BLE needs is granted.
     *
     * This only reports status; prompting requires an Activity, so the host app
     * (or the Flutter plugin's activity binding) must request them — pass
     * [requiredPermissions] to your permission launcher.
     */
    fun requestPermissions(): Boolean

    /**
     * The runtime permissions this build needs, for the host app's permission
     * launcher.
     *
     * Exposed here because [requestPermissions] only reports status, so
     * without it a consumer could not prompt at all without reaching into the
     * transport module — which is not on their compile classpath by design.
     *
     * The set differs by API level: 31+ uses BLUETOOTH_SCAN/BLUETOOTH_CONNECT,
     * below that ACCESS_FINE_LOCATION, without which scans silently return
     * nothing.
     */
    val requiredPermissions: Array<String>

    /**
     * Disconnects everything and releases the SDK's internal coroutine scope.
     *
     * An instance is unusable afterwards; create a new one to reconnect. Call
     * this when the owning component goes away — an SDK instance scoped to an
     * Activity or ViewModel and never closed keeps its collectors alive for the
     * life of the process.
     *
     * Idempotent.
     */
    suspend fun close()

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
