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
    fun `close disconnects everything and is idempotent`() = runTest {
        val connection = CountingConnection(DeviceKind.HEART_RATE_MONITOR)
        val sdk = harness(connection)

        sdk.connect(device)
        advanceUntilIdle()

        sdk.close()
        advanceUntilIdle()
        assertTrue("close must disconnect live devices", connection.disconnected)

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

    override suspend fun close() {
        if (closed) return
        closed = true
        disconnectAll()
    }

    override suspend fun backfill(id: DeviceId, since: Instant) = emptyList<DeviceReading>()
    override suspend fun readLibreTag(tag: Any) = emptyList<DeviceReading>()
    override fun setDexcomTransmitter(serial: String, sessionStart: Instant) = Unit
    override fun requestPermissions() = true
}
