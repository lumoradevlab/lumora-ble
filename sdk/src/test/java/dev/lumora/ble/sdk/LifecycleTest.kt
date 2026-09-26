package dev.lumora.ble.sdk

import dev.lumora.ble.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Lifecycle guarantees a third-party integrator depends on.
 *
 * These matter more than they look: an SDK whose collectors outlive their
 * connection degrades the host app slowly, and the symptom appears far from
 * the cause.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleTest {

    /** Counts active collectors so a leak is observable. */
    private class CountingConnection(override val kind: DeviceKind) : DeviceConnection {
        val stateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val readingFlow = MutableSharedFlow<DeviceReading>(extraBufferCapacity = 8)
        var collectors = 0
            private set

        override val state: Flow<ConnectionState> =
            stateFlow.onSubscription { collectors++ }.onCompletion { collectors-- }
        override val readings: Flow<DeviceReading> =
            readingFlow.onSubscription { collectors++ }.onCompletion { collectors-- }

        var disconnected = false
            private set

        override suspend fun connect(device: DiscoveredDevice) {
            stateFlow.value = ConnectionState.Ready(device)
        }

        override suspend fun disconnect() {
            disconnected = true
            stateFlow.value = ConnectionState.Disconnected
        }

        var released = false
            private set

        override fun release() {
            released = true
            disconnected = true
            stateFlow.value = ConnectionState.Disconnected
        }

        override suspend fun backfill(since: Instant) = emptyList<DeviceReading>()
    }

    private val device = DiscoveredDevice(
        id = DeviceId("AA:BB:CC:DD:EE:FF", DeviceKind.HEART_RATE_MONITOR),
        name = "strap",
        rssi = -50,
    )

    @Test
    fun `disconnect stops the collectors it started`() = runTest {
        val connection = CountingConnection(DeviceKind.HEART_RATE_MONITOR)
        val sdk = harness(connection)

        sdk.connect(device)
        advanceUntilIdle()
        assertEquals("both mirrors should be collecting", 2, connection.collectors)

        sdk.disconnect(device.id)
        advanceUntilIdle()

        // The leak this guards: without cancelling the mirror jobs, a
        // connect/disconnect loop accumulates two coroutines per cycle.
        assertEquals("collectors must stop on disconnect", 0, connection.collectors)
        assertTrue(connection.disconnected)
    }

    @Test
    fun `reconnect cycles do not accumulate collectors`() = runTest {
        val connection = CountingConnection(DeviceKind.HEART_RATE_MONITOR)
        val sdk = harness(connection)

        repeat(5) {
            sdk.connect(device)
            advanceUntilIdle()
            sdk.disconnect(device.id)
            advanceUntilIdle()
        }

        assertEquals("five cycles must leave nothing behind", 0, connection.collectors)
    }

    @Test
    fun `use closes the SDK even when the block throws`() = runTest {
        val connection = CountingConnection(DeviceKind.HEART_RATE_MONITOR)
        val sdk = harness(connection)

        // kotlin.io.use works because LumoraBle is Closeable — no extension
        // of our own is needed, and writing one would shadow the stdlib's
        // try/finally semantics for no gain.
        val error = runCatching {
            sdk.use {
                it.connect(device)
                advanceUntilIdle()
                error("one-off task failed")
            }
        }.exceptionOrNull()

        assertEquals("one-off task failed", error?.message)
        // The point of the idiom: teardown happened despite the throw, so a
        // failed one-off task cannot leak a GATT client.
        assertTrue("use must close on the exception path", connection.released)
    }

    @Test
    fun `use closes before a launched job inside it has run`() = runTest {
        val connection = CountingConnection(DeviceKind.HEART_RATE_MONITOR)
        val sdk = harness(connection)

        // The trap `use` sets with coroutines: the block returns as soon as
        // it launches, so close() runs while the launched work is still
        // pending. Documented rather than guarded against — `use` suits a
        // one-off task that awaits its own work, not fire-and-forget.
        sdk.use {
            it.connect(device)
            // deliberately not awaited
        }

        assertTrue("close ran at the end of the block", connection.released)
    }

    @Test
    fun `close releases synchronously, before any coroutine runs`() = runTest {
        val connection = CountingConnection(DeviceKind.HEART_RATE_MONITOR)
        val sdk = harness(connection)

        sdk.connect(device)
        advanceUntilIdle()

        sdk.close()

        // Asserted BEFORE advanceUntilIdle: the GATT client must be released
        // by the time close() returns. If this needed a coroutine to run, a
        // caller closing from onCleared() could be destroyed first and leak
        // the client for the life of the process.
        assertTrue("release must happen synchronously", connection.released)

        // Cancellation itself is cooperative: Job.cancel() returns at once but
        // the collector's onCompletion runs when it next resumes. That is fine
        // — the native GATT client is already freed above, and a coroutine
        // that never resumes holds nothing the OS cares about.
        advanceUntilIdle()
        assertEquals("collectors must stop", 0, connection.collectors)

        // A second close must not throw — callers tear down from several paths.
        sdk.close()
    }

    private fun kotlinx.coroutines.test.TestScope.harness(
        connection: DeviceConnection,
    ): LumoraBle = TestableLumoraBle(
        connection,
        CoroutineScope(StandardTestDispatcher(testScheduler)),
    )
}

/**
 * Exercises the real mirror/cancel logic against an injected connection.
 *
 * Mirrors [LumoraBleImpl]'s lifecycle handling rather than wrapping it, because
 * that class needs an Android Context and a live BLE adapter.
 */
private class TestableLumoraBle(
    private val connection: DeviceConnection,
    private val scope: CoroutineScope,
) : LumoraBle {

    private val active = mutableMapOf<DeviceId, DeviceConnection>()
    private val mirrors = mutableMapOf<DeviceId, List<kotlinx.coroutines.Job>>()
    private var closed = false

    private val _connections = MutableStateFlow<Map<DeviceId, ConnectionState>>(emptyMap())
    override val connections: Flow<Map<DeviceId, ConnectionState>> = _connections

    private val _readings = MutableSharedFlow<DeviceReading>(extraBufferCapacity = 64)
    override val readings: Flow<DeviceReading> = _readings

    override val supportedDevices = SupportMatrix.all

    override fun scan(kind: DeviceKind): Flow<DiscoveredDevice> = emptyFlow()

    override suspend fun connect(device: DiscoveredDevice) {
        active[device.id] = connection
        mirrors[device.id] = listOf(
            connection.state
                .onEach { st -> _connections.update { it + (device.id to st) } }
                .launchIn(scope),
            connection.readings.onEach { _readings.emit(it) }.launchIn(scope),
        )
        connection.connect(device)
    }

    override suspend fun disconnect(id: DeviceId) {
        active.remove(id)?.disconnect()
        mirrors.remove(id)?.forEach { it.cancel() }
        _connections.update { it - id }
    }

    override suspend fun disconnectAll() {
        active.keys.toList().forEach { runCatching { disconnect(it) } }
    }

    override fun close() {
        if (closed) return
        closed = true
        // Mirrors LumoraBleImpl: release synchronously, then cancel.
        active.values.forEach { runCatching { it.release() } }
        active.clear()
        mirrors.values.forEach { jobs -> jobs.forEach { it.cancel() } }
        mirrors.clear()
    }

    override suspend fun backfill(id: DeviceId, since: Instant) = emptyList<DeviceReading>()
    override suspend fun readLibreTag(tag: Any) = emptyList<DeviceReading>()
    override fun requestPermissions() = true
    override val requiredPermissions: Array<String> = emptyArray()
}
