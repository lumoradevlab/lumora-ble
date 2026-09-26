package dev.lumora.ble.oura

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import dev.lumora.ble.core.*
import dev.lumora.ble.transport.GattConnection
import dev.lumora.ble.transport.Notification
import dev.lumora.ble.transport.u8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.time.Instant

/**
 * Oura Ring connection.
 *
 * Flow: connect → discover → subscribe to notify char → request nonce →
 * encrypt under the stored key → authenticate → issue commands.
 *
 * The 16-byte auth key must already exist in the [CredentialStore]. A key can
 * only be installed on a factory-reset ring (see [provisionKey]); a ring already
 * paired to the Oura app will reject provisioning, so the user must reset it
 * first. This is the main practical constraint on the Oura path.
 */
class OuraConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    private val credentials: CredentialStore,
) : DeviceConnection {

    override val kind = DeviceKind.OURA_RING

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _readings = MutableSharedFlow<DeviceReading>(extraBufferCapacity = 128)
    override val readings: SharedFlow<DeviceReading> = _readings.asSharedFlow()

    private var link: GattConnection? = null

    override suspend fun connect(device: DiscoveredDevice) {
        _state.value = ConnectionState.Connecting
        val key = credentials.load(device.id)
            ?: throw DeviceException(DeviceError.PairingRequired(
                "No Oura auth key stored. Factory-reset the ring and call provisionKey()."))

        val gatt = GattConnection(context, scope)
        link = gatt
        try {
            gatt.connect(device.bluetoothDevice())
            gatt.requestMtu(OuraProtocol.MTU)
            gatt.subscribe(OuraProtocol.NOTIFY_CHAR)

            _state.value = ConnectionState.Authenticating
            authenticate(gatt, key)

            scope.launch { gatt.notifications.collect(::dispatch) }
            _state.value = ConnectionState.Ready(device)
        } catch (e: Throwable) {
            gatt.close()
            link = null
            _state.value = ConnectionState.Failed(
                (e as? DeviceException)?.error ?: DeviceError.ProtocolViolation(e.message ?: "connect failed"))
            throw e
        }
    }

    private suspend fun authenticate(gatt: GattConnection, key: ByteArray) {
        val nonceFrame = request(gatt, OuraProtocol.REQUEST_NONCE) {
            it[0] == OuraProtocol.TAG_EXTENDED && it.size > 2 && it[2] == OuraProtocol.EXT_NONCE_RSP
        } ?: throw DeviceException(DeviceError.OperationTimeout("nonce"))

        val nonce = OuraProtocol.parseNonce(nonceFrame)
            ?: throw DeviceException(DeviceError.ProtocolViolation("malformed nonce frame"))

        val answer = OuraAuth.encryptNonce(nonce, key)
        val authFrame = request(gatt, OuraProtocol.authenticate(answer)) {
            it[0] == OuraProtocol.TAG_EXTENDED && it.size > 2 && it[2] == OuraProtocol.EXT_AUTH_RSP
        } ?: throw DeviceException(DeviceError.OperationTimeout("authenticate"))

        if (OuraProtocol.parseAuthResult(authFrame) != true) {
            throw DeviceException(DeviceError.AuthRejected("ring rejected the stored auth key"))
        }
        Timber.i("Oura authenticated")
    }

    /**
     * Installs a new 16-byte key. Only succeeds on a factory-reset ring; an
     * already-paired ring answers with a non-zero result byte.
     */
    suspend fun provisionKey(device: DiscoveredDevice): ByteArray {
        val gatt = GattConnection(context, scope)
        try {
            gatt.connect(device.bluetoothDevice())
            gatt.subscribe(OuraProtocol.NOTIFY_CHAR)
            val key = OuraAuth.generateKey()
            val rsp = request(gatt, OuraProtocol.setAuthKey(key)) {
                it[0] == OuraProtocol.OP_SET_AUTH_KEY_RSP
            } ?: throw DeviceException(DeviceError.OperationTimeout("setAuthKey"))

            when (val result = if (rsp.size > 2) rsp.u8(2) else -1) {
                0x00 -> credentials.save(device.id, key)
                0x05 -> throw DeviceException(DeviceError.AuthRejected(
                    "ring reports production tests missing — it is not factory-reset"))
                else -> throw DeviceException(DeviceError.AuthRejected("setAuthKey failed: $result"))
            }
            return key
        } finally {
            gatt.close()
        }
    }

    /** Writes a command and waits for the first notification matching [predicate]. */
    private suspend fun request(
        gatt: GattConnection,
        command: ByteArray,
        timeoutMs: Long = 5_000,
        predicate: (ByteArray) -> Boolean,
    ): ByteArray? {
        val response = scope.async {
            withTimeoutOrNull(timeoutMs) {
                gatt.notifications.filter { it.characteristic == OuraProtocol.NOTIFY_CHAR }
                    .map { it.value }
                    .first { it.isNotEmpty() && predicate(it) }
            }
        }
        gatt.write(OuraProtocol.WRITE_CHAR, command)
        return response.await()
    }

    suspend fun battery(): BatteryLevel {
        val gatt = link ?: throw DeviceException(DeviceError.GattFailure(0, "not connected"))
        val frame = request(gatt, OuraProtocol.REQUEST_BATTERY) {
            it[0] == OuraProtocol.OP_BATTERY_RSP
        } ?: throw DeviceException(DeviceError.OperationTimeout("battery"))
        return BatteryLevel(percent = frame.u8(2).coerceIn(0, 100))
    }

    /** Enables the fast-HR connection profile and starts live measurement. */
    suspend fun startLiveHeartRate() {
        val gatt = link ?: throw DeviceException(DeviceError.GattFailure(0, "not connected"))
        gatt.write(OuraProtocol.WRITE_CHAR, OuraProtocol.bleMode(fastHeartRate = true))
        gatt.write(OuraProtocol.WRITE_CHAR, OuraProtocol.realtime(mode = 1))
    }

    private suspend fun dispatch(n: Notification) {
        if (n.characteristic != OuraProtocol.NOTIFY_CHAR) return
        val f = n.value
        if (f.isEmpty()) return
        when (f[0]) {
            OuraProtocol.OP_BATTERY_RSP ->
                _readings.emit(DeviceReading.Battery(BatteryLevel(f.u8(2)), Instant.now()))
            OuraProtocol.OP_REALTIME_RSP -> OuraEvents.parseRealtime(f)?.let { _readings.emit(it) }
            OuraProtocol.OP_EVENTS_RSP -> Timber.d("history batch complete")
            else -> Timber.v("unhandled oura frame tag=0x%02x", f[0])
        }
    }

    override suspend fun backfill(since: Instant): List<DeviceReading> {
        val gatt = link ?: throw DeviceException(DeviceError.GattFailure(0, "not connected"))
        val collected = mutableListOf<DeviceReading>()
        val job = scope.launch {
            gatt.notifications
                .filter { it.characteristic == OuraProtocol.NOTIFY_CHAR }
                .takeWhile { it.value.isEmpty() || it.value[0] != OuraProtocol.OP_EVENTS_RSP }
                .collect { n -> OuraEvents.parseHistory(n.value, since)?.let(collected::add) }
        }
        gatt.write(OuraProtocol.WRITE_CHAR, OuraProtocol.requestEvents())
        withTimeoutOrNull(60_000) { job.join() }
        job.cancel()
        return collected
    }

    /** Synchronous release; see [DeviceConnection.release]. */
    override fun release() {
        link?.close()
        link = null
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun disconnect() {
        link?.close()
        link = null
        _state.value = ConnectionState.Disconnected
    }

    private fun DiscoveredDevice.bluetoothDevice(): BluetoothDevice =
        context.getSystemService(BluetoothManager::class.java).adapter
            .getRemoteDevice(id.address)
}
