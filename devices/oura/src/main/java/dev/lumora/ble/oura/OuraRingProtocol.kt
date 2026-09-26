package dev.lumora.ble.oura

import android.content.Context
import dev.lumora.ble.core.DeviceConnection
import dev.lumora.ble.core.DeviceKind
import dev.lumora.ble.core.DeviceProtocol
import dev.lumora.ble.core.ProtocolContext
import java.util.UUID

/**
 * Install this to support the Oura Ring.
 *
 * **Opt-in for a reason.** The protocol is reverse-engineered and unofficial;
 * using it violates Ōura Health's terms of service, and installing it means
 * accepting that on your users' behalf. A build that does not install it does
 * not ship the code.
 *
 * Also requires a factory-reset ring — see `SupportMatrix`.
 */
object OuraRingProtocol : DeviceProtocol {

    override val kind = DeviceKind.OURA_RING

    override val scanServices: List<UUID> = listOf(OuraProtocol.SERVICE)

    override fun create(context: ProtocolContext): DeviceConnection =
        OuraConnection(context.androidContext as Context, context.scope, context.credentials)
}
