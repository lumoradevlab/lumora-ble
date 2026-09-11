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
    )

    fun forKind(kind: DeviceKind): DeviceSupport = all.first { it.kind == kind }

    fun isUsable(kind: DeviceKind): Boolean =
        forKind(kind).status != SupportStatus.BLOCKED
}
