package dev.lumora.ble.transport

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permissions differ sharply by API level:
 *  - API 31+ : BLUETOOTH_SCAN / BLUETOOTH_CONNECT, no location needed if the
 *              scan is flagged neverForLocation in the manifest.
 *  - API <31 : legacy BLUETOOTH/BLUETOOTH_ADMIN plus ACCESS_FINE_LOCATION,
 *              without which scans silently return zero results.
 */
object BlePermissions {

    fun required(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun missing(context: Context): List<String> = required().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    fun allGranted(context: Context): Boolean = missing(context).isEmpty()
}
