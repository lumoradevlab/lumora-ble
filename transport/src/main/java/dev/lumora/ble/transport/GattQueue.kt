package dev.lumora.ble.transport

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import dev.lumora.ble.core.DeviceError
import dev.lumora.ble.core.DeviceException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** CCCD — writing to this is what actually turns notifications on. */
val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

internal sealed interface GattOp {
    val result: CompletableDeferred<ByteArray>

    data class Read(val char: UUID, override val result: CompletableDeferred<ByteArray>) : GattOp
    data class Write(
        val char: UUID,
        val payload: ByteArray,
        val writeType: Int,
        override val result: CompletableDeferred<ByteArray>,
    ) : GattOp
    data class Subscribe(
        val char: UUID,
        val indication: Boolean,
        override val result: CompletableDeferred<ByteArray>,
    ) : GattOp
    data class RequestMtu(val mtu: Int, override val result: CompletableDeferred<ByteArray>) : GattOp
}

/**
 * Serializes GATT operations.
 *
 * Android's BLE stack permits exactly ONE outstanding operation per connection;
 * issuing a second before the first's callback fires silently drops it. This
 * queue submits one op at a time and waits for its callback before the next.
 *
 * Operations are dispatched from a single consumer coroutine (see [GattConnection]),
 * so no additional locking is needed here.
 */
internal class GattQueue(private val opTimeoutMs: Long = 10_000) {
    private val pending = Channel<GattOp>(Channel.UNLIMITED)
    private var inFlight: GattOp? = null

    suspend fun submit(op: GattOp): ByteArray {
        pending.send(op)
        return try {
            withTimeout(opTimeoutMs) { op.result.await() }
        } catch (e: TimeoutCancellationException) {
            throw DeviceException(DeviceError.OperationTimeout(op::class.simpleName ?: "gatt"))
        }
    }

    /** Called by the consumer loop to pick up the next operation. */
    suspend fun next(): GattOp = pending.receive().also { inFlight = it }

    /** Completes the in-flight operation and frees the slot for the next one. */
    fun complete(value: ByteArray) {
        inFlight?.result?.complete(value)
        inFlight = null
    }

    fun fail(error: DeviceError) {
        inFlight?.result?.completeExceptionally(DeviceException(error))
        inFlight = null
    }

    /** Fails everything still queued — call on disconnect so callers never hang. */
    fun cancelAll(error: DeviceError) {
        fail(error)
        while (true) {
            val op = pending.tryReceive().getOrNull() ?: break
            op.result.completeExceptionally(DeviceException(error))
        }
    }
}

/** Executes an op against the live [BluetoothGatt]. Returns false if it could not be issued. */
@Suppress("DEPRECATION", "MissingPermission")
internal fun GattOp.execute(gatt: BluetoothGatt, resolve: (UUID) -> BluetoothGattCharacteristic?): Boolean {
    return when (this) {
        is GattOp.Read -> resolve(char)?.let { gatt.readCharacteristic(it) } ?: false

        is GattOp.Write -> {
            val c = resolve(char) ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(c, payload, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = writeType
                c.value = payload
                gatt.writeCharacteristic(c)
            }
        }

        is GattOp.Subscribe -> {
            val c = resolve(char) ?: return false
            if (!gatt.setCharacteristicNotification(c, true)) return false
            // setCharacteristicNotification only flips a local flag. The device is
            // not told to send anything until the CCCD is written.
            val cccd = c.getDescriptor(CCCD_UUID) ?: return false
            val value = if (indication) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                cccd.value = value
                gatt.writeDescriptor(cccd)
            }
        }

        is GattOp.RequestMtu -> gatt.requestMtu(mtu)
    }
}
