package dev.lumora.ble.dexcom

import android.content.Context
import dev.lumora.ble.core.DeviceConnection
import dev.lumora.ble.core.DeviceKind
import dev.lumora.ble.core.DeviceProtocol
import dev.lumora.ble.core.ProtocolContext
import java.time.Instant
import java.util.UUID

/**
 * Install this to support the Dexcom G6.
 *
 * **Opt-in for a reason.** The protocol is reverse-engineered and unofficial;
 * using it violates Dexcom's terms of service. A build that does not install
 * it does not ship the code. The G7/ONE+ is not supported at all — see
 * `SupportMatrix`.
 *
 * Unlike the other protocols this one is a class, not an object: a G6's
 * encryption key is derived entirely from the transmitter serial, so the
 * credential is required before a connection can be built at all.
 *
 * ```
 * install(DexcomProtocolFactory("8UMS7E", sessionStart = Instant.now()))
 * ```
 *
 * @throws DeviceException if [serial] is not 6 alphanumeric characters.
 *   Validated at construction so a typo surfaces while the user is still
 *   holding the applicator, not at connect time.
 */
class DexcomProtocolFactory(
    serial: String,
    private val sessionStart: Instant,
) : DeviceProtocol {

    private val serial: String = DexcomAuth.validateSerial(serial)

    override val kind = DeviceKind.DEXCOM_SENSOR

    override val scanServices: List<UUID> = listOf(DexcomProtocol.SERVICE)

    /** A G6 advertises "Dexcom" plus the last two serial characters. */
    override fun scanNamePrefix(): String = DexcomProtocol.advertisedName(serial)

    override fun create(context: ProtocolContext): DeviceConnection =
        DexcomConnection(
            context.androidContext as Context, context.scope, serial, sessionStart)
}
