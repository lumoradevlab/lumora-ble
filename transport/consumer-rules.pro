# Consumer rules for dev.lumora.ble:transport

# Host apps call BlePermissions directly to prompt for runtime permissions.
-keep class dev.lumora.ble.transport.BlePermissions { *; }
