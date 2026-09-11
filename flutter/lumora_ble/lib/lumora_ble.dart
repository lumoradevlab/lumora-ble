/// Lumora BLE — one SDK for Oura, FreeStyle Libre and Dexcom.
///
/// The Dart API is a thin binding over the native implementation; no protocol
/// logic lives here, so Flutter and native consumers behave identically.
///
/// Check [LumoraBle.supportedDevices] before offering a device in your UI —
/// not every device is fully usable over BLE. See docs/PROTOCOL-STATUS.md.
library lumora_ble;

import 'dart:async';

import 'package:flutter/services.dart';

import 'src/models.dart';

export 'src/models.dart';

class LumoraBle {
  LumoraBle._();

  static final LumoraBle instance = LumoraBle._();

  static const MethodChannel _methods =
      MethodChannel('dev.lumora.ble/methods');
  static const EventChannel _readingEvents =
      EventChannel('dev.lumora.ble/readings');
  static const EventChannel _connectionEvents =
      EventChannel('dev.lumora.ble/connections');
  static const EventChannel _scanEvents = EventChannel('dev.lumora.ble/scan');

  Stream<DeviceReading>? _readings;
  Stream<Map<DeviceId, ConnectionState>>? _connections;

  /// Which devices this build supports, and what each one requires.
  Future<List<DeviceSupport>> supportedDevices() async {
    final raw = await _invoke<List<Object?>>('supportedDevices') ?? const [];
    return raw
        .map((e) => DeviceSupport.fromMap(e! as Map<Object?, Object?>))
        .toList();
  }

  /// Requests the runtime permissions BLE needs. Returns true if all granted.
  ///
  /// On Android 12+ these are BLUETOOTH_SCAN/BLUETOOTH_CONNECT; below that,
  /// ACCESS_FINE_LOCATION, without which scans silently return nothing.
  Future<bool> requestPermissions() async =>
      await _invoke<bool>('requestPermissions') ?? false;

  /// Supplies the Dexcom transmitter serial (6 characters, printed on the
  /// applicator) and the current sensor session start.
  ///
  /// For a G6 the serial IS the encryption credential, so this must be called
  /// before connecting to a Dexcom sensor.
  Future<void> setDexcomTransmitter(String serial, DateTime sessionStart) =>
      _invoke<void>('setDexcomTransmitter', {
        'serial': serial,
        'sessionStart': sessionStart.toUtc().millisecondsSinceEpoch,
      });

  /// Reads a Libre 1/2 sensor over NFC.
  ///
  /// Libre 1/2 are NFC devices, not BLE: the user holds the phone against the
  /// sensor and the whole reading history transfers in one tap. There is no
  /// connection to keep open, so this returns everything the sensor holds.
  ///
  /// Throws [LumoraBleException] if the tap fails or the sensor is expired.
  Future<List<DeviceReading>> readLibreSensor() async {
    final raw = await _invoke<List<Object?>>('readLibreSensor');
    return (raw ?? const [])
        .map((e) => DeviceReading.fromMap(e! as Map<Object?, Object?>))
        .toList();
  }

  /// Scans for devices of [kind]. Scanning stops when the subscription is
  /// cancelled — always cancel, or you will drain the user's battery.
  Stream<DiscoveredDevice> scan(DeviceKind kind) => _scanEvents
      .receiveBroadcastStream({'kind': kind.name})
      .map((e) => DiscoveredDevice.fromMap(e as Map<Object?, Object?>))
      .handleError(_rethrowAsLumora);

  /// Live readings from every connected device, merged.
  Stream<DeviceReading> get readings => _readings ??= _readingEvents
      .receiveBroadcastStream()
      .map((e) => DeviceReading.fromMap(e as Map<Object?, Object?>))
      .handleError(_rethrowAsLumora)
      .asBroadcastStream();

  /// Convenience filter for apps that only care about glucose.
  Stream<GlucoseReading> get glucoseReadings =>
      readings.where((r) => r is GlucoseReading).cast<GlucoseReading>();

  /// Connection state for every managed device.
  Stream<Map<DeviceId, ConnectionState>> get connections =>
      _connections ??= _connectionEvents
          .receiveBroadcastStream()
          .map((e) => (e as Map<Object?, Object?>).map((k, v) => MapEntry(
                DeviceId.fromMap(k! as Map<Object?, Object?>),
                ConnectionState.fromMap(v! as Map<Object?, Object?>),
              )))
          .handleError(_rethrowAsLumora)
          .asBroadcastStream();

  /// Connects and authenticates. Throws [LumoraBleException] on failure;
  /// check [LumoraBleException.requiresUserAction] to decide whether to show
  /// setup instructions rather than a generic error.
  Future<void> connect(DiscoveredDevice device) =>
      _invoke<void>('connect', device.id.toMap());

  Future<void> disconnect(DeviceId id) => _invoke<void>('disconnect', id.toMap());

  Future<void> disconnectAll() => _invoke<void>('disconnectAll');

  /// Pulls stored on-device history since [since].
  Future<List<DeviceReading>> backfill(DeviceId id, DateTime since) async {
    final raw = await _invoke<List<Object?>>('backfill', {
      ...id.toMap(),
      'since': since.toUtc().millisecondsSinceEpoch,
    });
    return (raw ?? const [])
        .map((e) => DeviceReading.fromMap(e! as Map<Object?, Object?>))
        .toList();
  }

  Future<T?> _invoke<T>(String method, [Map<String, Object?>? args]) async {
    try {
      return await _methods.invokeMethod<T>(method, args);
    } on PlatformException catch (e) {
      throw LumoraBleException(code: e.code, message: e.message ?? '');
    }
  }

  Never _rethrowAsLumora(Object error, StackTrace stack) {
    if (error is PlatformException) {
      Error.throwWithStackTrace(
        LumoraBleException(code: error.code, message: error.message ?? ''),
        stack,
      );
    }
    Error.throwWithStackTrace(error, stack);
  }
}
