import Foundation

/// Swift mirrors of the Kotlin `core` model types.
///
/// These must stay in sync with
/// `core/src/main/java/dev/lumora/ble/core/Model.kt` and with the wire encoding
/// in `LumoraBlePlugin.kt`. Every enum crosses the channel as
/// SCREAMING_SNAKE_CASE by name, never by ordinal, so adding a case on one
/// platform cannot silently shift the meaning of another.
enum DeviceKind: String {
    case ouraRing = "OURA_RING"
    case libreSensor = "LIBRE_SENSOR"
    case dexcomSensor = "DEXCOM_SENSOR"
    case heartRateMonitor = "HEART_RATE_MONITOR"
    case fitbitTracker = "FITBIT_TRACKER"
    case pixelWatch = "PIXEL_WATCH"

    /// Apple Watch, read through HealthKit rather than over a radio.
    ///
    /// Unlike every other kind, this is not a peripheral the SDK connects to:
    /// watchOS exposes no BLE service for health data, so the only supported
    /// route is the HealthKit store the watch syncs into on the paired iPhone.
    case appleWatch = "APPLE_WATCH"
}

enum SupportStatus: String {
    case supported = "SUPPORTED"
    case requiresSetup = "REQUIRES_SETUP"
    case blocked = "BLOCKED"
}

enum Transport: String {
    case ble = "BLE"
    case nfc = "NFC"

    /// An OS-mediated health store, not a radio link to a device.
    ///
    /// The distinction matters to the UX exactly as NFC-vs-BLE does: there is
    /// nothing to scan for and nothing to stay connected to. The user grants
    /// permission once and data arrives from the system.
    case healthKit = "HEALTH_KIT"
}

struct DeviceId {
    let address: String
    let kind: DeviceKind

    func toMap() -> [String: Any?] {
        ["address": address, "kind": kind.rawValue]
    }
}

struct DiscoveredDevice {
    let id: DeviceId
    let name: String?
    let rssi: Int
    let serial: String?

    func toMap() -> [String: Any?] {
        ["id": id.toMap(), "name": name, "rssi": rssi, "serial": serial]
    }
}

struct DeviceSupport {
    let kind: DeviceKind
    let status: SupportStatus
    let prerequisite: String?
    let limitation: String?
    let transport: Transport

    func toMap() -> [String: Any?] {
        [
            "kind": kind.rawValue,
            "status": status.rawValue,
            "prerequisite": prerequisite,
            "limitation": limitation,
            "transport": transport.rawValue,
        ]
    }
}

/// Mirrors the Kotlin `DeviceError` closed set so Dart sees identical codes
/// from both platforms.
enum DeviceError {
    case bluetoothUnavailable(detail: String)
    case pairingRequired(instruction: String)
    case protocolViolation(detail: String)
    case operationTimeout(operation: String)
    case authRejected(detail: String)

    var code: String {
        switch self {
        case .bluetoothUnavailable: return "BluetoothUnavailable"
        case .pairingRequired: return "PairingRequired"
        case .protocolViolation: return "ProtocolViolation"
        case .operationTimeout: return "OperationTimeout"
        case .authRejected: return "AuthRejected"
        }
    }

    var message: String {
        switch self {
        case .bluetoothUnavailable(let d): return d
        case .pairingRequired(let i): return i
        case .protocolViolation(let d): return d
        case .operationTimeout(let op): return "\(op) timed out"
        case .authRejected(let d): return d
        }
    }
}

struct DeviceException: Error {
    let error: DeviceError
}

/// A reading crossing the channel. Field names match `DeviceReading.toMap()`
/// on Android exactly, so `DeviceReading.fromMap` in Dart decodes both.
enum DeviceReading {
    case heartRate(bpm: Int, ibiMs: [Int], timestamp: Date)
    case battery(percent: Int, charging: Bool, timestamp: Date)
    case temperature(celsius: Double, timestamp: Date)
    case spO2(percent: Double, timestamp: Date)
    /// SDNN in milliseconds — a statistic over a window, not a raw interval,
    /// which is why it is not folded into `heartRate`'s ibiMs.
    case heartRateVariability(sdnnMs: Double, timestamp: Date)

    /// One scored sleep interval. Covers a span rather than an instant, which
    /// is why it carries an end as well as a timestamp.
    case sleep(stage: String, start: Date, end: Date)

    func toMap() -> [String: Any?] {
        switch self {
        case .heartRate(let bpm, let ibi, let ts):
            return ["type": "heartRate", "timestamp": ts.millis, "bpm": bpm, "ibiMs": ibi]
        case .battery(let percent, let charging, let ts):
            return ["type": "battery", "timestamp": ts.millis,
                    "percent": percent, "charging": charging]
        case .temperature(let celsius, let ts):
            return ["type": "temperature", "timestamp": ts.millis, "celsius": celsius]
        case .spO2(let percent, let ts):
            return ["type": "spO2", "timestamp": ts.millis, "percent": percent]
        case .heartRateVariability(let sdnn, let ts):
            return ["type": "hrv", "timestamp": ts.millis, "sdnnMs": sdnn]
        case .sleep(let stage, let start, let end):
            return ["type": "sleep", "timestamp": start.millis,
                    "stage": stage, "end": end.millis]
        }
    }
}

extension Date {
    /// Epoch milliseconds — the timestamp unit the channel uses throughout.
    var millis: Int { Int(timeIntervalSince1970 * 1000.0) }
}
