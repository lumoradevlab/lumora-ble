package dev.lumora.ble.libre

import dev.lumora.ble.core.DeviceConnection
import dev.lumora.ble.core.DeviceError
import dev.lumora.ble.core.DeviceException
import dev.lumora.ble.core.DeviceKind
import dev.lumora.ble.core.DeviceReading
import dev.lumora.ble.core.NfcTagReader
import dev.lumora.ble.core.DeviceProtocol
import dev.lumora.ble.core.ProtocolContext

/**
 * Install this to support FreeStyle Libre 1/2.
 *
 * **Opt-in for a reason.** The FRAM layout is reverse-engineered and
 * unofficial; using it violates Abbott's terms of service, and the readings
 * are uncalibrated. A build that does not install it does not ship the code.
 *
 * Libre is NFC, not BLE: it contributes no scan services and cannot be
 * connected to. It is reached through `LumoraBle.readLibreTag(tag)`, and
 * installing this is what enables that call.
 */
object LibreProtocol : DeviceProtocol, NfcTagReader {

    override val kind = DeviceKind.LIBRE_SENSOR

    /** Never discovered by a BLE scan — the user taps the sensor. */
    override val scanServices = emptyList<java.util.UUID>()

    override fun create(context: ProtocolContext): DeviceConnection =
        throw DeviceException(DeviceError.ProtocolViolation(
            "Libre sensors are read over NFC, not connected to. " +
                "Call LumoraBle.readLibreTag(tag) with the tag from your " +
                "NFC intent or reader-mode callback."))

    override suspend fun readTag(tag: Any): List<DeviceReading> {
        val nfcTag = tag as? android.nfc.Tag ?: throw DeviceException(
            DeviceError.ProtocolViolation(
                "readLibreTag expects an android.nfc.Tag, got ${tag::class.java.name}"))

        val result = LibreNfcReader().read(nfcTag)
        if (result.isExpired) {
            throw DeviceException(DeviceError.ProtocolViolation(
                "Libre sensor is expired (age ${result.sensorAgeMinutes / 60}h); " +
                    "its readings are no longer trustworthy"))
        }
        return (result.trend + result.history).map { DeviceReading.Glucose(it) }
    }
}
