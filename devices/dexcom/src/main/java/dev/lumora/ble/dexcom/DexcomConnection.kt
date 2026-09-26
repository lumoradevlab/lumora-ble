package dev.lumora.ble.dexcom

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import dev.lumora.ble.core.*
import dev.lumora.ble.transport.GattConnection
import dev.lumora.ble.transport.Notification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.time.Instant

/**
 * Dexcom G6 connection.
 *
 * The transmitter serial is the credential — there is nothing to provision, so
 * unlike Oura this needs no factory reset. The caller supplies the serial
 * printed on the applicator.
 *
 * Two things about the G6 that shape this class:
 *  - Authentication is **mutual**. We verify the transmitter's echo of our token
 *    before answering its challenge, so a rogue transmitter cannot feed us data.
 *  - The transmitter drops the link aggressively after a session; reconnecting
 *    per reading cycle is normal, not an error.
 */
class DexcomConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Serial printed on the applicator, e.g. "8UMS7E". */
    private val transmitterSerial: String,
    /** When the current sensor session started; timestamps are relative to it. */
    private val sessionStart: Instant,
) : DeviceConnection {

    override val kind = DeviceKind.DEXCOM_SENSOR

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _readings = MutableSharedFlow<DeviceReading>(extraBufferCapacity = 128)
    override val readings: SharedFlow<DeviceReading> = _readings.asSharedFlow()

    private var link: GattConnection? = null

    override suspend fun connect(device: DiscoveredDevice) {
        DexcomAuth.requireG6(device.name ?: "")
        DexcomAuth.validateSerial(transmitterSerial)

        _state.value = ConnectionState.Connecting
        val gatt = GattConnection(context, scope)
        link = gatt
        try {
            gatt.connect(device.bluetoothDevice())
            // The auth characteristic uses indications, not notifications.
            gatt.subscribe(DexcomProtocol.AUTHENTICATION, indication = true)

            _state.value = ConnectionState.Authenticating
            authenticate(gatt)

            gatt.subscribe(DexcomProtocol.CONTROL, indication = true)
            scope.launch { gatt.notifications.collect(::dispatch) }

            _state.value = ConnectionState.Ready(device)
        } catch (e: Throwable) {
            gatt.close()
            link = null
            _state.value = ConnectionState.Failed(
                (e as? DeviceException)?.error
                    ?: DeviceError.ProtocolViolation(e.message ?: "connect failed"))
            throw e
        }
    }

    private suspend fun authenticate(gatt: GattConnection) {
        val token = DexcomAuth.generateToken()

        val challengeFrame = request(gatt, DexcomProtocol.authRequest(token)) {
            it[0] == DexcomProtocol.Opcode.AUTH_CHALLENGE_RX
        } ?: throw DeviceException(DeviceError.OperationTimeout("auth challenge"))

        val challenge = AuthChallenge.parse(challengeFrame)
            ?: throw DeviceException(DeviceError.ProtocolViolation("malformed auth challenge"))

        // Mutual auth: prove the transmitter holds our serial-derived key before
        // we answer. Skipping this would let any nearby device impersonate it.
        if (!DexcomAuth.verifyTransmitter(token, challenge.encryptedToken, transmitterSerial)) {
            throw DeviceException(DeviceError.AuthRejected(
                "Transmitter did not echo our token correctly — wrong serial, or " +
                    "this is not the transmitter we expected."))
        }

        val answer = DexcomAuth.encryptChallenge(challenge.challenge, transmitterSerial)
        val statusFrame = request(gatt, DexcomProtocol.authChallengeReply(answer)) {
            it[0] == DexcomProtocol.Opcode.AUTH_STATUS_RX
        } ?: throw DeviceException(DeviceError.OperationTimeout("auth status"))

        val status = AuthStatus.parse(statusFrame)
            ?: throw DeviceException(DeviceError.ProtocolViolation("malformed auth status"))

        if (!status.isAuthenticated) {
            throw DeviceException(DeviceError.AuthRejected(
                "Transmitter rejected authentication (serial may be wrong)"))
        }

        if (status.needsBonding) {
            Timber.i("Dexcom authenticated; requesting bond")
            gatt.write(DexcomProtocol.AUTHENTICATION, DexcomProtocol.BOND_REQUEST)
            // Bonding is driven by the Android stack and may prompt the user.
            gatt.bond()
        }
        Timber.i("Dexcom ready")
    }

    /** Writes a command and waits for the first matching indication. */
    private suspend fun request(
        gatt: GattConnection,
        command: ByteArray,
        timeoutMs: Long = 10_000,
        predicate: (ByteArray) -> Boolean,
    ): ByteArray? {
        val response = scope.async {
            withTimeoutOrNull(timeoutMs) {
                gatt.notifications
                    .map { it.value }
                    .first { it.isNotEmpty() && predicate(it) }
            }
        }
        gatt.write(DexcomProtocol.AUTHENTICATION, command)
        return response.await()
    }

    /** Requests the current glucose value. */
    suspend fun readGlucose() {
        val gatt = link ?: throw DeviceException(DeviceError.GattFailure(0, "not connected"))
        gatt.write(DexcomProtocol.CONTROL, DexcomProtocol.GLUCOSE_REQUEST)
    }

    private suspend fun dispatch(n: Notification) {
        val frame = n.value
        if (frame.isEmpty()) return
        when (frame[0]) {
            DexcomProtocol.Opcode.GLUCOSE_RX -> {
                val message = GlucoseMessage.parse(frame) ?: return
                if (!message.state.producesGlucose) {
                    Timber.i("sensor not producing glucose: %s", message.state)
                    return
                }
                message.toReading(sessionStart)?.let { _readings.emit(DeviceReading.Glucose(it)) }
            }
            else -> Timber.v("unhandled dexcom frame 0x%02x", frame[0])
        }
    }

    /**
     * The G6 stores recent readings and replays them on request. Not yet
     * implemented: the backfill stream is a separate characteristic with its own
     * chunked framing, which needs a real transmitter to validate.
     */
    override suspend fun backfill(since: Instant): List<DeviceReading> {
        Timber.w("Dexcom backfill not implemented; returning live readings only")
        return emptyList()
    }

    /**
     * Synchronous release; see [DeviceConnection.release].
     *
     * Skips the polite DISCONNECT write that [disconnect] sends: that is a
     * GATT round-trip, and the point of this path is to free the client
     * without waiting. The transmitter drops the link on its own.
     */
    override fun release() {
        link?.close()
        link = null
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun disconnect() {
        runCatching { link?.write(DexcomProtocol.CONTROL, DexcomProtocol.DISCONNECT) }
        link?.close()
        link = null
        _state.value = ConnectionState.Disconnected
    }

    private fun DiscoveredDevice.bluetoothDevice(): BluetoothDevice =
        context.getSystemService(BluetoothManager::class.java).adapter
            .getRemoteDevice(id.address)
}
