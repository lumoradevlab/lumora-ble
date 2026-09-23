package dev.lumora.ble.core

/**
 * The SDK's honest self-description.
 *
 * Exposed at runtime so an integrating app can grey out a device in its UI
 * instead of discovering at connect time that it cannot be used. Keep this in
 * lockstep with docs/PROTOCOL-STATUS.md.
 *
 * Note the generation targeting: the supported devices are the ones with
 * tractable protocols (Dexcom **G6**, Libre **1/2**). Their successors (G7,
 * Libre 3) moved to authentication this SDK cannot implement.
 *
 * [DeviceKind.HEART_RATE_MONITOR] is the exception to all of that. It is built
 * on published SIG profiles rather than reverse-engineering, so it carries no
 * vendor-ToS exposure and no firmware-update fragility, and one entry covers
 * every conforming peripheral. Where a vendor is blocked for its proprietary
 * protocol but broadcasts standard heart rate — Fitbit and Pixel Watch both
 * do — the blocked entry names that route explicitly.
 */
object SupportMatrix {

    val all: List<DeviceSupport> = listOf(
        DeviceSupport(
            kind = DeviceKind.DEXCOM_SENSOR,
            status = SupportStatus.REQUIRES_SETUP,
            prerequisite = "Needs the 6-character transmitter serial printed on the " +
                "applicator. Android will prompt to bond on first connect.",
            limitation = "Dexcom G6 only. The G7/ONE+ uses EC-J-PAKE authentication " +
                "that is not implemented and is rejected with a clear error.",
            transport = Transport.BLE,
        ),
        DeviceSupport(
            kind = DeviceKind.LIBRE_SENSOR,
            status = SupportStatus.SUPPORTED,
            prerequisite = "Hold the phone against the sensor to scan. The sensor must " +
                "already be activated with the official FreeStyle Libre app.",
            limitation = "Libre 1/2 only, read over NFC rather than BLE. Libre 3 requires " +
                "an Abbott-issued certificate and is not supported. Readings are " +
                "uncalibrated and may differ slightly from the vendor app.",
            transport = Transport.NFC,
        ),
        DeviceSupport(
            kind = DeviceKind.OURA_RING,
            status = SupportStatus.REQUIRES_SETUP,
            prerequisite = "The ring must be factory-reset before it will accept a " +
                "third-party auth key, which means giving up the official Oura app " +
                "for that ring.",
            limitation = "Raw samples only — Oura's sleep and readiness scores are " +
                "computed by proprietary models that do not run on the ring.",
            transport = Transport.BLE,
        ),
        DeviceSupport(
            kind = DeviceKind.HEART_RATE_MONITOR,
            status = SupportStatus.REQUIRES_SETUP,
            prerequisite = "The device must be broadcasting. On a Fitbit Charge 6 / " +
                "Fitbit Air or Pixel Watch 2+, swipe down and tap Connected Fitness " +
                "→ Connect; a Pixel Watch may also need Extended Pairing enabled. " +
                "Dedicated chest straps broadcast whenever worn and need no setup.",
            limitation = "Live measurements only — the standard profiles define no " +
                "stored history, so backfill returns nothing. Broadcast is " +
                "session-scoped, not a background connection, and the device holds " +
                "few concurrent links (Charge 6 allows one; Pixel Watch 3+ allows " +
                "two), so a watch already paired to gym equipment will refuse.",
            transport = Transport.BLE,
        ),
        DeviceSupport(
            kind = DeviceKind.FITBIT_TRACKER,
            status = SupportStatus.BLOCKED,
            limitation = "Fitbit's own sync protocol (activity, sleep, stored history) " +
                "is encrypted under a per-device key provisioned through Fitbit's " +
                "cloud at manufacture and never exposed to the client, so no " +
                "third-party client can derive it. Use HEART_RATE_MONITOR for live " +
                "heart rate, or the Google Health API for historical data — note " +
                "the legacy Fitbit Web API shut down in September 2026.",
            transport = Transport.BLE,
        ),
        DeviceSupport(
            kind = DeviceKind.PIXEL_WATCH,
            status = SupportStatus.BLOCKED,
            limitation = "A Pixel Watch is a Wear OS device, not a BLE peripheral with " +
                "a companion protocol: its data lives in on-device Health Services " +
                "and syncs to Google's cloud, with no GATT surface to connect to. " +
                "Reaching it means shipping a Wear OS app or using the Google Health " +
                "API. Live heart rate is available via HEART_RATE_MONITOR.",
            transport = Transport.BLE,
        ),
    )

    fun forKind(kind: DeviceKind): DeviceSupport = all.first { it.kind == kind }

    fun isUsable(kind: DeviceKind): Boolean =
        forKind(kind).status != SupportStatus.BLOCKED
}
