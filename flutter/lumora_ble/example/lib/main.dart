import 'dart:async';
import 'dart:io' show Platform;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lumora_ble/lumora_ble.dart';

void main() => runApp(const ExampleApp());

class ExampleApp extends StatelessWidget {
  const ExampleApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Lumora BLE',
        theme: ThemeData(useMaterial3: true),
        home: const HomePage(),
      );
}

/// Exercises whichever path the running platform actually supports.
///
/// The two platforms reach different devices by design — Android scans for BLE
/// peripherals, iOS reads the HealthKit store an Apple Watch syncs into — so
/// the UI branches rather than pretending to a parity that does not exist.
class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final _sdk = LumoraBle.instance;

  List<DeviceSupport> _support = const [];
  final _found = <DiscoveredDevice>[];
  final _log = <String>[];
  final _latest = <String, String>{};
  /// How many samples of each type have arrived. Without this the card shows
  /// one row per type and 200 readings look identical to one.
  final _counts = <String, int>{};

  StreamSubscription<DiscoveredDevice>? _scanSub;
  StreamSubscription<DeviceReading>? _readingSub;
  bool _scanning = false;
  bool _observing = false;

  bool get _isIos => Platform.isIOS;

  @override
  void initState() {
    super.initState();
    _loadSupport();
    _readingSub = _sdk.readings.listen(
      _onReading,
      onError: (Object e) => _say('reading stream error: ${_describe(e)}'),
    );
  }

  @override
  void dispose() {
    _scanSub?.cancel();
    _readingSub?.cancel();
    super.dispose();
  }

  Future<void> _loadSupport() async {
    try {
      final support = await _sdk.supportedDevices();
      setState(() => _support = support);
      final usable = support.where((s) => s.isUsable).map((s) => s.kind.name);
      _say('platform supports: ${usable.isEmpty ? "nothing" : usable.join(", ")}');
    } catch (e) {
      _say('supportedDevices failed: ${_describe(e)}');
    }
  }

  void _onReading(DeviceReading reading) {
    final entry = switch (reading) {
      HeartRateReading r => ('Heart rate', '${r.bpm} bpm'),
      HeartRateVariabilityReading r =>
        ('HRV (SDNN)', '${r.sdnnMs.toStringAsFixed(1)} ms'),
      SpO2Reading r => ('Blood oxygen', '${r.percent.toStringAsFixed(1)}%'),
      TemperatureReading r =>
        ('Body temperature', '${r.celsius.toStringAsFixed(2)} °C'),
      BatteryReading r => ('Battery', '${r.percent}%'),
      GlucoseReading r => ('Glucose', '${r.mgdl} mg/dL'),
      _ => ('Reading', reading.runtimeType.toString()),
    };
    setState(() {
      _counts.update(entry.$1, (n) => n + 1, ifAbsent: () => 1);
      _latest[entry.$1] = entry.$2;
    });
    _say('${entry.$1}: ${entry.$2}');
  }

  /// iOS: there is nothing to scan for, so connect() starts observing HealthKit.
  Future<void> _startHealthKit() async {
    _say('requesting Health authorization…');
    try {
      await _sdk.requestPermissions();
      await _sdk.connect(const DiscoveredDevice(
        id: DeviceId(address: 'healthkit', kind: DeviceKind.appleWatch),
        rssi: 0,
      ));
      setState(() => _observing = true);
      _say('observing HealthKit for new samples');
      // Observing alone can look broken: the watch syncs in batches, so nothing
      // arrives for minutes. Loading recent history immediately is what shows
      // the path actually works.
      await _backfill();
    } catch (e) {
      _say('connect failed: ${_describe(e)}');
    }
  }

  /// Asks the native side why a read came back empty.
  ///
  /// iOS-only and deliberately not part of the public SDK surface: it exists to
  /// make an empty HealthKit result diagnosable rather than mysterious.
  Future<void> _diagnose() async {
    try {
      const channel = MethodChannel('dev.lumora.ble/methods');
      final raw = await channel.invokeMethod<Map<Object?, Object?>>(
          'healthKitDiagnostics');
      if (raw == null) return;
      _say('healthkit available: ${raw["available"]}');
      final auth = raw['authorization'] as Map<Object?, Object?>?;
      auth?.forEach((k, v) => _say('  $k → $v'));
      final counts = raw['lastReadCounts'] as Map<Object?, Object?>?;
      if (counts != null && counts.isNotEmpty) {
        counts.forEach((k, v) => _say('  raw samples $k: $v'));
      }
    } catch (e) {
      _say('diagnostics unavailable: ${_describe(e)}');
    }
  }

  Future<void> _backfill({Duration window = const Duration(hours: 24)}) async {
    final since = DateTime.now().subtract(window);
    _say('reading the last ${window.inHours}h from HealthKit…');
    try {
      final readings = await _sdk.backfill(
        const DeviceId(address: 'healthkit', kind: DeviceKind.appleWatch),
        since,
      );
      // Per-type counts, because one row per label in the card above hides how
      // many samples actually arrived — and a type with zero is the useful
      // signal, not the ones that worked.
      final byType = <String, int>{};
      for (final r in readings) {
        byType.update(r.runtimeType.toString(), (n) => n + 1, ifAbsent: () => 1);
      }
      byType.forEach((type, n) => _say('  $type: $n samples'));
      await _diagnose();

      if (readings.isEmpty) {
        _say('backfill returned 0 samples');
        _say('iOS never reports denied read access, so this is not proof of a '
            'permission problem — check Health › Browse › Heart › Heart Rate. '
            'Data there but not here means the per-type toggles were left off.');
      } else {
        _say('backfill returned ${readings.length} samples');
        // Newest last from the query, so show the most recent values.
        for (final r in readings.reversed.take(8)) {
          _onReading(r);
        }
      }
    } catch (e) {
      _say('backfill failed: ${_describe(e)}');
    }
  }

  /// Android: the BLE path.
  Future<void> _toggleScan() async {
    if (_scanning) {
      await _scanSub?.cancel();
      setState(() => _scanning = false);
      _say('scan stopped');
      return;
    }
    if (!await _sdk.requestPermissions()) {
      _say('bluetooth permissions not granted');
      return;
    }
    setState(() {
      _scanning = true;
      _found.clear();
    });
    _say('scanning for standard heart rate (0x180D)…');
    _scanSub = _sdk.scan(DeviceKind.heartRateMonitor).listen(
      (d) {
        if (_found.any((e) => e.id.address == d.id.address)) return;
        setState(() => _found.add(d));
        _say('found ${d.name ?? "(unnamed)"} rssi=${d.rssi}');
      },
      onError: (Object e) {
        _say('scan failed: ${_describe(e)}');
        setState(() => _scanning = false);
      },
    );
  }

  Future<void> _connect(DiscoveredDevice device) async {
    if (_scanning) await _toggleScan();
    _say('connecting to ${device.name ?? device.id.address}…');
    try {
      await _sdk.connect(device);
      _say('connected');
    } catch (e) {
      _say('connect failed: ${_describe(e)}');
    }
  }

  String _describe(Object e) =>
      e is LumoraBleException ? '${e.code}: ${e.message}' : e.toString();

  void _say(String line) {
    final t = DateTime.now();
    final stamp = '${t.hour.toString().padLeft(2, '0')}:'
        '${t.minute.toString().padLeft(2, '0')}:'
        '${t.second.toString().padLeft(2, '0')}';
    setState(() => _log.insert(0, '$stamp  $line'));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_isIos ? 'Lumora — Apple Watch' : 'Lumora — BLE'),
      ),
      body: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SizedBox(height: 8),
            if (_isIos) ...[
              FilledButton(
                onPressed: _observing ? null : _startHealthKit,
                child: Text(_observing
                    ? 'Observing HealthKit'
                    : 'Grant Health access & observe'),
              ),
              const SizedBox(height: 8),
              OutlinedButton(
                onPressed: _backfill,
                child: const Text('Read last 24 hours'),
              ),
              const SizedBox(height: 8),
              // SpO2, HRV and body temperature are sampled on a schedule rather
              // than continuously, so a 24h window can legitimately contain
              // none. A week distinguishes "not granted" from "not measured".
              OutlinedButton(
                onPressed: () => _backfill(window: const Duration(days: 7)),
                child: const Text('Read last 7 days'),
              ),
            ] else
              FilledButton(
                onPressed: _toggleScan,
                child: Text(_scanning
                    ? 'Stop scan'
                    : 'Scan for heart rate (0x180D)'),
              ),
            const SizedBox(height: 12),
            if (_latest.isNotEmpty)
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      const Text('Live readings',
                          style: TextStyle(fontWeight: FontWeight.bold)),
                      const SizedBox(height: 4),
                      ..._latest.entries.map((e) => Row(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            children: [
                              Text('${e.key}  (${_counts[e.key] ?? 0})'),
                              Text(e.value,
                                  style: const TextStyle(
                                      fontFamily: 'monospace',
                                      fontWeight: FontWeight.bold)),
                            ],
                          )),
                    ],
                  ),
                ),
              ),
            for (final d in _found)
              Card(
                child: ListTile(
                  title: Text(d.name ?? '(unnamed)'),
                  subtitle: Text('${d.id.address}   rssi ${d.rssi}'),
                  trailing: FilledButton(
                    onPressed: () => _connect(d),
                    child: const Text('Connect'),
                  ),
                ),
              ),
            const SizedBox(height: 8),
            const Text('Log', style: TextStyle(fontWeight: FontWeight.bold)),
            Expanded(
              child: ListView.builder(
                itemCount: _log.length,
                itemBuilder: (_, i) => Text(
                  _log[i],
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 11),
                ),
              ),
            ),
            if (_support.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Text(
                  '${_support.where((s) => s.isUsable).length} of '
                  '${_support.length} kinds usable on this platform',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
          ],
        ),
      ),
    );
  }
}
