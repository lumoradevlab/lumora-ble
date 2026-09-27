package dev.lumora.ble.testapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.lumora.ble.core.*
import dev.lumora.ble.sdk.create
import dev.lumora.ble.standard.StandardGattProtocol
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Drives the SDK for the on-device harness.
 *
 * Deliberately thin: it holds no protocol logic, so anything that goes wrong
 * here is a fault in the SDK rather than in the test app.
 */
class ScanViewModel(app: Application) : AndroidViewModel(app) {

    // This harness exercises the standard SIG profile only, so it installs
    // only that protocol — and therefore ships none of the vendor code.
    private val sdk = LumoraBle.create(app) {
        install(StandardGattProtocol)
    }

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** Latest reading per type, so the screen shows current values not a firehose. */
    private val _latest = MutableStateFlow<Map<String, String>>(emptyMap())
    val latest: StateFlow<Map<String, String>> = _latest.asStateFlow()

    val connections: StateFlow<Map<DeviceId, ConnectionState>> =
        sdk.connections.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val supported: List<DeviceSupport> = sdk.supportedDevices

    private var scanJob: Job? = null

    init {
        log("SDK ready. ${supported.count { it.status != SupportStatus.BLOCKED }} usable kinds.")
        viewModelScope.launch {
            sdk.readings.collect { reading ->
                val (label, value) = reading.describe()
                _latest.update { it + (label to value) }
                log("$label: $value")
            }
        }
    }

    fun permissionsGranted(): Boolean = sdk.requestPermissions()

    /** Surfaced from the SDK so the UI needs no transport-module dependency. */
    val requiredPermissions: Array<String> get() = sdk.requiredPermissions

    /**
     * Scans for standard-profile peripherals.
     *
     * Toggling rather than restarting matters: the scan flow stops the radio
     * only when cancelled, so leaving it running is a real battery cost.
     */
    fun toggleScan() {
        if (_scanning.value) {
            scanJob?.cancel()
            scanJob = null
            _scanning.value = false
            log("scan stopped")
            return
        }

        if (!permissionsGranted()) {
            log("BLE permissions not granted — grant them and retry")
            return
        }

        _devices.value = emptyList()
        _scanning.value = true
        log("scanning for standard heart rate profile (0x180D)…")
        log("on a Fitbit/Pixel Watch, start sharing: swipe down → Connected Fitness → Connect")

        scanJob = viewModelScope.launch {
            sdk.scan(DeviceKind.HEART_RATE_MONITOR)
                .catch { e -> log("scan failed: ${e.readable()}") ; _scanning.value = false }
                .collect { device ->
                    _devices.update { current ->
                        if (current.any { it.id == device.id }) current else current + device
                    }
                    log("found ${device.name ?: "(unnamed)"} rssi=${device.rssi}")
                }
        }
    }

    fun connect(device: DiscoveredDevice) {
        viewModelScope.launch {
            // Keeping the scan running while connecting is a classic source of
            // status-133 failures; stop the radio first.
            if (_scanning.value) toggleScan()
            log("connecting to ${device.name ?: device.id.address}…")
            runCatching { sdk.connect(device) }
                .onSuccess { log("connected — waiting for notifications") }
                .onFailure { log("connect failed: ${it.readable()}") }
        }
    }

    fun disconnect(id: DeviceId) {
        viewModelScope.launch {
            runCatching { sdk.disconnect(id) }
                .onSuccess { log("disconnected"); _latest.value = emptyMap() }
                .onFailure { log("disconnect failed: ${it.readable()}") }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Synchronous by design: launching this would race the ViewModel's
        // own scope cancellation, and a lost race leaks a GATT client for the
        // life of the process.
        sdk.close()
    }

    private fun DeviceReading.describe(): Pair<String, String> = when (this) {
        is DeviceReading.HeartRate -> "Heart rate" to buildString {
            append("${sample.bpm} bpm")
            if (sample.ibiMs.isNotEmpty()) append("  RR=${sample.ibiMs.joinToString()}ms")
        }
        is DeviceReading.Battery -> "Battery" to "${level.percent}%"
        is DeviceReading.Temperature -> "Temperature" to "%.2f °C".format(celsius)
        is DeviceReading.SpO2 -> "SpO2" to "%.1f%%".format(percent)
        is DeviceReading.HeartRateVariability -> "HRV (SDNN)" to "%.1f ms".format(sdnnMs)
        is DeviceReading.Sleep -> "Sleep" to "${stage.name} ${durationMinutes}min"
        is DeviceReading.Glucose -> "Glucose" to "${reading.mgdl} mg/dL ${reading.trend.name}"
    }

    /** Surfaces the SDK's closed error set, which is more useful than a message. */
    private fun Throwable.readable(): String = when (val e = this) {
        is DeviceException -> when (val err = e.error) {
            is DeviceError.BluetoothUnavailable -> "bluetooth unavailable — ${err.detail}"
            is DeviceError.GattFailure ->
                "GATT ${err.status} during ${err.operation}" +
                    if (err.status == 133) " (status 133: often a stale connection — toggle Bluetooth)" else ""
            is DeviceError.AuthRejected -> "auth rejected — ${err.detail}"
            is DeviceError.PairingRequired -> err.instruction
            is DeviceError.ProtocolViolation -> "protocol — ${err.detail}"
            is DeviceError.OperationTimeout -> "timed out during ${err.operation}"
            is DeviceError.ScanTimeout -> "scan timed out for ${err.kind}"
        }
        else -> e.message ?: e::class.java.simpleName
    }

    private fun log(line: String) {
        val stamp = TIME.format(Instant.now())
        _events.update { (listOf("$stamp  $line") + it).take(200) }
    }

    private companion object {
        val TIME: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    }
}
