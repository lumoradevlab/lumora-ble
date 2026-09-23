package dev.lumora.ble.standard

import java.util.UUID

/**
 * Bluetooth SIG standard GATT profiles.
 *
 * Unlike the other three device modules, nothing here is reverse-engineered.
 * These UUIDs and payload layouts are published by the Bluetooth SIG, so any
 * conforming peripheral works without per-vendor code, and a firmware update
 * cannot silently break the parsing.
 *
 * That is what makes this module worth having: one implementation covers
 * Fitbit Charge 6, Fitbit Air, Google Pixel Watch 2+, Polar, Wahoo, Garmin
 * straps, and most generic chest straps and oximeters.
 *
 * All 16-bit SIG UUIDs expand into the base UUID `0000xxxx-0000-1000-8000-
 * 00805f9b34fb`.
 */
object StandardGattProfiles {

    /** Expands a 16-bit SIG identifier into its full 128-bit UUID. */
    fun sig(short: Int): UUID =
        UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(short and 0xFFFF))

    /** Heart Rate service (0x180D) and its mandatory measurement characteristic. */
    val HEART_RATE_SERVICE: UUID = sig(0x180D)
    val HEART_RATE_MEASUREMENT: UUID = sig(0x2A37)
    val BODY_SENSOR_LOCATION: UUID = sig(0x2A38)

    /** Battery service (0x180F). Optional but near-universal. */
    val BATTERY_SERVICE: UUID = sig(0x180F)
    val BATTERY_LEVEL: UUID = sig(0x2A19)

    /** Health Thermometer (0x1809). */
    val HEALTH_THERMOMETER_SERVICE: UUID = sig(0x1809)
    val TEMPERATURE_MEASUREMENT: UUID = sig(0x2A1C)

    /** Pulse Oximeter (0x1822). */
    val PULSE_OXIMETER_SERVICE: UUID = sig(0x1822)
    val PLX_CONTINUOUS_MEASUREMENT: UUID = sig(0x2A5F)

    /** Device Information (0x180A), used to label the device in a picker. */
    val DEVICE_INFORMATION_SERVICE: UUID = sig(0x180A)
    val MANUFACTURER_NAME: UUID = sig(0x2A29)
    val MODEL_NUMBER: UUID = sig(0x2A24)

    /**
     * Services worth scanning for. Heart Rate leads because it is the one
     * profile every supported wearable in this class implements.
     */
    val SCANNABLE_SERVICES: List<UUID> = listOf(
        HEART_RATE_SERVICE,
        PULSE_OXIMETER_SERVICE,
        HEALTH_THERMOMETER_SERVICE,
    )
}
