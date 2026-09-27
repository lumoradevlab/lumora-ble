import Foundation

/// The iOS build's honest self-description.
///
/// Deliberately different from the Android matrix, because the platforms
/// genuinely support different things. Reporting Android's list here would
/// promise BLE protocols this build does not implement.
///
/// Keep in lockstep with `core/.../SupportMatrix.kt` and
/// docs/PROTOCOL-STATUS.md.
enum IosSupportMatrix {

    static let all: [DeviceSupport] = [
        DeviceSupport(
            kind: .appleWatch,
            status: .requiresSetup,
            prerequisite: "The user must grant Health access when prompted, and the "
                + "watch must be paired with this iPhone. Data arrives from the "
                + "HealthKit store the watch syncs into — there is nothing to scan "
                + "for and nothing to connect to.",
            limitation: "Read through HealthKit, not over a radio: watchOS exposes no "
                + "BLE service for health data. iOS never reports whether read access "
                + "was denied, so an empty result is not evidence of a permission "
                + "problem. Samples arrive in batches as the watch syncs, so this is "
                + "near-live rather than the sub-second cadence of a BLE strap. Sleep "
                + "is returned as staged intervals from backfill only, never live; "
                + "stage detail (core/deep/REM) needs iOS 16+ and a watch that "
                + "records it, older sources report only asleepUnspecified.",
            transport: .healthKit
        ),
        DeviceSupport(
            kind: .heartRateMonitor,
            status: .blocked,
            prerequisite: nil,
            limitation: "Standard GATT heart rate is implemented on Android only. The "
                + "iOS build reads Apple Watch data through HealthKit; a CoreBluetooth "
                + "implementation of the standard profile is not yet written.",
            transport: .ble
        ),
        DeviceSupport(
            kind: .ouraRing,
            status: .blocked,
            prerequisite: nil,
            limitation: "The Oura protocol is implemented on Android only. Porting it "
                + "means reimplementing the auth handshake over CoreBluetooth.",
            transport: .ble
        ),
        DeviceSupport(
            kind: .dexcomSensor,
            status: .blocked,
            prerequisite: nil,
            limitation: "The Dexcom G6 protocol is implemented on Android only. "
                + "Porting it means reimplementing mutual auth over CoreBluetooth.",
            transport: .ble
        ),
        DeviceSupport(
            kind: .libreSensor,
            status: .blocked,
            prerequisite: nil,
            limitation: "Libre 1/2 are read over NFC. Core NFC can read ISO-15693 "
                + "tags, but the reader is implemented on Android only.",
            transport: .nfc
        ),
        DeviceSupport(
            kind: .fitbitTracker,
            status: .blocked,
            prerequisite: nil,
            limitation: "Fitbit's sync protocol is encrypted under a per-device key "
                + "provisioned through Fitbit's cloud, on every platform. If the user "
                + "has Fitbit writing into Apple Health, APPLE_WATCH surfaces it.",
            transport: .ble
        ),
        DeviceSupport(
            kind: .pixelWatch,
            status: .blocked,
            prerequisite: nil,
            limitation: "A Pixel Watch is a Wear OS device with no GATT surface, and "
                + "it does not pair with an iPhone at all.",
            transport: .ble
        ),
    ]

    static func forKind(_ kind: DeviceKind) -> DeviceSupport? {
        all.first { $0.kind == kind }
    }
}
