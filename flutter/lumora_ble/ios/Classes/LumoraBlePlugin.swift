import Foundation
#if canImport(Flutter)
import Flutter
#elseif canImport(FlutterMacOS)
import FlutterMacOS
#endif

/// iOS implementation of the Lumora plugin.
///
/// Speaks the same channels and the same wire encoding as
/// `LumoraBlePlugin.kt`, so one Dart API serves both platforms. What differs is
/// what each platform can reach: Android connects to BLE peripherals, iOS reads
/// the HealthKit store an Apple Watch syncs into.
///
/// Methods that only make sense on the BLE path (`scan`, `connect`,
/// `setDexcomTransmitter`, `readLibreSensor`) fail here with a clear,
/// actionable error rather than returning empty and looking broken.
public class LumoraBlePlugin: NSObject, FlutterPlugin {

    private let health = HealthKitReader()
    private var readingSink: FlutterEventSink?
    private var connectionSink: FlutterEventSink?

    /// HealthKit has no device address, so one synthetic id stands for
    /// "whatever the paired watch writes into Health".
    private static let watchId = DeviceId(address: "healthkit", kind: .appleWatch)

    public static func register(with registrar: FlutterPluginRegistrar) {
        let instance = LumoraBlePlugin()

        let methods = FlutterMethodChannel(
            name: "dev.lumora.ble/methods", binaryMessenger: registrar.messenger())
        registrar.addMethodCallDelegate(instance, channel: methods)

        FlutterEventChannel(name: "dev.lumora.ble/readings",
                            binaryMessenger: registrar.messenger())
            .setStreamHandler(ReadingStreamHandler(plugin: instance))
        FlutterEventChannel(name: "dev.lumora.ble/connections",
                            binaryMessenger: registrar.messenger())
            .setStreamHandler(ConnectionStreamHandler(plugin: instance))
        FlutterEventChannel(name: "dev.lumora.ble/scan",
                            binaryMessenger: registrar.messenger())
            .setStreamHandler(ScanStreamHandler())
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "supportedDevices":
            result(IosSupportMatrix.all.map { $0.toMap() })

        case "requestPermissions":
            Task {
                do {
                    let ok = try await health.requestAuthorization()
                    result(ok)
                } catch {
                    result(self.flutterError(error))
                }
            }

        case "connect":
            // "Connecting" to HealthKit means starting to observe it. There is
            // no link to establish, so this succeeds or fails immediately.
            Task {
                do {
                    _ = try await self.health.requestAuthorization()
                    try self.health.startObserving { [weak self] reading in
                        self?.emit(reading)
                    }
                    self.emitConnection(status: "READY")
                    result(nil)
                } catch {
                    result(self.flutterError(error))
                }
            }

        case "disconnect", "disconnectAll":
            health.stopObserving()
            emitConnection(status: "DISCONNECTED")
            result(nil)

        case "backfill":
            let args = call.arguments as? [String: Any]
            let sinceMs = (args?["since"] as? NSNumber)?.doubleValue ?? 0
            Task {
                do {
                    let since = Date(timeIntervalSince1970: sinceMs / 1000.0)
                    let readings = try await self.health.backfill(since: since)
                    result(readings.map { $0.toMap() })
                } catch {
                    result(self.flutterError(error))
                }
            }

        // Android-only paths. Failing loudly beats returning empty: an
        // integrator calling these on iOS has a real portability bug.
        case "scan":
            result(unsupported(
                "Scanning is not available on iOS. Apple Watch data is read from "
                + "HealthKit, which exposes no devices to scan for — call connect() "
                + "directly."))

        case "setDexcomTransmitter":
            result(unsupported(
                "The Dexcom protocol is implemented on Android only."))

        case "readLibreSensor":
            result(unsupported(
                "The Libre NFC reader is implemented on Android only."))

        default:
            result(FlutterMethodNotImplemented)
        }
    }

    fileprivate func setReadingSink(_ sink: FlutterEventSink?) { readingSink = sink }
    fileprivate func setConnectionSink(_ sink: FlutterEventSink?) { connectionSink = sink }

    private func emit(_ reading: DeviceReading) {
        // Event sinks must be touched on the platform thread.
        DispatchQueue.main.async { self.readingSink?(reading.toMap()) }
    }

    private func emitConnection(status: String) {
        DispatchQueue.main.async {
            var state: [String: Any?] = ["status": status]
            if status == "READY" {
                state["device"] = DiscoveredDevice(
                    id: Self.watchId, name: "Apple Watch (HealthKit)",
                    rssi: 0, serial: nil).toMap()
            }
            // Dart decodes this stream as Map<DeviceId, ConnectionState>, so
            // the key is itself a map. Swift dictionaries require Hashable
            // keys, which [String: Any?] is not — box both sides through
            // NSDictionary, which the standard codec encodes identically to
            // the Kotlin side's Map<DeviceId, ConnectionState>.
            let key = NSDictionary(dictionary: Self.watchId.toMap() as [AnyHashable: Any])
            let value = NSDictionary(dictionary: state.compactMapValues { $0 } as [AnyHashable: Any])
            self.connectionSink?(NSDictionary(object: value, forKey: key))
        }
    }

    private func unsupported(_ message: String) -> FlutterError {
        FlutterError(code: "ProtocolViolation", message: message, details: nil)
    }

    private func flutterError(_ error: Error) -> FlutterError {
        if let e = error as? DeviceException {
            return FlutterError(code: e.error.code, message: e.error.message, details: nil)
        }
        return FlutterError(code: "ProtocolViolation",
                            message: error.localizedDescription, details: nil)
    }
}

private class ReadingStreamHandler: NSObject, FlutterStreamHandler {
    private weak var plugin: LumoraBlePlugin?
    init(plugin: LumoraBlePlugin) { self.plugin = plugin }

    func onListen(withArguments _: Any?, eventSink: @escaping FlutterEventSink) -> FlutterError? {
        plugin?.setReadingSink(eventSink)
        return nil
    }
    func onCancel(withArguments _: Any?) -> FlutterError? {
        plugin?.setReadingSink(nil)
        return nil
    }
}

private class ConnectionStreamHandler: NSObject, FlutterStreamHandler {
    private weak var plugin: LumoraBlePlugin?
    init(plugin: LumoraBlePlugin) { self.plugin = plugin }

    func onListen(withArguments _: Any?, eventSink: @escaping FlutterEventSink) -> FlutterError? {
        plugin?.setConnectionSink(eventSink)
        return nil
    }
    func onCancel(withArguments _: Any?) -> FlutterError? {
        plugin?.setConnectionSink(nil)
        return nil
    }
}

/// Fails the subscription immediately rather than returning an empty stream, so
/// a caller scanning on iOS sees why nothing arrives.
private class ScanStreamHandler: NSObject, FlutterStreamHandler {
    func onListen(withArguments _: Any?, eventSink: @escaping FlutterEventSink) -> FlutterError? {
        FlutterError(
            code: "ProtocolViolation",
            message: "Scanning is not available on iOS. Apple Watch data is read from "
                + "HealthKit, which exposes no devices to scan for.",
            details: nil)
    }
    func onCancel(withArguments _: Any?) -> FlutterError? { nil }
}
