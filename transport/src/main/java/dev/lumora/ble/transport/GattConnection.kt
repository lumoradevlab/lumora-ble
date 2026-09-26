package dev.lumora.ble.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import dev.lumora.ble.core.DeviceError
import dev.lumora.ble.core.InternalLumoraApi
import dev.lumora.ble.core.DeviceException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** A characteristic notification: which characteristic, and the bytes it pushed. */
data class Notification(val characteristic: UUID, val value: ByteArray) {
    override fun equals(other: Any?) = other is Notification &&
        characteristic == other.characteristic && value.contentEquals(other.value)
    override fun hashCode() = 31 * characteristic.hashCode() + value.contentHashCode()
}

/**
 * Coroutine wrapper over one [BluetoothGatt] link.
 *
 * Owns the GATT client for its whole lifetime and guarantees `close()` on every
 * exit path — the old code leaked one client per scan result, which exhausts the
 * ~32-interface cap and produces permanent status-133 failures.
 *
 * **Not part of the public API.** This is plumbing shared between the device
 * modules, so it cannot be `internal` — Kotlin scopes that to one compilation
 * module and three separate device modules use it. Encapsulation comes from
 * Gradle instead: every module declares `:transport` with `implementation`, so
 * it resolves at runtime scope and never reaches a consumer's compile
 * classpath. Treat its signature as free to change without a major version.
 */
@InternalLumoraApi
@SuppressLint("MissingPermission")
class GattConnection(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val queue = GattQueue()
    private var gatt: BluetoothGatt? = null
    private var pump: Job? = null
    private val closed = AtomicBoolean(false)

    private val _notifications = MutableSharedFlow<Notification>(
        replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val notifications: SharedFlow<Notification> = _notifications

    private var connected: CompletableDeferred<Unit>? = null
    private var servicesReady: CompletableDeferred<Unit>? = null
    private var negotiatedMtu: Int = 23

    /** Effective payload size: ATT MTU minus the 3-byte opcode+handle header. */
    val maxPayload: Int get() = negotiatedMtu - 3

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        connected?.completeExceptionally(
                            DeviceException(DeviceError.GattFailure(status, "connect")))
                        return
                    }
                    connected?.complete(Unit)
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Timber.i("GATT disconnected (status=%d)", status)
                    val err = DeviceError.GattFailure(status, "disconnected")
                    connected?.completeExceptionally(DeviceException(err))
                    servicesReady?.completeExceptionally(DeviceException(err))
                    queue.cancelAll(err)
                    close()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) servicesReady?.complete(Unit)
            else servicesReady?.completeExceptionally(
                DeviceException(DeviceError.GattFailure(status, "discoverServices")))
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = mtu
            finish(status, byteArrayOf(), "requestMtu")
        }

        // API < 33
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            finish(status, c.value ?: byteArrayOf(), "read")
        }

        // API >= 33
        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) = finish(status, value, "read")

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            finish(status, byteArrayOf(), "write")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            finish(status, byteArrayOf(), "subscribe")
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            emit(c.uuid, c.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray,
        ) = emit(c.uuid, value)

        private fun finish(status: Int, value: ByteArray, op: String) {
            if (status == BluetoothGatt.GATT_SUCCESS) queue.complete(value)
            else queue.fail(DeviceError.GattFailure(status, op))
        }

        private fun emit(uuid: UUID, value: ByteArray) {
            // tryEmit can drop under burst; extraBufferCapacity of 256 makes that
            // unlikely, and dropping a sample beats blocking the binder thread.
            if (!_notifications.tryEmit(Notification(uuid, value))) {
                Timber.w("dropped notification from %s", uuid)
            }
        }
    }

    /** Connects and waits for service discovery. Throws [DeviceException] on failure. */
    suspend fun connect(device: BluetoothDevice) {
        check(gatt == null) { "GattConnection is single-use; create a new one per link" }
        connected = CompletableDeferred()
        servicesReady = CompletableDeferred()

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, callback)
        }

        connected!!.await()
        servicesReady!!.await()
        startPump()
    }

    /** Single consumer: pulls one op at a time so only one is ever outstanding. */
    private fun startPump() {
        pump = scope.launch {
            while (true) {
                val op = queue.next()
                val g = gatt
                if (g == null) {
                    queue.fail(DeviceError.GattFailure(0, "no gatt"))
                    continue
                }
                if (!op.execute(g) { uuid -> findCharacteristic(uuid) }) {
                    queue.fail(DeviceError.GattFailure(0, "could not issue ${op::class.simpleName}"))
                }
            }
        }
    }

    private fun findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.services?.firstNotNullOfOrNull { it.getCharacteristic(uuid) }

    suspend fun requestMtu(mtu: Int): Int {
        queue.submit(GattOp.RequestMtu(mtu, CompletableDeferred()))
        return negotiatedMtu
    }

    suspend fun read(characteristic: UUID): ByteArray =
        queue.submit(GattOp.Read(characteristic, CompletableDeferred()))

    suspend fun write(
        characteristic: UUID,
        payload: ByteArray,
        withResponse: Boolean = true,
    ): ByteArray = queue.submit(
        GattOp.Write(
            characteristic, payload,
            if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            CompletableDeferred(),
        )
    )

    suspend fun subscribe(characteristic: UUID, indication: Boolean = false) {
        queue.submit(GattOp.Subscribe(characteristic, indication, CompletableDeferred()))
    }

    fun hasService(uuid: UUID): Boolean = gatt?.getService(uuid) != null

    /**
     * Every service UUID the peripheral actually exposes.
     *
     * A device can advertise a service UUID it does not serve — advertisement
     * data and the GATT table are independent — so this reports what discovery
     * really found, which is what the protocol layer branches on.
     */
    fun discoveredServices(): List<UUID> =
        gatt?.services?.map { it.uuid }.orEmpty()

    /**
     * Starts Android-level bonding if the device is not already bonded.
     *
     * Some peripherals (the Dexcom G6 among them) refuse to serve data until
     * bonded. This may show a system pairing prompt, so call it only when the
     * protocol actually asks for it. Returns true if bonding is done or underway.
     */
    fun bond(): Boolean {
        val device = gatt?.device ?: return false
        return when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> true
            BluetoothDevice.BOND_BONDING -> true
            else -> device.createBond()
        }
    }

    /** Idempotent. Safe to call from any thread and from the disconnect callback. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        pump?.cancel()
        queue.cancelAll(DeviceError.GattFailure(0, "connection closed"))
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }
}
