import 'package:flutter_test/flutter_test.dart';
import 'package:lumora_ble/lumora_ble.dart';

void main() {
  group('DeviceReading decoding', () {
    test('decodes a glucose reading', () {
      final reading = DeviceReading.fromMap({
        'type': 'glucose',
        'timestamp': 1757548800000,
        'mgdl': 120,
        'trend': 'RISING_SLIGHTLY',
        'rateOfChange': 1.5,
        'source': 'DEXCOM_SENSOR',
        'isHistorical': false,
      }) as GlucoseReading;

      expect(reading.mgdl, 120);
      expect(reading.trend, GlucoseTrend.risingSlightly);
      expect(reading.source, DeviceKind.dexcomSensor);
      expect(reading.rateOfChange, 1.5);
      expect(reading.isHistorical, isFalse);
    });

    test('converts mg/dL to mmol/L', () {
      final reading = DeviceReading.fromMap({
        'type': 'glucose',
        'timestamp': 0,
        'mgdl': 180,
        'trend': 'FLAT',
        'source': 'LIBRE_SENSOR',
      }) as GlucoseReading;

      expect(reading.mmoll, closeTo(10.0, 0.01));
    });

    test('decodes heart rate with inter-beat intervals', () {
      final reading = DeviceReading.fromMap({
        'type': 'heartRate',
        'timestamp': 0,
        'bpm': 62,
        'ibiMs': [960, 970],
      }) as HeartRateReading;

      expect(reading.bpm, 62);
      expect(reading.ibiMs, [960, 970]);
    });

    test('falls back to unknown for an unrecognized enum name', () {
      final reading = DeviceReading.fromMap({
        'type': 'glucose',
        'timestamp': 0,
        'mgdl': 100,
        'trend': 'SOME_FUTURE_TREND',
        'source': 'OURA_RING',
      }) as GlucoseReading;

      expect(reading.trend, GlucoseTrend.unknown);
    });

    test('throws on an unknown reading type', () {
      expect(
        () => DeviceReading.fromMap({'type': 'mystery', 'timestamp': 0}),
        throwsA(isA<FormatException>()),
      );
    });
  });

  group('DeviceSupport', () {
    test('blocked devices are reported unusable with a reason', () {
      final support = DeviceSupport.fromMap({
        'kind': 'DEXCOM_SENSOR',
        'status': 'BLOCKED',
        'limitation': 'EC-J-PAKE not available',
      });

      expect(support.isUsable, isFalse);
      expect(support.limitation, contains('J-PAKE'));
    });

    test('decodes the transport so NFC devices are not shown as scannable', () {
      final libre = DeviceSupport.fromMap({
        'kind': 'LIBRE_SENSOR',
        'status': 'SUPPORTED',
        'transport': 'NFC',
      });

      expect(libre.transport, Transport.nfc);
      expect(libre.isUsable, isTrue);
    });

    test('defaults to BLE when no transport is sent', () {
      final support = DeviceSupport.fromMap({
        'kind': 'OURA_RING',
        'status': 'REQUIRES_SETUP',
      });

      expect(support.transport, Transport.ble);
    });

    test('requiresSetup devices remain usable', () {
      final support = DeviceSupport.fromMap({
        'kind': 'OURA_RING',
        'status': 'REQUIRES_SETUP',
        'prerequisite': 'factory reset required',
      });

      expect(support.isUsable, isTrue);
      expect(support.status, SupportStatus.requiresSetup);
    });

    test('an unknown status degrades to blocked rather than usable', () {
      final support = DeviceSupport.fromMap({
        'kind': 'OURA_RING',
        'status': 'SOMETHING_NEW',
      });

      expect(support.status, SupportStatus.blocked);
      expect(support.isUsable, isFalse);
    });

    test('multi-word kinds survive the SCREAMING_SNAKE_CASE round trip', () {
      // A name that fails to decode silently falls back to the first enum
      // value, which would show a heart rate strap as an Oura ring.
      final hr = DeviceSupport.fromMap({
        'kind': 'HEART_RATE_MONITOR',
        'status': 'REQUIRES_SETUP',
      });

      expect(hr.kind, DeviceKind.heartRateMonitor);
      expect(hr.isUsable, isTrue);
    });

    test('vendor-locked kinds decode and report as blocked', () {
      for (final entry in {
        'FITBIT_TRACKER': DeviceKind.fitbitTracker,
        'PIXEL_WATCH': DeviceKind.pixelWatch,
      }.entries) {
        final support = DeviceSupport.fromMap({
          'kind': entry.key,
          'status': 'BLOCKED',
          'limitation': 'use HEART_RATE_MONITOR for live heart rate',
        });

        expect(support.kind, entry.value);
        expect(support.isUsable, isFalse);
      }
    });
  });

  group('errors', () {
    test('flags pairing problems as user-actionable', () {
      const e = LumoraBleException(
          code: 'PairingRequired', message: 'factory reset the ring');

      expect(e.requiresUserAction, isTrue);
    });

    test('does not flag transport failures as user-actionable', () {
      const e = LumoraBleException(code: 'GattFailure', message: 'status 133');

      expect(e.requiresUserAction, isFalse);
    });
  });

  group('DeviceId', () {
    test('round-trips through the channel encoding', () {
      const id = DeviceId(address: 'AA:BB:CC:DD:EE:FF', kind: DeviceKind.ouraRing);

      expect(DeviceId.fromMap(id.toMap()), id);
    });
  });
}
