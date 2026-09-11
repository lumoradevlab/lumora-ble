package dev.lumora.ble.dexcom

import java.util.UUID

/**
 * Dexcom G6 BLE constants.
 *
 * The G6 is the target here. The G7 uses a different, much stronger handshake
 * (EC-J-PAKE) that is not implemented — see [DexcomAuth].
 *
 * Ported from a production Flutter implementation that ran against real
 * transmitters. Dexcom publishes no BLE specification.
 */
object DexcomProtocol {

    /** Dexcom uses a custom 128-bit base, not the standard Bluetooth base. */
    private const val BASE = "849e-531c-c594-30f1f86a4ea5"

    private fun dexcom(prefix: String): UUID = UUID.fromString("$prefix-$BASE")

    val SERVICE: UUID = dexcom("f8083532")
    val CONTROL: UUID = dexcom("f8083534")
    val AUTHENTICATION: UUID = dexcom("f8083535")
    val BACKFILL: UUID = dexcom("f8083536")

    /**
     * Advertised name is "Dexcom" + the last two characters of the transmitter
     * serial, which is how a specific transmitter is found during a scan.
     */
    fun advertisedName(transmitterSerial: String): String =
        "Dexcom" + transmitterSerial.takeLast(2)

    /** Opcodes. Tx = phone to transmitter, Rx = transmitter to phone. */
    object Opcode {
        const val AUTH_REQUEST_TX: Byte = 0x01
        const val AUTH_CHALLENGE_RX: Byte = 0x03
        const val AUTH_CHALLENGE_TX: Byte = 0x04
        const val AUTH_STATUS_RX: Byte = 0x05
        const val BOND_REQUEST_TX: Byte = 0x07
        const val DISCONNECT_TX: Byte = 0x09
        const val TRANSMITTER_TIME_TX: Byte = 0x24
        const val GLUCOSE_TX: Byte = 0x30
        const val GLUCOSE_RX: Byte = 0x31
        const val BACKFILL_TX: Byte = 0x50
    }

    /** Trailing slot byte on the auth request; the alternate is rarely needed. */
    const val END_BYTE_STANDARD: Byte = 0x02
    const val END_BYTE_ALTERNATE: Byte = 0x01

    const val TOKEN_SIZE = 8

    /**
     * `01 <8-byte token> <slot>` — opens authentication.
     *
     * The token is random per attempt and is what proves the transmitter holds
     * the same serial-derived key: it echoes the token back encrypted.
     */
    fun authRequest(singleUseToken: ByteArray, alternateSlot: Boolean = false): ByteArray {
        require(singleUseToken.size == TOKEN_SIZE) { "token must be $TOKEN_SIZE bytes" }
        return byteArrayOf(Opcode.AUTH_REQUEST_TX) + singleUseToken +
            byteArrayOf(if (alternateSlot) END_BYTE_ALTERNATE else END_BYTE_STANDARD)
    }

    /** `04 <8-byte encrypted challenge>` — answers the transmitter's challenge. */
    fun authChallengeReply(encryptedChallenge: ByteArray): ByteArray {
        require(encryptedChallenge.size == TOKEN_SIZE) { "challenge reply must be 8 bytes" }
        return byteArrayOf(Opcode.AUTH_CHALLENGE_TX) + encryptedChallenge
    }

    val BOND_REQUEST = byteArrayOf(Opcode.BOND_REQUEST_TX)
    val GLUCOSE_REQUEST = byteArrayOf(Opcode.GLUCOSE_TX)
    val TRANSMITTER_TIME_REQUEST = byteArrayOf(Opcode.TRANSMITTER_TIME_TX)
    val DISCONNECT = byteArrayOf(Opcode.DISCONNECT_TX)
}
