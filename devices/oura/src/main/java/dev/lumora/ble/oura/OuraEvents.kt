package dev.lumora.ble.oura

import dev.lumora.ble.core.DeviceReading
import dev.lumora.ble.core.HeartRateSample
import dev.lumora.ble.core.u16le
import dev.lumora.ble.core.u8
import timber.log.Timber
import java.time.Instant

/**
 * Decoders for the ring's event stream.
 *
 * Only a subset of tags is publicly documented. Unknown tags are logged and
 * skipped rather than treated as errors, because firmware updates add new ones
 * and a strict parser would break the whole sync.
 */
object OuraEvents {

    /** `07 01 <status>` plus payload — live measurement push. */
    fun parseRealtime(frame: ByteArray): DeviceReading? {
        if (frame.size < 5) return null
        // Payload layout beyond the status byte is mode-dependent; the HR case
        // carries a u16 IBI in milliseconds from which BPM is derived.
        val ibiMs = frame.u16le(3)
        if (ibiMs !in 250..3000) return null // 20-240 bpm sanity window
        val bpm = (60_000.0 / ibiMs).toInt()
        return DeviceReading.HeartRate(
            HeartRateSample(Instant.now(), bpm, listOf(ibiMs))
        )
    }

    /**
     * History events are `tag length payload...`. Ring-start (0x41) and debug
     * (0x43) are identified; sample-bearing tags vary by firmware and are
     * surfaced as null until confirmed against a real device.
     */
    fun parseHistory(frame: ByteArray, since: Instant): DeviceReading? {
        if (frame.size < 2) return null
        return when (frame[0]) {
            OuraProtocol.EVENT_RING_START -> {
                Timber.d("ring start event")
                null
            }
            OuraProtocol.EVENT_DEBUG -> {
                Timber.v("debug: %s", String(frame.drop(2).toByteArray()).trim())
                null
            }
            else -> {
                Timber.v("unmapped history tag 0x%02x len=%d", frame[0], frame.u8(1))
                null
            }
        }
    }
}
