package dev.lumora.ble.dexcom

import dev.lumora.ble.core.DeviceError
import dev.lumora.ble.core.DeviceException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Dexcom G6 authentication.
 *
 * The shared key is derived entirely from the transmitter serial printed on the
 * applicator — there is no key exchange. The handshake is:
 *
 *  1. Phone sends `01 <random 8-byte token> <slot>`.
 *  2. Transmitter replies `03 <our token, encrypted> <its 8-byte challenge>`.
 *  3. Phone verifies the echoed token, proving the transmitter holds the same
 *     key, then replies `04 <challenge, encrypted>`.
 *  4. Transmitter reports `05 <authenticated> <bonded>`; bonding follows.
 *
 * Step 3's verification is mutual authentication and must not be skipped: it is
 * what stops a nearby impostor transmitter from feeding an app fake glucose.
 *
 * This is NOT how the G7 works — see [requireG6].
 */
object DexcomAuth {

    private val random = SecureRandom()

    /**
     * Builds the AES-128 key from the serial.
     *
     * The serial is padded to exactly 16 bytes as `00<SN>00<SN>`, which is why a
     * 6-character serial is required.
     */
    fun keyFor(transmitterSerial: String): ByteArray {
        val sn = validateSerial(transmitterSerial)
        val key = "00$sn" + "00$sn"
        val bytes = key.toByteArray(Charsets.UTF_8)
        check(bytes.size == 16) { "derived key must be 16 bytes, was ${bytes.size}" }
        return bytes
    }

    /**
     * Encrypts an 8-byte value the way the transmitter expects: the value is
     * doubled to fill one AES block, encrypted ECB, and the first 8 bytes of
     * ciphertext are returned.
     */
    fun encryptChallenge(value: ByteArray, transmitterSerial: String): ByteArray {
        require(value.size == DexcomProtocol.TOKEN_SIZE) {
            "challenge must be ${DexcomProtocol.TOKEN_SIZE} bytes, was ${value.size}"
        }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyFor(transmitterSerial), "AES"))
        // Doubling fills exactly one 16-byte block, so NoPadding is correct.
        return cipher.doFinal(value + value).copyOf(DexcomProtocol.TOKEN_SIZE)
    }

    fun generateToken(): ByteArray =
        ByteArray(DexcomProtocol.TOKEN_SIZE).also(random::nextBytes)

    /**
     * Confirms the transmitter encrypted our token to the same value we did.
     * A mismatch means it does not hold our key — reject the connection.
     */
    fun verifyTransmitter(
        ourToken: ByteArray,
        echoedEncryptedToken: ByteArray,
        transmitterSerial: String,
    ): Boolean = encryptChallenge(ourToken, transmitterSerial)
        .contentEquals(echoedEncryptedToken)

    /** Serials are 6 alphanumeric characters; the key derivation depends on it. */
    fun validateSerial(serial: String): String {
        val normalized = serial.trim().uppercase()
        if (normalized.length != 6 || !normalized.all { it.isLetterOrDigit() }) {
            throw DeviceException(DeviceError.AuthRejected(
                "Dexcom transmitter serial must be 6 alphanumeric characters " +
                    "(printed on the applicator), got '$serial'"))
        }
        return normalized
    }

    /**
     * The G7 replaces this handshake with EC-J-PAKE, for which Android provides
     * no primitive (BouncyCastle ships only the finite-field variant) and whose
     * pairing channel is contended with the official app. Fail clearly rather
     * than letting a G7 hang in a retry loop.
     */
    fun requireG6(advertisedName: String) {
        if (advertisedName.startsWith("DXCM", ignoreCase = true)) {
            throw DeviceException(DeviceError.PairingRequired(
                "This looks like a Dexcom G7/ONE+ ('$advertisedName'), which requires " +
                    "EC-J-PAKE authentication that this SDK does not implement. Only " +
                    "G6 transmitters (advertised as 'Dexcom<XX>') are supported over BLE."))
        }
    }
}
