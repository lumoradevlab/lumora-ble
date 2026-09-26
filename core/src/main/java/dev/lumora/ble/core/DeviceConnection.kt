package dev.lumora.ble.core

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * One device, one connection. Each protocol module implements this so the app
 * layer never branches on device type.
 *
 * Implementations must be safe to [connect] again after [disconnect], and must
 * release all GATT resources on disconnect — Android caps an app at ~32 client
 * interfaces and leaking them bricks BLE process-wide until restart.
 */
interface DeviceConnection {
    val kind: DeviceKind
    val state: Flow<ConnectionState>
    /** Hot stream of live readings. Only emits while state is [ConnectionState.Ready]. */
    val readings: Flow<DeviceReading>

    /** Suspends until authenticated or throws [DeviceException]. */
    suspend fun connect(device: DiscoveredDevice)

    suspend fun disconnect()

    /**
     * Releases the GATT client immediately, without suspending.
     *
     * Called from [LumoraBle.close], which cannot suspend: a teardown callback
     * may return before a launched coroutine runs, and a lost race leaks a
     * client interface for the life of the process.
     *
     * Implementations must be idempotent and safe to call from any thread.
     * The default routes to [disconnect] only for implementations with no
     * native resource to release; anything holding a `GattConnection` must
     * override it.
     */
    fun release() = Unit

    /**
     * Pulls stored on-device history. Separate from [readings] because it is a
     * bounded request/response exchange, not a subscription.
     */
    suspend fun backfill(since: Instant): List<DeviceReading>
}

/** Discovers devices of a given [kind]. Scanning stops when the flow is cancelled. */
interface DeviceScanner {
    fun scan(kind: DeviceKind): Flow<DiscoveredDevice>
}

/**
 * Per-device secrets that survive process death. All three protocols need a
 * credential established out-of-band before BLE data flows, so this is the
 * pivot point of the whole library.
 *
 * Implementations MUST be backed by encrypted storage — these are the keys to
 * a user's health data.
 */
interface CredentialStore {
    suspend fun load(id: DeviceId): ByteArray?
    suspend fun save(id: DeviceId, credential: ByteArray)
    suspend fun clear(id: DeviceId)
}
