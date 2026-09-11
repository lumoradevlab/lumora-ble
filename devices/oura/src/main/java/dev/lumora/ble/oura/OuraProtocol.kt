package dev.lumora.ble.oura

import java.util.UUID

/**
 * Oura Ring BLE protocol constants (Gen 3/4/5 share this layout).
 *
 * Source: reverse-engineering notes in Th0rgal/open_oura. Oura publishes no BLE
 * spec and no official SDK, so every value here is empirical and may change with
 * any firmware release. Treat unknown tags as non-fatal.
 */
object OuraProtocol {
    val SERVICE: UUID = UUID.fromString("98ed0001-a541-11e4-b6a0-0002a5d5c51b")
    val WRITE_CHAR: UUID = UUID.fromString("98ed0002-a541-11e4-b6a0-0002a5d5c51b")
    val NOTIFY_CHAR: UUID = UUID.fromString("98ed0003-a541-11e4-b6a0-0002a5d5c51b")
    val CHARGER_SERVICE: UUID = UUID.fromString("8bc5888f-c577-4f5d-857f-377354093f13")

    const val MANUFACTURER_ID = 0x02b2
    const val MTU = 203

    /** Frame layout is `tag length payload…`; 0x2f is the extended-op envelope. */
    const val TAG_EXTENDED: Byte = 0x2f

    // Top-level opcodes.
    const val OP_FIRMWARE_REQ: Byte = 0x08
    const val OP_FIRMWARE_RSP: Byte = 0x09
    const val OP_BATTERY_REQ: Byte = 0x0c
    const val OP_BATTERY_RSP: Byte = 0x0d
    const val OP_EVENTS_REQ: Byte = 0x10
    const val OP_EVENTS_RSP: Byte = 0x11
    const val OP_SET_AUTH_KEY: Byte = 0x24
    const val OP_SET_AUTH_KEY_RSP: Byte = 0x25
    const val OP_REALTIME_REQ: Byte = 0x06
    const val OP_REALTIME_RSP: Byte = 0x07
    const val OP_BLE_MODE_REQ: Byte = 0x16

    // Extended sub-tags, carried as the first payload byte under TAG_EXTENDED.
    const val EXT_NONCE_REQ: Byte = 0x2b
    const val EXT_NONCE_RSP: Byte = 0x2c
    const val EXT_AUTH_REQ: Byte = 0x2d
    const val EXT_AUTH_RSP: Byte = 0x2e

    // History event tags.
    const val EVENT_RING_START: Byte = 0x41
    const val EVENT_DEBUG: Byte = 0x43

    /** `2f 01 2b` — ask the ring for a 15-byte nonce. */
    val REQUEST_NONCE = byteArrayOf(TAG_EXTENDED, 0x01, EXT_NONCE_REQ)

    /** `0c 00` — battery level. Requires prior authentication. */
    val REQUEST_BATTERY = byteArrayOf(OP_BATTERY_REQ, 0x00)

    /** `08 03 00 00 00` — firmware/API version. Works pre-auth. */
    val REQUEST_FIRMWARE = byteArrayOf(OP_FIRMWARE_REQ, 0x03, 0x00, 0x00, 0x00)

    fun setAuthKey(key: ByteArray): ByteArray {
        require(key.size == 16) { "auth key must be 16 bytes" }
        return byteArrayOf(OP_SET_AUTH_KEY, 0x10) + key
    }

    /** `2f 11 2d <16 bytes>` — answer the nonce challenge. */
    fun authenticate(encryptedNonce: ByteArray): ByteArray {
        require(encryptedNonce.size == 16) { "encrypted nonce must be 16 bytes" }
        return byteArrayOf(TAG_EXTENDED, 0x11, EXT_AUTH_REQ) + encryptedNonce
    }

    /** `10 09 00 00 00 08 ff ff ff ff` — stream stored history events. */
    fun requestEvents(): ByteArray = byteArrayOf(
        OP_EVENTS_REQ, 0x09, 0x00, 0x00, 0x00, 0x08,
        0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    )

    /** `06 04 <mode> 00 00 00` — start/stop live measurement. */
    fun realtime(mode: Int): ByteArray =
        byteArrayOf(OP_REALTIME_REQ, 0x04, mode.toByte(), 0x00, 0x00, 0x00)

    /** `16 01 <0=normal, 1=fast HR>` — connection-interval profile. */
    fun bleMode(fastHeartRate: Boolean): ByteArray =
        byteArrayOf(OP_BLE_MODE_REQ, 0x01, if (fastHeartRate) 1 else 0)

    /** Extracts the 15-byte nonce from `2f 10 2c <nonce>`. */
    fun parseNonce(response: ByteArray): ByteArray? {
        if (response.size < 18) return null
        if (response[0] != TAG_EXTENDED || response[2] != EXT_NONCE_RSP) return null
        return response.copyOfRange(3, 18)
    }

    /** `2f 02 2e 00` = success, `…01` = rejected. */
    fun parseAuthResult(response: ByteArray): Boolean? {
        if (response.size < 4) return null
        if (response[0] != TAG_EXTENDED || response[2] != EXT_AUTH_RSP) return null
        return response[3] == 0x00.toByte()
    }
}
