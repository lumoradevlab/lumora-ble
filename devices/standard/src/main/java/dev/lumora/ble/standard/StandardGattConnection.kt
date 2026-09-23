package dev.lumora.ble.standard

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import dev.lumora.ble.core.*
import dev.lumora.ble.transport.GattConnection
import dev.lumora.ble.transport.Notification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant

/**
 * A connection to any peripheral implementing the SIG standard GATT profiles.
 *
 * This is the module with no proprietary protocol in it. There is no
 * authentication step, no credential to provision, and no vendor handshake —
 * connect, discover, subscribe, parse. Consequently there is also no
 * [CredentialStore] dependency, unlike the other three connections.
 *
 * Covers Fitbit Charge 6 / Fitbit Air / Pixel Watch 2+ in heart-rate broadcast
 * mode, plus Polar, Wahoo, Garmin and generic chest straps and oximeters.
 *
 * **Broadcast is user-initiated and session-scoped.** The Fitbit and Pixel
 * Watch devices only advertise 0x180D while the user has explicitly started
 * sharing (Quick Settings → Connected Fitness → Connect). They are not
 * discoverable the rest of the time, which is why this kind is REQUIRES_SETUP
 * rather than SUPPORTED, and why [connect] surfaces a PairingRequired error
 * naming the on-watch steps instead of a bare GATT failure.
 */
class StandardGattConnection(
    private val context: Context,
    private val scope: CoroutineScope,
) : DeviceConnection {

    override val kind = DeviceKind.HEART_RATE_MONITOR

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _readings = MutableSharedFlow<DeviceReading>(extraBufferCapacity = 128)
    override val readings: SharedFlow<DeviceReading> = _readings.asSharedFlow()

    private var link: GattConnection? = null

    /** Profiles this particular peripheral turned out to expose. */
    var discoveredProfiles: Set<UUIDProfile> = emptySet()
        private set

    enum class UUIDProfile { HEART_RATE, BATTERY, TEMPERATURE, PULSE_OXIMETRY }

    override suspend fun connect(device: DiscoveredDevice) {
        _state.value = ConnectionState.Connecting

        val gatt = GattConnection(context, scope)
        link = gatt
        try {
            gatt.connect(device.bluetoothDevice())

            // No auth step: the link is usable the moment services resolve.
            val profiles = mutableSetOf<UUIDProfile>()

            if (gatt.hasService(StandardGattProfiles.HEART_RATE_SERVICE)) {
                gatt.subscribe(StandardGattProfiles.HEART_RATE_MEASUREMENT)
                profiles += UUIDProfile.HEART_RATE
            }
            if (gatt.hasService(StandardGattProfiles.PULSE_OXIMETER_SERVICE)) {
                gatt.subscribe(StandardGattProfiles.PLX_CONTINUOUS_MEASUREMENT)
                profiles += UUIDProfile.PULSE_OXIMETRY
            }
            if (gatt.hasService(StandardGattProfiles.HEALTH_THERMOMETER_SERVICE)) {
                // Thermometers indicate rather than notify.
                gatt.subscribe(StandardGattProfiles.TEMPERATURE_MEASUREMENT, indication = true)
                profiles += UUIDProfile.TEMPERATURE
            }
            if (gatt.hasService(StandardGattProfiles.BATTERY_SERVICE)) {
                profiles += UUIDProfile.BATTERY
                // Battery Level is readable always, notifiable only sometimes;
                // read once so the app has a value even on a silent device.
                runCatching {
                    val level = gatt.read(StandardGattProfiles.BATTERY_LEVEL)
                    StandardGattParsers.parseBattery(level)?.let {
                        _readings.emit(DeviceReading.Battery(it, Instant.now()))
                    }
                    gatt.subscribe(StandardGattProfiles.BATTERY_LEVEL)
                }.onFailure { Timber.d("battery not notifiable: %s", it.message) }
            }

            if (profiles.isEmpty()) {
                throw DeviceException(DeviceError.PairingRequired(
                    "This device exposes no standard measurement profile. On a Fitbit " +
                        "or Pixel Watch, start sharing first: swipe down → Connected " +
                        "Fitness → Connect. On a Pixel Watch you may also need to " +
                        "enable Extended Pairing."))
            }
            discoveredProfiles = profiles

            scope.launch { gatt.notifications.collect(::dispatch) }
            _state.value = ConnectionState.Ready(device)
            Timber.i("standard GATT ready, profiles=%s", profiles)
        } catch (e: Throwable) {
            gatt.close()
            link = null
            _state.value = ConnectionState.Failed(
                (e as? DeviceException)?.error
                    ?: DeviceError.ProtocolViolation(e.message ?: "connect failed"))
            throw e
        }
    }

    private suspend fun dispatch(n: Notification) {
        val now = Instant.now()
        when (n.characteristic) {
            StandardGattProfiles.HEART_RATE_MEASUREMENT -> {
                // A peripheral that reports contact explicitly and says it has
                // none is producing noise, not a measurement.
                if (StandardGattParsers.sensorContact(n.value) == false) {
                    Timber.v("dropping HR sample: no sensor contact")
                    return
                }
                StandardGattParsers.parseHeartRate(n.value, now)
                    ?.let { _readings.emit(DeviceReading.HeartRate(it)) }
            }
            StandardGattProfiles.BATTERY_LEVEL ->
                StandardGattParsers.parseBattery(n.value)
                    ?.let { _readings.emit(DeviceReading.Battery(it, now)) }
            StandardGattProfiles.TEMPERATURE_MEASUREMENT ->
                StandardGattParsers.parseTemperature(n.value)
                    ?.let { _readings.emit(DeviceReading.Temperature(it, now)) }
            StandardGattProfiles.PLX_CONTINUOUS_MEASUREMENT ->
                StandardGattParsers.parseSpO2(n.value)
                    ?.let { _readings.emit(DeviceReading.SpO2(it, now)) }
            else -> Timber.v("unhandled characteristic %s", n.characteristic)
        }
    }

    /**
     * Always empty: the standard profiles are live-streaming only and define no
     * stored-history characteristic. Returning an empty list rather than
     * throwing lets a caller poll every device uniformly.
     */
    override suspend fun backfill(since: Instant): List<DeviceReading> {
        Timber.d("standard GATT profiles carry no stored history")
        return emptyList()
    }

    override suspend fun disconnect() {
        link?.close()
        link = null
        discoveredProfiles = emptySet()
        _state.value = ConnectionState.Disconnected
    }

    private fun DiscoveredDevice.bluetoothDevice(): BluetoothDevice =
        context.getSystemService(BluetoothManager::class.java).adapter
            .getRemoteDevice(id.address)
}
