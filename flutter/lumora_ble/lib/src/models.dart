/// Dart mirrors of the native `core` model types.
///
/// These must stay in sync with `core/src/main/java/dev/lumora/ble/core/Model.kt`.
/// Every enum is decoded by name, never by ordinal, so adding a native case
/// cannot silently shift the meaning of an existing one.
library;

enum DeviceKind {
  ouraRing,
  libreSensor,
  dexcomSensor,

  /// Any peripheral speaking the SIG standard GATT profiles (Heart Rate
  /// 0x180D, Battery 0x180F, Health Thermometer 0x1809, Pulse Oximeter
  /// 0x1822).
  ///
  /// Not a vendor: one kind covers Fitbit Charge 6, Fitbit Air, Pixel Watch
  /// 2+, Polar, Wahoo, Garmin straps and generic oximeters.
  heartRateMonitor,

  /// A Fitbit tracker's own sync protocol. Blocked — check [SupportStatus]
  /// before offering it. Live heart rate is reachable via [heartRateMonitor].
  fitbitTracker,

  /// A Pixel Watch as a full companion device. Blocked — it is a Wear OS
  /// device with no GATT surface. Live heart rate via [heartRateMonitor].
  pixelWatch,
}

enum SupportStatus { supported, requiresSetup, blocked }

/// How a device is reached. Not every supported device is BLE — Libre 1/2 are
/// NFC — and the difference changes the UX: NFC is a deliberate tap-to-scan,
/// BLE is a background connection.
enum Transport { ble, nfc }

enum GlucoseTrend {
  risingRapidly,
  rising,
  risingSlightly,
  flat,
  fallingSlightly,
  falling,
  fallingRapidly,
  unknown,
}

/// Maps SCREAMING_SNAKE_CASE from the platform channel to a Dart enum.
T _decode<T extends Enum>(List<T> values, String? wire, T fallback) {
  if (wire == null) return fallback;
  final normalized = wire.replaceAll('_', '').toLowerCase();
  for (final v in values) {
    if (v.name.toLowerCase() == normalized) return v;
  }
  return fallback;
}

class DeviceId {
  const DeviceId({required this.address, required this.kind});

  final String address;
  final DeviceKind kind;

  factory DeviceId.fromMap(Map<Object?, Object?> map) => DeviceId(
        address: map['address'] as String,
        kind: _decode(DeviceKind.values, map['kind'] as String?,
            DeviceKind.ouraRing),
      );

  Map<String, Object?> toMap() => {'address': address, 'kind': kind.name};

  @override
  bool operator ==(Object other) =>
      other is DeviceId && other.address == address && other.kind == kind;

  @override
  int get hashCode => Object.hash(address, kind);
}

class DiscoveredDevice {
  const DiscoveredDevice({
    required this.id,
    this.name,
    required this.rssi,
    this.serial,
  });

  final DeviceId id;
  final String? name;
  final int rssi;
  final String? serial;

  factory DiscoveredDevice.fromMap(Map<Object?, Object?> map) =>
      DiscoveredDevice(
        id: DeviceId.fromMap(map['id']! as Map<Object?, Object?>),
        name: map['name'] as String?,
        rssi: (map['rssi'] as num?)?.toInt() ?? 0,
        serial: map['serial'] as String?,
      );
}

/// What a device can actually do in this build. Check before showing it in a UI.
class DeviceSupport {
  const DeviceSupport({
    required this.kind,
    required this.status,
    this.prerequisite,
    this.limitation,
    this.transport = Transport.ble,
  });

  final DeviceKind kind;
  final SupportStatus status;

  /// BLE devices are scanned and connected; NFC devices are tapped.
  final Transport transport;

  /// A user step required before BLE works (NFC activation, factory reset).
  final String? prerequisite;

  /// Why the device is limited, when it is.
  final String? limitation;

  bool get isUsable => status != SupportStatus.blocked;

  factory DeviceSupport.fromMap(Map<Object?, Object?> map) => DeviceSupport(
        kind: _decode(
            DeviceKind.values, map['kind'] as String?, DeviceKind.ouraRing),
        status: _decode(SupportStatus.values, map['status'] as String?,
            SupportStatus.blocked),
        prerequisite: map['prerequisite'] as String?,
        limitation: map['limitation'] as String?,
        transport:
            _decode(Transport.values, map['transport'] as String?, Transport.ble),
      );
}

/// Base class for everything a device emits.
sealed class DeviceReading {
  const DeviceReading(this.timestamp);

