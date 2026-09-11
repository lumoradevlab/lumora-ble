package dev.lumora.ble.libre

import dev.lumora.ble.core.DeviceKind
import dev.lumora.ble.core.GlucoseReading
import dev.lumora.ble.core.GlucoseTrend
import dev.lumora.ble.transport.u8
import java.time.Instant

/**
 * Parser for the Libre 1/2 FRAM image read over NFC.
 *
 * The sensor exposes a 344-byte memory image containing two ring buffers:
 *  - **trend**: 16 slots, one per minute (most recent ~16 minutes)
 *  - **history**: 32 slots, one per 15 minutes (~8 hours)
 *
 * Each slot is 6 bytes and the buffers wrap, so the newest entry is at
 * `index - 1` and older entries walk backwards with modulo arithmetic.
 *
 * Ported from a production Flutter implementation that ran against real sensors.
 */
object LibreFram {

    const val FRAM_SIZE = 344

    private const val TREND_SLOTS = 16
    private const val HISTORY_SLOTS = 32
    private const val SLOT_SIZE = 6

    // Standard layout offsets.
    private const val TREND_INDEX = 26
    private const val HISTORY_INDEX = 27
    private const val SENSOR_TIME_LOW = 316
    private const val SENSOR_TIME_HIGH = 317
    private const val TREND_DATA_START = 28
    private const val HISTORY_DATA_START = 124

    // Libre Pro/H keeps its counters elsewhere.
    private const val PRO_TREND_INDEX = 76
    private const val PRO_HISTORY_INDEX = 29
    private const val PRO_SENSOR_TIME_LOW = 74
    private const val PRO_SENSOR_TIME_HIGH = 75
    private const val PRO_TREND_DATA_START = 80

    private const val STATE_OFFSET = 4

    /**
     * Uncalibrated conversion from raw sensor counts to mg/dL.
     *
     * The upstream implementation computes `raw * 117.64705`, which yields a
     * hundredths-of-mg/dL scale, so the result is divided by 100. Kept as two
     * named constants rather than a single fused factor so the correspondence
     * to the reference implementation stays visible.
     *
     * This is the *uncalibrated* path. Sensors carry per-unit calibration
     * parameters that the official algorithm applies on top; without them
     * readings can drift from what the vendor app shows, so treat these as
     * approximate. See [Libre1CalibrationParameters].
     */
    const val RAW_MULTIPLIER = 117.64705
    private const val RAW_SCALE = 100.0

    private const val GLUCOSE_MASK = 0x1FFF
    private const val MIN_MGDL = 20
    private const val MAX_MGDL = 500

    /** Everything decoded from one FRAM read. */
    data class Result(
        val state: LibreSensorState,
        val sensorAgeMinutes: Int,
        val type: LibreSensorType,
        /** Newest first, per-minute. */
        val trend: List<GlucoseReading>,
        /** Newest first, per-15-minutes. */
        val history: List<GlucoseReading>,
    ) {
        val current: GlucoseReading? get() = trend.firstOrNull()

        /** Sensors run ~14 days; past that the readings stop being trustworthy. */
        val isExpired: Boolean
            get() = state == LibreSensorState.EXPIRED ||
                state == LibreSensorState.SHUTDOWN ||
                sensorAgeMinutes > 14 * 24 * 60
    }

