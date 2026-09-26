import Foundation
#if canImport(HealthKit)
import HealthKit
#endif

/// Reads Apple Watch health data from the HealthKit store.
///
/// **This is not a BLE connection, and the difference is structural.** watchOS
/// exposes no GATT service for health data: an Apple Watch pairs only with its
/// iPhone, over a proprietary link, and writes into HealthKit on that phone.
/// So there is nothing to scan for, nothing to connect to, and no protocol to
/// implement — the SDK reads a system store the watch has already populated.
///
/// Three consequences shape the API:
///
/// 1. **No device identity.** HealthKit reports samples, and a sample's source
///    may be the watch, the phone, or a third-party app. The SDK surfaces one
///    synthetic device rather than inventing per-device addresses.
/// 2. **Permission is per data type and one-way.** The user grants read access
///    per quantity type, and iOS deliberately does not tell an app that read
///    access was *denied* — a denied type looks identical to a type with no
///    data. Empty results are therefore never proof of a permission problem.
/// 3. **Latency, not live streaming.** Watch samples reach the phone's store in
///    batches. An anchored query delivers them as they arrive, which is close
///    to live for heart rate but is not the sub-second cadence of a BLE strap.
final class HealthKitReader {

    #if canImport(HealthKit)
    private let store = HKHealthStore()
    private var activeQueries: [HKQuery] = []

    /// Raw sample count from the most recent backfill, per type identifier.
    /// -1 means the query itself errored. Surfaced so an empty overall result
    /// can be attributed to a specific type rather than guessed at.
    private(set) var lastReadCounts: [String: Int] = [:]

    /// Quantity types this SDK reads, paired with the reading each becomes.
    private static let readTypes: [HKQuantityTypeIdentifier] = [
        .heartRate,
        .heartRateVariabilitySDNN,
        .oxygenSaturation,
        .bodyTemperature,
    ]

    var isAvailable: Bool { HKHealthStore.isHealthDataAvailable() }