  final DateTime timestamp;

  static DeviceReading fromMap(Map<Object?, Object?> map) {
    final ts = DateTime.fromMillisecondsSinceEpoch(
      (map['timestamp'] as num).toInt(),
      isUtc: true,
    );
    return switch (map['type'] as String?) {
      'glucose' => GlucoseReading(
          timestamp: ts,
          mgdl: (map['mgdl'] as num).toInt(),
          trend: _decode(GlucoseTrend.values, map['trend'] as String?,
              GlucoseTrend.unknown),
          rateOfChange: (map['rateOfChange'] as num?)?.toDouble(),
          source: _decode(
              DeviceKind.values, map['source'] as String?, DeviceKind.libreSensor),
          isHistorical: map['isHistorical'] as bool? ?? false,
        ),
      'heartRate' => HeartRateReading(
          timestamp: ts,
          bpm: (map['bpm'] as num).toInt(),
          ibiMs: ((map['ibiMs'] as List<Object?>?) ?? const [])
              .map((e) => (e as num).toInt())
              .toList(),
        ),
      'battery' => BatteryReading(
          timestamp: ts,
          percent: (map['percent'] as num).toInt(),
          charging: map['charging'] as bool? ?? false,
        ),
      'temperature' => TemperatureReading(
          timestamp: ts,
          celsius: (map['celsius'] as num).toDouble(),
        ),
      'spo2' => SpO2Reading(
          timestamp: ts,
          percent: (map['percent'] as num).toDouble(),
        ),
      final other => throw FormatException('unknown reading type: $other'),
    };
  }
}

class GlucoseReading extends DeviceReading {
  const GlucoseReading({
    required DateTime timestamp,
    required this.mgdl,
    required this.trend,
    this.rateOfChange,
    required this.source,
    this.isHistorical = false,
  }) : super(timestamp);

  /// Always mg/dL. Convert to mmol/L at the UI edge with [mmoll].
  final int mgdl;
  final GlucoseTrend trend;

  /// mg/dL per minute, when the sensor reports it.
  final double? rateOfChange;
  final DeviceKind source;
  final bool isHistorical;

  double get mmoll => mgdl / 18.0;
}

class HeartRateReading extends DeviceReading {
  const HeartRateReading({
    required DateTime timestamp,
    required this.bpm,
    this.ibiMs = const [],
  }) : super(timestamp);

  final int bpm;

  /// Inter-beat intervals in milliseconds, when available.
  final List<int> ibiMs;
}

class BatteryReading extends DeviceReading {
  const BatteryReading({
    required DateTime timestamp,
    required this.percent,
    this.charging = false,
  }) : super(timestamp);

  final int percent;
  final bool charging;
}

class TemperatureReading extends DeviceReading {
  const TemperatureReading({
    required DateTime timestamp,
    required this.celsius,
  }) : super(timestamp);

  final double celsius;
}

class SpO2Reading extends DeviceReading {
  const SpO2Reading({required DateTime timestamp, required this.percent})
      : super(timestamp);

  final double percent;
}

/// Connection lifecycle, mirroring the native sealed interface.
enum ConnectionStatus {
  disconnected,
  scanning,
  connecting,
  authenticating,
  ready,
  failed,
}

class ConnectionState {
  const ConnectionState({required this.status, this.error});

  final ConnectionStatus status;
  final LumoraBleException? error;

  bool get isReady => status == ConnectionStatus.ready;

  factory ConnectionState.fromMap(Map<Object?, Object?> map) => ConnectionState(
        status: _decode(ConnectionStatus.values, map['status'] as String?,
            ConnectionStatus.disconnected),
        error: map['error'] == null
            ? null
            : LumoraBleException.fromMap(map['error']! as Map<Object?, Object?>),
      );
}

/// Typed failures. [code] matches the native `DeviceError` subclass name.
class LumoraBleException implements Exception {
  const LumoraBleException({required this.code, required this.message});

  final String code;
  final String message;

  /// The device needs an out-of-band step (factory reset, NFC activation) or a
  /// credential the SDK cannot obtain. Surface [message] to the user directly.
  bool get requiresUserAction => code == 'PairingRequired';

  factory LumoraBleException.fromMap(Map<Object?, Object?> map) =>
      LumoraBleException(
        code: map['code'] as String? ?? 'Unknown',
        message: map['message'] as String? ?? '',
      );

  @override
  String toString() => 'LumoraBleException($code): $message';
}
