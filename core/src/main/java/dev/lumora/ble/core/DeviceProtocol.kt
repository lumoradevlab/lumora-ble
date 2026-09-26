package dev.lumora.ble.core

import java.util.UUID

/**
 * A protocol a build can speak, contributed by a device module.
 *
 * This is what makes vendor protocols opt-in. Every protocol here is
 * unofficial and using one violates that vendor's terms of service, so the
 * SDK must not force a consumer who wants standard SIG heart rate to also ship
 * the Dexcom and Abbott code. An integrator depends on the modules they want
 * and installs them explicitly; a protocol whose module is absent is a compile
 * error rather than a runtime surprise.
 *
 * Implementations are objects in the device modules. [create] is called once
 * per device, so an implementation must not hold per-connection state.
 */
interface DeviceProtocol {

    /** The kind this protocol serves. One protocol per kind per build. */
    val kind: DeviceKind

    /**
     * Service UUIDs to filter a BLE scan on, or empty for a protocol that is
     * not discovered by scanning — Libre is tapped over NFC, not scanned.
     */
    val scanServices: List<UUID> get() = emptyList()

    /**
     * Advertised-name prefix to narrow a scan, when the protocol can derive
     * one. A Dexcom G6 advertises "Dexcom" plus the last two serial characters.
     */
    fun scanNamePrefix(): String? = null

    /**
     * Builds a connection for one device.
     *
     * @throws DeviceException if a credential this protocol needs has not been
     *   supplied — a G6 cannot derive its key without the transmitter serial.
     */
    fun create(context: ProtocolContext): DeviceConnection
}

/**
 * What a protocol needs from the SDK to build a connection.
 *
 * Deliberately narrow, and deliberately untyped in [androidContext]: `core`
 * stays free of Android imports so its domain types remain JVM-testable.
 * Implementations cast it, which is safe because only the Android SDK
 * constructs one.
 */
interface ProtocolContext {
    /** The application `android.content.Context`. */
    val androidContext: Any

    /** Scope owned by the SDK; cancelled when the SDK is closed. */
    val scope: kotlinx.coroutines.CoroutineScope

    val credentials: CredentialStore
}

/**
 * A protocol reached by tapping an NFC tag rather than connecting over BLE.
 *
 * Implemented alongside [DeviceProtocol] by protocols that are tapped — Libre
 * 1/2 are the only ones today. Kept separate so the SDK can route a tag to
 * whichever protocol handles it without importing that device module.
 */
interface NfcTagReader {
    /**
     * Decodes one tag.
     *
     * @throws DeviceException if the tag is not this protocol's sensor, or the
     *   read fails part-way — a partial transfer is never returned as data.
     */
    suspend fun readTag(tag: Any): List<DeviceReading>
}

/**
 * Collects the protocols a build installs.
 *
 * Passed to the `LumoraBle.create` builder. Installing the same kind twice
 * fails loudly rather than silently picking one.
 */
class ProtocolRegistry {

    private val protocols = LinkedHashMap<DeviceKind, DeviceProtocol>()

    fun install(protocol: DeviceProtocol) {
        val existing = protocols.put(protocol.kind, protocol)
        require(existing == null) {
            "Two protocols installed for ${protocol.kind}: " +
                "${existing!!::class.java.name} and ${protocol::class.java.name}"
        }
    }

    operator fun get(kind: DeviceKind): DeviceProtocol? = protocols[kind]

    val installed: Set<DeviceKind> get() = protocols.keys.toSet()

    fun isEmpty(): Boolean = protocols.isEmpty()
}
