package dev.lumora.ble.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import dev.lumora.ble.core.DeviceError
import dev.lumora.ble.core.DeviceException
import dev.lumora.ble.core.InternalLumoraApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/**
 * Scans for peripherals advertising [serviceUuids].
 *
 * The returned flow stops the scan when cancelled — the old implementation never
 * stopped scanning and spawned a fresh GATT client per scan result, which both
 * drained battery and leaked connections.
 *
 * **Not public API.** Consumers scan through [dev.lumora.ble.core.LumoraBle.scan],
 * which applies the installed protocol's service filter and de-duplicates
 * results. See [InternalLumoraApi].
 */
@SuppressLint("MissingPermission")
@InternalLumoraApi
class BleScanner(private val context: Context) {

    fun scan(
        serviceUuids: List<UUID>,
        namePrefix: String? = null,
        lowLatency: Boolean = true,
    ): Flow<ScanResult> = callbackFlow {
        val manager = context.getSystemService(BluetoothManager::class.java)
            ?: throw DeviceException(DeviceError.BluetoothUnavailable("no BluetoothManager"))
        val adapter: BluetoothAdapter = manager.adapter
            ?: throw DeviceException(DeviceError.BluetoothUnavailable("no adapter"))
        if (!adapter.isEnabled) {
            // Never call adapter.enable() — removed in API 33 and a hostile UX
            // besides. The caller must prompt the user.
            throw DeviceException(DeviceError.BluetoothUnavailable("bluetooth is off"))
        }
        val scanner = adapter.bluetoothLeScanner
            ?: throw DeviceException(DeviceError.BluetoothUnavailable("no LE scanner"))

        val filters = buildList {
            serviceUuids.forEach {
                add(ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build())
            }
            // Libre advertises without its service UUID, so fall back to a name prefix.
            if (serviceUuids.isEmpty() && namePrefix != null) {
                add(ScanFilter.Builder().setDeviceName(namePrefix).build())
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(
                if (lowLatency) ScanSettings.SCAN_MODE_LOW_LATENCY
                else ScanSettings.SCAN_MODE_BALANCED
            )
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device?.name ?: result.scanRecord?.deviceName
                if (namePrefix != null && name?.startsWith(namePrefix) != true) return
                trySend(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(DeviceException(DeviceError.GattFailure(errorCode, "scan")))
            }
        }

        scanner.startScan(filters, settings, cb)
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }
}
