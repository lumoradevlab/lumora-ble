package dev.lumora.ble.sdk

import android.annotation.SuppressLint
import android.content.Context
import dev.lumora.ble.core.*
import dev.lumora.ble.dexcom.DexcomAuth
import dev.lumora.ble.dexcom.DexcomConnection
import dev.lumora.ble.dexcom.DexcomProtocol
import dev.lumora.ble.oura.OuraConnection
import dev.lumora.ble.oura.OuraProtocol
import dev.lumora.ble.transport.BlePermissions
import dev.lumora.ble.transport.BleScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [LumoraBle] implementation.
 *
 * Owns one [DeviceConnection] per connected device and merges their readings
 * into a single stream. Connections are created lazily per device kind, so a
 * consumer that only uses Oura never touches the CGM code paths.
 */
// Permissions are verified in scan()/connect() via BlePermissions before any
// BluetoothDevice member is touched; lint cannot see across that check.
@SuppressLint("MissingPermission")
internal class LumoraBleImpl(
    private val context: Context,
    private val credentials: CredentialStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : LumoraBle {

    private val scanner = BleScanner(context)
    private val active = ConcurrentHashMap<DeviceId, DeviceConnection>()
    private val connectLock = Mutex()

    private val _connections = MutableStateFlow<Map<DeviceId, ConnectionState>>(emptyMap())
    override val connections: Flow<Map<DeviceId, ConnectionState>> = _connections.asStateFlow()

    private val _readings = MutableSharedFlow<DeviceReading>(extraBufferCapacity = 256)
    override val readings: Flow<DeviceReading> = _readings.asSharedFlow()

    override val supportedDevices: List<DeviceSupport> = SupportMatrix.all

    override fun requestPermissions(): Boolean = BlePermissions.allGranted(context)

    override fun scan(kind: DeviceKind): Flow<DiscoveredDevice> {
        if (!BlePermissions.allGranted(context)) {
            return flow {
                throw DeviceException(DeviceError.BluetoothUnavailable(
                    "missing permissions: ${BlePermissions.missing(context).joinToString()}"))
            }
        }

        // Libre 1/2 are NFC devices and never appear in a BLE scan.
        if (kind == DeviceKind.LIBRE_SENSOR) {
            return flow {
                throw DeviceException(DeviceError.ProtocolViolation(
                    "Libre sensors are read over NFC, not BLE. Use LibreNfcReader.read(tag)."))
            }
        }

        val services = when (kind) {
            DeviceKind.OURA_RING -> listOf(OuraProtocol.SERVICE)
            DeviceKind.DEXCOM_SENSOR -> listOf(DexcomProtocol.SERVICE)
            DeviceKind.LIBRE_SENSOR -> emptyList()
        }
        // A G6 advertises as "Dexcom" + the last two serial characters, so a
        // configured serial narrows the scan to that one transmitter.
        val namePrefix = when (kind) {
            DeviceKind.DEXCOM_SENSOR ->
                dexcomConfig?.serial?.let { DexcomProtocol.advertisedName(it) } ?: "Dexcom"
            else -> null
        }

        return scanner.scan(services, namePrefix)
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

        // Mirror this connection's state and readings into the merged streams.
        connection.state
            .onEach { st -> _connections.update { it + (device.id to st) } }
            .launchIn(scope)
        connection.readings
            .onEach { _readings.emit(it) }
            .launchIn(scope)

        try {
            connection.connect(device)
        } catch (e: Throwable) {
            active.remove(device.id)
            throw e
        }
    }

    private fun create(kind: DeviceKind): DeviceConnection = when (kind) {
        DeviceKind.OURA_RING -> OuraConnection(context, scope, credentials)

        DeviceKind.DEXCOM_SENSOR -> {
            val config = dexcomConfig ?: throw DeviceException(DeviceError.PairingRequired(
                "Dexcom needs the transmitter serial. Call setDexcomTransmitter(serial, " +
                    "sessionStart) before connecting."))
            DexcomConnection(context, scope, config.serial, config.sessionStart)
        }

        // Libre 1/2 are NFC, not BLE — read them with LibreNfcReader instead.
        DeviceKind.LIBRE_SENSOR -> throw DeviceException(DeviceError.ProtocolViolation(
            "Libre sensors are read over NFC, not BLE. Use LibreNfcReader.read(tag)."))
    }

    /**
     * Supplies the Dexcom transmitter serial and current session start.
     *
     * The serial is the credential — there is nothing to provision on the
     * transmitter, but without it the key cannot be derived.
     */
    override fun setDexcomTransmitter(serial: String, sessionStart: Instant) {
        dexcomConfig = DexcomConfig(DexcomAuth.validateSerial(serial), sessionStart)
    }

    private data class DexcomConfig(val serial: String, val sessionStart: Instant)

    private var dexcomConfig: DexcomConfig? = null

    override suspend fun disconnect(id: DeviceId) {
        active.remove(id)?.disconnect()
        _connections.update { it - id }
    }

    override suspend fun disconnectAll() {
        active.keys.toList().forEach { runCatching { disconnect(it) } }
    }

    override suspend fun backfill(id: DeviceId, sinceEpochMillis: Long): List<DeviceReading> {
        val connection = active[id]
            ?: throw DeviceException(DeviceError.GattFailure(0, "device not connected"))
        return connection.backfill(Instant.ofEpochMilli(sinceEpochMillis))
    }
}

/**
 * Creates an SDK instance.
 *
 * [credentials] defaults to an encrypted store; supply your own only if you
 * already have a secure keystore. Never back this with plain SharedPreferences —
 * these are the keys to a user's health data.
 */
fun LumoraBle.Companion.create(
    context: Context,
    credentials: CredentialStore = EncryptedCredentialStore(context),
): LumoraBle = LumoraBleImpl(context.applicationContext, credentials)
