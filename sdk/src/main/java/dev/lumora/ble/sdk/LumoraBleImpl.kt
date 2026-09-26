package dev.lumora.ble.sdk

import android.annotation.SuppressLint
import android.content.Context
import dev.lumora.ble.core.*
import dev.lumora.ble.transport.BlePermissions
import dev.lumora.ble.transport.BleScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Default [LumoraBle] implementation.
 *
 * Owns one [DeviceConnection] per connected device and merges their readings
 * into a single stream.
 *
 * Note what this class does *not* import: no device module appears here. Every
 * protocol arrives through the [ProtocolRegistry] the consumer builds, so a
 * build that installs only standard SIG heart rate never links the vendor code
 * — which is what keeps those vendors' terms of service out of an app that
 * did not opt in.
 */
// Permissions are verified in scan()/connect() via BlePermissions before any
// BluetoothDevice member is touched; lint cannot see across that check.
@SuppressLint("MissingPermission")
internal class LumoraBleImpl(
    private val context: Context,
    private val credentials: CredentialStore,
    private val registry: ProtocolRegistry,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : LumoraBle {

    private val scanner = BleScanner(context)
    private val active = ConcurrentHashMap<DeviceId, DeviceConnection>()

    /**
     * The collectors mirroring each connection into the merged streams.
     *
     * Held so [disconnect] can cancel them. Without this they accumulate in
     * [scope] for the life of the process — a reconnect loop would leak two
     * coroutines per cycle.
     */
    private val mirrors = ConcurrentHashMap<DeviceId, List<Job>>()
    private val connectLock = Mutex()
    private val closed = AtomicBoolean(false)

    /** Handed to each protocol so it can build a connection. */
    private val protocolContext = object : ProtocolContext {
        override val androidContext: Any get() = this@LumoraBleImpl.context
        override val scope: CoroutineScope get() = this@LumoraBleImpl.scope
        override val credentials: CredentialStore get() = this@LumoraBleImpl.credentials
    }

    private val _connections = MutableStateFlow<Map<DeviceId, ConnectionState>>(emptyMap())
    override val connections: Flow<Map<DeviceId, ConnectionState>> = _connections.asStateFlow()

    private val _readings = MutableSharedFlow<DeviceReading>(extraBufferCapacity = 256)
    override val readings: Flow<DeviceReading> = _readings.asSharedFlow()

    /**
     * Only the kinds this build installed a protocol for.
     *
     * Reporting the full matrix would advertise devices whose code is not in
     * the APK, which is exactly the confusion opt-in protocols exist to avoid.
     */
    override val supportedDevices: List<DeviceSupport> =
        SupportMatrix.all.filter { it.kind in registry.installed }

    override fun requestPermissions(): Boolean = BlePermissions.allGranted(context)

    override val requiredPermissions: Array<String> get() = BlePermissions.required()

    override fun scan(kind: DeviceKind): Flow<DiscoveredDevice> {
        if (!BlePermissions.allGranted(context)) {
            return flow {
                throw DeviceException(DeviceError.BluetoothUnavailable(
                    "missing permissions: ${BlePermissions.missing(context).joinToString()}"))
            }
        }

        val protocol = registry[kind] ?: return flow { throw notInstalled(kind) }

        // A protocol contributing no scan services is not discovered by
        // scanning — Libre is tapped over NFC. Failing loudly beats an empty
        // stream, which looks like a hardware fault.
        if (protocol.scanServices.isEmpty()) {
            return flow {
                throw DeviceException(DeviceError.ProtocolViolation(
                    "$kind is not discovered by scanning. " + if (kind == DeviceKind.LIBRE_SENSOR) {
                        "Libre sensors are read over NFC: call readLibreTag(tag)."
                    } else {
                        "See supportedDevices() for how it is reached."
                    }))
            }
        }

        return scanner.scan(protocol.scanServices, protocol.scanNamePrefix())
            .map { result ->
                DiscoveredDevice(
                    id = DeviceId(result.device.address, kind),
                    name = runCatching { result.device.name }.getOrNull()
                        ?: result.scanRecord?.deviceName,
                    rssi = result.rssi,
                )
            }
            // The old implementation connected on every scan callback for the
            // same device; de-duplicating here is what prevents that.
            .distinctUntilChangedBy { it.id }
    }

    override suspend fun connect(device: DiscoveredDevice) = connectLock.withLock {
        if (active.containsKey(device.id)) return@withLock

        val support = SupportMatrix.forKind(device.id.kind)
        if (support.status == SupportStatus.BLOCKED) {
            throw DeviceException(DeviceError.PairingRequired(
                support.limitation ?: "device not supported over BLE"))
        }

        val connection = create(device.id.kind)
        active[device.id] = connection

        // Mirror this connection's state and readings into the merged streams,
        // keeping the jobs so disconnect() can stop them.
        mirrors[device.id] = listOf(
            connection.state
                .onEach { st -> _connections.update { it + (device.id to st) } }
                .launchIn(scope),
            connection.readings
                .onEach { _readings.emit(it) }
                .launchIn(scope),
        )

        try {
            connection.connect(device)
        } catch (e: Throwable) {
            active.remove(device.id)
            mirrors.remove(device.id)?.forEach { it.cancel() }
            throw e
        }
    }

    private fun create(kind: DeviceKind): DeviceConnection =
        (registry[kind] ?: throw notInstalled(kind)).create(protocolContext)

    /**
     * The error for a kind whose module was not installed.
     *
     * Lists what *is* installed, because the fix is a build-file change and a
     * consumer has no other way to see what this build can reach.
     */
    private fun notInstalled(kind: DeviceKind) = DeviceException(
        DeviceError.PairingRequired(
            "No protocol installed for $kind. Add its module to your build and " +
                "install it in LumoraBle.create(context) { install(...) }. " +
                "Installed: ${registry.installed.joinToString().ifEmpty { "none" }}"))

    /**
     * Reads a Libre sensor over NFC and republishes the result on [readings].
     *
     * Libre never appears in [scan] or [connect], so without this the single
     * merged stream the SDK promises would silently exclude a supported device.
     */
    override suspend fun readLibreTag(tag: Any): List<DeviceReading> {
        val protocol = registry[DeviceKind.LIBRE_SENSOR]
            ?: throw notInstalled(DeviceKind.LIBRE_SENSOR)

        val reader = protocol as? NfcTagReader ?: throw DeviceException(
            DeviceError.ProtocolViolation(
                "installed Libre protocol does not support tag reading"))

        val readings = reader.readTag(tag)
        readings.forEach { _readings.emit(it) }
        return readings
    }

    override suspend fun disconnect(id: DeviceId) {
        active.remove(id)?.disconnect()
        // Cancel after disconnecting, so the final Disconnected state is still
        // mirrored to observers before the collector stops.
        mirrors.remove(id)?.forEach { it.cancel() }
        _connections.update { it - id }
    }

    override suspend fun disconnectAll() {
        active.keys.toList().forEach { runCatching { disconnect(it) } }
    }

    /**
     * Synchronous teardown, so a caller can release from onCleared() or
     * onDestroy() without launching a coroutine that may never run.
     *
     * Order matters. Each connection's GATT client is released first and
     * synchronously — Android caps an app at ~32 client interfaces and a
     * leaked one stays leaked until the process restarts — and only then is
     * the scope cancelled. Doing it the other way round would kill the
     * coroutines mid-release.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        active.values.forEach { runCatching { it.release() } }
        active.clear()
        mirrors.values.forEach { jobs -> jobs.forEach { it.cancel() } }
        mirrors.clear()
        _connections.value = emptyMap()

        scope.cancel()
    }

    override suspend fun backfill(id: DeviceId, since: Instant): List<DeviceReading> {
        val connection = active[id]
            ?: throw DeviceException(DeviceError.GattFailure(0, "device not connected"))
        return connection.backfill(since)
    }
}
