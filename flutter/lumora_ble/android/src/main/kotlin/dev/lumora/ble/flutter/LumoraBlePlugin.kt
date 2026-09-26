package dev.lumora.ble.flutter

import dev.lumora.ble.core.*
// LumoraBle.create is an extension on the companion, declared in the sdk
// module rather than core, so the wildcard import above does not cover it.
import dev.lumora.ble.sdk.create
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Flutter binding over [LumoraBle].
 *
 * Contains no protocol logic — it only marshals the native API onto platform
 * channels, so Flutter and native consumers share one implementation and one
 * set of bugs.
 *
 * All enums cross the channel by NAME, never ordinal, so adding a native case
 * cannot silently change the meaning of an existing one on the Dart side.
 */
class LumoraBlePlugin : FlutterPlugin {

    private lateinit var methods: MethodChannel
    private lateinit var readingEvents: EventChannel
    private lateinit var connectionEvents: EventChannel
    private lateinit var scanEvents: EventChannel

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var sdk: LumoraBle

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        sdk = LumoraBle.create(binding.applicationContext)

        methods = MethodChannel(binding.binaryMessenger, "dev.lumora.ble/methods")
        methods.setMethodCallHandler(::onMethodCall)

        readingEvents = EventChannel(binding.binaryMessenger, "dev.lumora.ble/readings")
        readingEvents.setStreamHandler(
            FlowStreamHandler(scope) { sdk.readings.onEach { it } }
        )

        connectionEvents = EventChannel(binding.binaryMessenger, "dev.lumora.ble/connections")
        connectionEvents.setStreamHandler(
            FlowStreamHandler(scope) { sdk.connections }
        )

        scanEvents = EventChannel(binding.binaryMessenger, "dev.lumora.ble/scan")
        scanEvents.setStreamHandler(ScanStreamHandler(scope, sdk))
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methods.setMethodCallHandler(null)
        readingEvents.setStreamHandler(null)
        connectionEvents.setStreamHandler(null)
        scanEvents.setStreamHandler(null)
        scope.launch { sdk.disconnectAll() }
        scope.cancel()
    }

    private fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        scope.launch {
            try {
                val value: Any? = when (call.method) {
                    "supportedDevices" -> sdk.supportedDevices.map { it.toMap() }

                    "requestPermissions" -> sdk.requestPermissions()

                    "setDexcomTransmitter" -> {
                        sdk.setDexcomTransmitter(
                            call.argument<String>("serial")!!,
                            Instant.ofEpochMilli(call.argument<Number>("sessionStart")!!.toLong()),
                        )
                        null
                    }

                    // NFC is a tap, not a connection: the reader is driven by the
                    // host app's foreground-dispatch tag callback, so the plugin
                    // surfaces a clear error rather than pretending to scan.
                    "readLibreSensor" -> throw DeviceException(
                        DeviceError.ProtocolViolation(
                            "Libre NFC reading requires an Activity-bound tag callback. " +
                                "Wire LibreNfcReader to your NFC foreground dispatch and " +
                                "feed results in natively."))

                    "connect" -> {
                        sdk.connect(call.discoveredDevice())
                        null
                    }

                    "disconnect" -> {
                        sdk.disconnect(call.deviceId())
                        null
                    }

                    "disconnectAll" -> {
                        sdk.disconnectAll()
                        null
                    }

                    "backfill" -> {
                        val since = call.argument<Number>("since")!!.toLong()
                        sdk.backfill(call.deviceId(), since).map { it.toMap() }
                    }

                    else -> return@launch result.notImplemented()
                }
                result.success(value)
            } catch (e: DeviceException) {
                result.error(e.error.code(), e.error.message(), null)
            } catch (e: Throwable) {
                result.error("Unknown", e.message ?: e::class.simpleName, null)
            }
        }
    }

    private fun MethodCall.deviceId() = DeviceId(
        address = argument<String>("address")!!,
        kind = DeviceKind.valueOf(argument<String>("kind")!!.toScreamingSnake()),
    )

    private fun MethodCall.discoveredDevice() = DiscoveredDevice(
        id = deviceId(),
        name = argument<String>("name"),
        rssi = argument<Number>("rssi")?.toInt() ?: 0,
        serial = argument<String>("serial"),
    )
}

/** Bridges a Kotlin [kotlinx.coroutines.flow.Flow] onto an [EventChannel]. */
private class FlowStreamHandler<T>(
    private val scope: CoroutineScope,
    private val source: () -> kotlinx.coroutines.flow.Flow<T>,
) : EventChannel.StreamHandler {

    private var job: Job? = null

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        job = source()
            .onEach { events.success(encode(it)) }
            .launchIn(scope)
    }

    override fun onCancel(arguments: Any?) {
        job?.cancel()
        job = null
    }

    private fun encode(value: T): Any? = when (value) {
        is DeviceReading -> value.toMap()
        is Map<*, *> -> value.entries.associate { (k, v) ->
            (k as DeviceId).toMap() to (v as ConnectionState).toMap()
        }
        else -> value
    }
}

