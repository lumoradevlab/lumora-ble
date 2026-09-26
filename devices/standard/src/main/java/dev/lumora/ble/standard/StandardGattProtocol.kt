package dev.lumora.ble.standard

import android.content.Context
import dev.lumora.ble.core.DeviceConnection
import dev.lumora.ble.core.DeviceKind
import dev.lumora.ble.core.DeviceProtocol
import dev.lumora.ble.core.ProtocolContext
import java.util.UUID

/**
 * Install this to support standard SIG heart rate peripherals.
 *
 * The one protocol in the SDK with no vendor terms attached: it implements
 * published Bluetooth SIG profiles, so installing it carries none of the
 * ToS exposure the vendor modules do.
 *
 * ```
 * val sdk = LumoraBle.create(context) {
 *     install(StandardGattProtocol)
 * }
 * ```
 */
object StandardGattProtocol : DeviceProtocol {

    override val kind = DeviceKind.HEART_RATE_MONITOR

    override val scanServices: List<UUID> = StandardGattProfiles.SCANNABLE_SERVICES

    override fun create(context: ProtocolContext): DeviceConnection =
        StandardGattConnection(context.androidContext as Context, context.scope)
}