    /**
     * Decodes a FRAM image.
     *
     * [now] anchors the timestamps: the sensor reports only its own age, so
     * absolute times are derived by subtracting that age from the read time.
     */
    fun parse(
        fram: ByteArray,
        type: LibreSensorType,
        now: Instant = Instant.now(),
    ): Result {
        require(fram.size >= FRAM_SIZE) {
            "FRAM image must be at least $FRAM_SIZE bytes, was ${fram.size}"
        }

        val pro = type.usesProLayout
        val trendIndex = fram.u8(if (pro) PRO_TREND_INDEX else TREND_INDEX)
        val historyIndex = fram.u8(if (pro) PRO_HISTORY_INDEX else HISTORY_INDEX)
        val ageMinutes = if (pro) {
            256 * fram.u8(PRO_SENSOR_TIME_HIGH) + fram.u8(PRO_SENSOR_TIME_LOW)
        } else {
            256 * fram.u8(SENSOR_TIME_HIGH) + fram.u8(SENSOR_TIME_LOW)
        }

        val state = LibreSensorState.fromCode(fram.u8(STATE_OFFSET))
        val sensorStart = now.minusSeconds(ageMinutes * 60L)

        val trend = readRing(
            fram = fram,
            slots = TREND_SLOTS,
            ringIndex = trendIndex,
            dataStart = if (pro) PRO_TREND_DATA_START else TREND_DATA_START,
            sensorStart = sensorStart,
            // Trend slot n is n minutes before the current sensor age.
            secondsFor = { i -> maxOf(0, ageMinutes - i) * 60L },
        )

        val history = readRing(
            fram = fram,
            slots = HISTORY_SLOTS,
            ringIndex = historyIndex,
            dataStart = HISTORY_DATA_START,
            sensorStart = sensorStart,
            // History is every 15 minutes, offset to the middle of each block.
            secondsFor = { i ->
                val minutes = maxOf(0, abs(ageMinutes - 3) / 15 * 15 - i * 15)
                minutes * 60L
            },
            historical = true,
        )

        return Result(state, ageMinutes, type, trend, history)
    }

    private fun abs(v: Int) = if (v < 0) -v else v

    /**
     * Walks a wrapping ring buffer backwards from [ringIndex], newest first.
     */
    private fun readRing(
        fram: ByteArray,
        slots: Int,
        ringIndex: Int,
        dataStart: Int,
        sensorStart: Instant,
        secondsFor: (Int) -> Long,
        historical: Boolean = false,
    ): List<GlucoseReading> {
        val out = ArrayList<GlucoseReading>(slots)

        for (index in 0 until slots) {
            // The newest entry sits one slot before the write pointer.
            var slot = ringIndex - index - 1
            if (slot < 0) slot += slots

            val offset = slot * SLOT_SIZE + dataStart
            if (offset + SLOT_SIZE > fram.size) continue

            val raw = (fram.u8(offset + 1) shl 8 or fram.u8(offset)) and GLUCOSE_MASK
            if (raw == 0) continue

            val mgdl = (raw * RAW_MULTIPLIER / RAW_SCALE).toInt()
            if (mgdl !in MIN_MGDL..MAX_MGDL) continue

            out += GlucoseReading(
                timestamp = sensorStart.plusSeconds(secondsFor(index)),
                mgdl = mgdl,
                trend = GlucoseTrend.UNKNOWN, // derived below for the trend buffer
                source = DeviceKind.LIBRE_SENSOR,
                isHistorical = historical,
            )
        }

        return if (historical) out else withDerivedTrend(out)
    }

    /**
     * Libre reports no trend arrow — it is computed from the slope of recent
     * per-minute readings, which is what the official app does too.
     */
    private fun withDerivedTrend(readings: List<GlucoseReading>): List<GlucoseReading> {
        if (readings.size < 2) return readings
        val newest = readings[0]
        // Compare against a reading ~5 minutes older for a stable slope.
        val reference = readings.getOrNull(5) ?: readings.last()
        val minutes = (newest.timestamp.epochSecond - reference.timestamp.epochSecond) / 60.0
        if (minutes <= 0.0) return readings

        val rate = (newest.mgdl - reference.mgdl) / minutes
        return buildList(readings.size) {
            add(newest.copy(trend = trendFrom(rate), rateOfChange = rate))
            addAll(readings.drop(1))
        }
    }

    fun trendFrom(mgdlPerMinute: Double): GlucoseTrend = when {
        mgdlPerMinute >= 3.0 -> GlucoseTrend.RISING_RAPIDLY
        mgdlPerMinute >= 2.0 -> GlucoseTrend.RISING
        mgdlPerMinute >= 1.0 -> GlucoseTrend.RISING_SLIGHTLY
        mgdlPerMinute > -1.0 -> GlucoseTrend.FLAT
        mgdlPerMinute > -2.0 -> GlucoseTrend.FALLING_SLIGHTLY
        mgdlPerMinute > -3.0 -> GlucoseTrend.FALLING
        else -> GlucoseTrend.FALLING_RAPIDLY
    }
}