/** Scanning is per-subscription: the scan stops when Dart cancels. */
private class ScanStreamHandler(
    private val scope: CoroutineScope,
    private val sdk: LumoraBle,
) : EventChannel.StreamHandler {

    private var job: Job? = null

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        val kindName = (arguments as? Map<*, *>)?.get("kind") as? String
        val kind = runCatching { DeviceKind.valueOf(kindName!!.toScreamingSnake()) }
            .getOrElse {
                events.error("BadArgument", "unknown device kind: $kindName", null)
                return
            }

        job = sdk.scan(kind)
            .onEach { events.success(it.toMap()) }
            .launchIn(scope)
    }

    override fun onCancel(arguments: Any?) {
        job?.cancel()
        job = null
    }
}

// --- Wire encoding -----------------------------------------------------------
// Enum names cross as SCREAMING_SNAKE_CASE; Dart normalizes by stripping
// underscores and lowercasing, so both spellings stay compatible.

private fun String.toScreamingSnake(): String =
    replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()

internal fun DeviceId.toMap(): Map<String, Any?> =
    mapOf("address" to address, "kind" to kind.name)

internal fun DiscoveredDevice.toMap(): Map<String, Any?> =
    mapOf("id" to id.toMap(), "name" to name, "rssi" to rssi, "serial" to serial)

internal fun DeviceSupport.toMap(): Map<String, Any?> = mapOf(
    "kind" to kind.name,
    "status" to status.name,
    "prerequisite" to prerequisite,
    "limitation" to limitation,
    "transport" to transport.name,
)

internal fun ConnectionState.toMap(): Map<String, Any?> = when (this) {
    is ConnectionState.Disconnected -> mapOf("status" to "DISCONNECTED")
    is ConnectionState.Scanning -> mapOf("status" to "SCANNING")
    is ConnectionState.Connecting -> mapOf("status" to "CONNECTING")
    is ConnectionState.Authenticating -> mapOf("status" to "AUTHENTICATING")
    is ConnectionState.Ready -> mapOf("status" to "READY", "device" to device.toMap())
    is ConnectionState.Failed -> mapOf(
        "status" to "FAILED",
        "error" to mapOf("code" to reason.code(), "message" to reason.message()),
    )
}

internal fun DeviceError.code(): String = when (this) {
    is DeviceError.BluetoothUnavailable -> "BluetoothUnavailable"
    is DeviceError.ScanTimeout -> "ScanTimeout"
    is DeviceError.GattFailure -> "GattFailure"
    is DeviceError.AuthRejected -> "AuthRejected"
    is DeviceError.PairingRequired -> "PairingRequired"
    is DeviceError.ProtocolViolation -> "ProtocolViolation"
    is DeviceError.OperationTimeout -> "OperationTimeout"
}

internal fun DeviceError.message(): String = when (this) {
    is DeviceError.BluetoothUnavailable -> detail
    is DeviceError.ScanTimeout -> "scan timed out for $kind"
    is DeviceError.GattFailure -> "GATT $operation failed with status $status"
    is DeviceError.AuthRejected -> detail
    is DeviceError.PairingRequired -> instruction
    is DeviceError.ProtocolViolation -> detail
    is DeviceError.OperationTimeout -> "$operation timed out"
}

internal fun DeviceReading.toMap(): Map<String, Any?> {
    val base = mapOf("timestamp" to timestamp.toEpochMilli())
    return base + when (this) {
        is DeviceReading.Glucose -> mapOf(
            "type" to "glucose",
            "mgdl" to reading.mgdl,
            "trend" to reading.trend.name,
            "rateOfChange" to reading.rateOfChange,
            "source" to reading.source.name,
            "isHistorical" to reading.isHistorical,
        )
        is DeviceReading.HeartRate -> mapOf(
            "type" to "heartRate",
            "bpm" to sample.bpm,
            "ibiMs" to sample.ibiMs,
        )
        is DeviceReading.Battery -> mapOf(
            "type" to "battery",
            "percent" to level.percent,
            "charging" to level.charging,
        )
        is DeviceReading.Temperature -> mapOf("type" to "temperature", "celsius" to celsius)
        is DeviceReading.SpO2 -> mapOf("type" to "spo2", "percent" to percent)
        is DeviceReading.HeartRateVariability ->
            mapOf("type" to "hrv", "sdnnMs" to sdnnMs)
    }
}
