package dev.lumora.ble.libre

import android.nfc.Tag
import android.nfc.tech.NfcV
import dev.lumora.ble.core.DeviceError
import dev.lumora.ble.core.DeviceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.time.Instant

/**
 * Reads a Libre 1/2 sensor over NFC.
 *
 * Libre 1/2 are NFC devices, not BLE ones: the phone is held against the sensor
 * and the full 344-byte FRAM image is transferred in one pass. There is no
 * connection to maintain, no pairing, and no encryption on these generations —
 * which is exactly why they remain practical to support while Libre 3 does not.
 *
 * Requires `android.permission.NFC` and an NfcV-capable radio.
 *
 * This is strictly **read-only**. Sensor activation requires an Abbott-signed
 * command, and a malformed write can permanently ruin a sensor the user paid
 * for, so activation is left to the official app.
 */
class LibreNfcReader {

    /** ISO 15693 single-block read; the FRAM is fetched 3 blocks at a time. */
    private fun readBlocksCommand(startBlock: Int, count: Int) = byteArrayOf(
        0x02,                      // flags: high data rate
        0x23,                      // READ MULTIPLE BLOCKS
        startBlock.toByte(),
        (count - 1).toByte(),      // count is encoded as n-1
    )

    /** Abbott custom command returning sensor identity. */
    private val patchInfoCommand = byteArrayOf(0x02, 0xA1.toByte(), 0x07)

    /**
     * Reads identity and the full FRAM image, then decodes it.
     *
     * Hold the phone still: NFC transfers of this size take a moment and a
     * partial read throws rather than returning truncated glucose data.
     */
    suspend fun read(tag: Tag, now: Instant = Instant.now()): LibreFram.Result =
        withContext(Dispatchers.IO) {
            val nfcv = NfcV.get(tag)
                ?: throw DeviceException(DeviceError.ProtocolViolation(
                    "Tag is not NfcV — this is not a Libre 1/2 sensor"))

            try {
                nfcv.connect()

                val type = LibreSensorType.fromPatchInfo(readPatchInfo(nfcv))
                if (type == LibreSensorType.UNKNOWN) {
                    Timber.w("unrecognized Libre variant; attempting standard layout")
                }

                val fram = readFram(nfcv)
                LibreFram.parse(fram, type, now)
            } catch (e: IOException) {
                throw DeviceException(DeviceError.ProtocolViolation(
                    "NFC read failed — hold the phone against the sensor until it completes"))
            } finally {
                runCatching { nfcv.close() }
            }
        }

    private fun readPatchInfo(nfcv: NfcV): ByteArray =
        runCatching { nfcv.transceive(patchInfoCommand) }
            .getOrElse {
                Timber.w("patch info unavailable; sensor type will be unknown")
                ByteArray(0)
            }

    /**
     * Pulls the 344-byte image. The sensor serves 8-byte blocks and rejects
     * large multi-block requests, so this reads in small batches.
     */
    private fun readFram(nfcv: NfcV): ByteArray {
        val blockSize = 8
        val totalBlocks = 43 // 43 * 8 = 344
        val batch = 3
        val out = ByteArray(totalBlocks * blockSize)

        var block = 0
        while (block < totalBlocks) {
            val count = minOf(batch, totalBlocks - block)
            val response = nfcv.transceive(readBlocksCommand(block, count))

            // Byte 0 is the response flag; a non-zero value signals an error.
            if (response.isEmpty() || response[0].toInt() != 0) {
                throw IOException("sensor rejected read at block $block")
            }
            val payload = response.copyOfRange(1, response.size)
            payload.copyInto(
                destination = out,
                destinationOffset = block * blockSize,
                endIndex = minOf(payload.size, (totalBlocks - block) * blockSize),
            )
            block += count
        }
        return out
    }
}