    /// Requests read access.
    ///
    /// Returns true once the prompt has been answered — **not** that access was
    /// granted. iOS withholds that distinction by design, so that a user's
    /// decision to hide a condition is not itself detectable. Callers must not
    /// treat this as proof that data will arrive.
    func requestAuthorization() async throws -> Bool {
        guard isAvailable else {
            throw DeviceException(error: .bluetoothUnavailable(
                detail: "HealthKit is not available on this device."))
        }
        let types = Set(Self.readTypes.compactMap { HKQuantityType.quantityType(forIdentifier: $0) })
        // The completion-handler form, not the async one: the async overload is
        // iOS 15+, and this plugin's deployment target is iOS 13.
        return try await withCheckedThrowingContinuation { continuation in
            store.requestAuthorization(toShare: [], read: types) { _, error in
                if let error = error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: true)
                }
            }
        }
    }

    /// Starts delivering samples as HealthKit receives them from the watch.
    ///
    /// Uses an anchored query so each update yields only what is new; a plain
    /// sample query would re-deliver the whole history on every refresh.
    func startObserving(onReading: @escaping (DeviceReading) -> Void) throws {
        guard isAvailable else {
            throw DeviceException(error: .bluetoothUnavailable(
                detail: "HealthKit is not available on this device."))
        }

        for identifier in Self.readTypes {
            guard let type = HKQuantityType.quantityType(forIdentifier: identifier) else { continue }

            let handler: (HKAnchoredObjectQuery, [HKSample]?, [HKDeletedObject]?, HKQueryAnchor?, Error?) -> Void = {
                _, samples, _, _, _ in
                guard let quantities = samples as? [HKQuantitySample] else { return }
                for sample in quantities {
                    if let reading = Self.reading(from: sample, identifier: identifier) {
                        onReading(reading)
                    }
                }
            }

            let query = HKAnchoredObjectQuery(
                type: type,
                predicate: HKQuery.predicateForSamples(withStart: Date(), end: nil),
                anchor: nil,
                limit: HKObjectQueryNoLimit,
                resultsHandler: handler
            )
            query.updateHandler = handler
            store.execute(query)
            activeQueries.append(query)
        }
    }

    func stopObserving() {
        activeQueries.forEach { store.stop($0) }
        activeQueries.removeAll()
    }

    /// Per-type diagnostics for an empty read.
    ///
    /// `authorizationStatus` reports only what the app asked for, never whether
    /// the user granted it — iOS withholds that distinction deliberately. So
    /// `.sharingDenied` here means "we never requested it", which IS actionable,
    /// while `.sharingAuthorized` still tells us nothing about read access.
    /// Reported so an empty result can be attributed rather than guessed at.
    func diagnostics() -> [String: String] {
        guard isAvailable else { return ["healthKit": "unavailable on this device"] }
        var out: [String: String] = [:]
        for identifier in Self.readTypes {
            guard let type = HKQuantityType.quantityType(forIdentifier: identifier) else {
                out[identifier.rawValue] = "type unavailable"
                continue
            }
            out[identifier.rawValue] = switch store.authorizationStatus(for: type) {
            case .notDetermined: "not requested yet"
            case .sharingDenied: "request not granted for sharing"
            case .sharingAuthorized: "requested"
            @unknown default: "unknown"
            }
        }
        return out
    }

    /// Pulls stored history. This is where HealthKit genuinely outperforms the
    /// BLE path: the watch has been recording continuously, so a backfill
    /// returns real history rather than the empty list a standard GATT
    /// peripheral gives.
    func backfill(since: Date) async throws -> [DeviceReading] {
        guard isAvailable else { return [] }

        var collected: [DeviceReading] = []
        for identifier in Self.readTypes {
            guard let type = HKQuantityType.quantityType(forIdentifier: identifier) else { continue }
            // One type failing must not abort the rest: a watch may have heart
            // rate but no SpO2, and an error on the latter should not hide the
            // former. Per-type counts go back to the caller for the same reason.
            do {
                let samples = try await query(type: type, since: since)
                lastReadCounts[identifier.rawValue] = samples.count
                collected += samples.compactMap { Self.reading(from: $0, identifier: identifier) }
            } catch {
                lastReadCounts[identifier.rawValue] = -1
            }
        }
        return collected.sorted { $0.timestamp < $1.timestamp }
    }

    private func query(type: HKQuantityType, since: Date) async throws -> [HKQuantitySample] {
        try await withCheckedThrowingContinuation { continuation in
            let query = HKSampleQuery(
                sampleType: type,
                predicate: HKQuery.predicateForSamples(withStart: since, end: nil),
                limit: HKObjectQueryNoLimit,
                sortDescriptors: [NSSortDescriptor(key: HKSampleSortIdentifierStartDate,
                                                   ascending: true)]
            ) { _, samples, error in
                if let error = error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: (samples as? [HKQuantitySample]) ?? [])
                }
            }
            store.execute(query)
        }
    }

    /// Converts one HealthKit sample into the SDK's shared reading type.
    ///
    /// Units are requested explicitly rather than taken from the sample: a
    /// HealthKit quantity carries its own unit, and reading it in the wrong one
    /// yields a plausible but wrong number.
    private static func reading(
        from sample: HKQuantitySample,
        identifier: HKQuantityTypeIdentifier
    ) -> DeviceReading? {
        let timestamp = sample.startDate
        switch identifier {
        case .heartRate:
            let bpm = sample.quantity.doubleValue(
                for: HKUnit.count().unitDivided(by: .minute()))
            guard bpm > 0, bpm <= 300 else { return nil }
            return .heartRate(bpm: Int(bpm.rounded()), ibiMs: [], timestamp: timestamp)

        case .heartRateVariabilitySDNN:
            // Its own reading type: encoding SDNN as a zero-bpm heart rate made
            // HRV indistinguishable from a heart-rate sample downstream, so it
            // never showed up separately in a consuming app.
            let ms = sample.quantity.doubleValue(for: .secondUnit(with: .milli))
            guard ms > 0 else { return nil }
            return .heartRateVariability(sdnnMs: ms, timestamp: timestamp)

        case .oxygenSaturation:
            // HealthKit stores this as a 0..1 fraction; the SDK reports percent.
            let fraction = sample.quantity.doubleValue(for: .percent())
            guard fraction > 0, fraction <= 1.0 else { return nil }
            return .spO2(percent: fraction * 100.0, timestamp: timestamp)

        case .bodyTemperature:
            let celsius = sample.quantity.doubleValue(for: .degreeCelsius())
            return .temperature(celsius: celsius, timestamp: timestamp)

        default:
            return nil
        }
    }
    #else
    var isAvailable: Bool { false }
    var lastReadCounts: [String: Int] { [:] }
    func diagnostics() -> [String: String] { ["healthKit": "not compiled in"] }
    func requestAuthorization() async throws -> Bool { false }
    func startObserving(onReading: @escaping (DeviceReading) -> Void) throws {}
    func stopObserving() {}
    func backfill(since: Date) async throws -> [DeviceReading] { [] }
    #endif
}

private extension DeviceReading {
    var timestamp: Date {
        switch self {
        case .heartRate(_, _, let ts), .battery(_, _, let ts),
             .temperature(_, let ts), .spO2(_, let ts),
             .heartRateVariability(_, let ts):
            return ts
        }
    }
}
