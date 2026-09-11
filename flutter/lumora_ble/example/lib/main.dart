import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lumora_ble/lumora_ble.dart';

void main() => runApp(const ExampleApp());

class ExampleApp extends StatelessWidget {
  const ExampleApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Lumora BLE',
        theme: ThemeData(useMaterial3: true),
        home: const DeviceListPage(),
      );
}

class DeviceListPage extends StatefulWidget {
  const DeviceListPage({super.key});

  @override
  State<DeviceListPage> createState() => _DeviceListPageState();
}

class _DeviceListPageState extends State<DeviceListPage> {
  final _sdk = LumoraBle.instance;
  List<DeviceSupport> _support = const [];
  final _found = <DiscoveredDevice>[];
  StreamSubscription<DiscoveredDevice>? _scanSub;
  DeviceReading? _latest;

  @override
  void initState() {
    super.initState();
    _load();
    // Readings from every connected device arrive on one stream.
    _sdk.readings.listen((r) => setState(() => _latest = r));
  }

  Future<void> _load() async {
    final support = await _sdk.supportedDevices();
    setState(() => _support = support);
  }

  Future<void> _start(DeviceSupport support) async {
    // NFC devices are tapped, not scanned — a scan would find nothing.
    if (support.transport == Transport.nfc) {
      _toast(support.prerequisite ?? 'Hold your phone against the sensor');
      return;
    }
    // A G6's key is derived from its serial, so it must be set before connecting.
    if (support.kind == DeviceKind.dexcomSensor) {
      await _sdk.setDexcomTransmitter('8UMS7E', DateTime.now());
    }
    await _scan(support.kind);
  }

  Future<void> _scan(DeviceKind kind) async {
    if (!await _sdk.requestPermissions()) {
      _toast('Bluetooth permissions are required');
      return;
    }
    setState(_found.clear);
    await _scanSub?.cancel();
    // Always cancel the subscription — that is what stops the radio.
    _scanSub = _sdk.scan(kind).listen(
          (d) => setState(() => _found.add(d)),
          onError: (Object e) => _toast('$e'),
        );
  }

  Future<void> _connect(DiscoveredDevice device) async {
    try {
      await _sdk.connect(device);
      _toast('Connected to ${device.name ?? device.id.address}');
    } on LumoraBleException catch (e) {
      // A blocked device or a missing prerequisite is expected, not a crash.
      _toast(e.requiresUserAction ? e.message : 'Failed: ${e.message}');
    }
  }

  void _toast(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  void dispose() {
    _scanSub?.cancel();
    _sdk.disconnectAll();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('Lumora BLE')),
        body: ListView(
          children: [
            for (final s in _support)
              ListTile(
                title: Text(s.kind.name),
                subtitle: Text(s.limitation ?? s.prerequisite ?? 'Ready'),
                trailing: FilledButton(
                  // Blocked devices are disabled rather than failing at connect.
                  onPressed: s.isUsable ? () => _start(s) : null,
                  child: Text(!s.isUsable
                      ? 'Blocked'
                      : s.transport == Transport.nfc
                          ? 'Tap'
                          : 'Scan'),
                ),
              ),
            const Divider(),
            for (final d in _found)
              ListTile(
                title: Text(d.name ?? d.id.address),
                subtitle: Text('RSSI ${d.rssi}'),
                onTap: () => _connect(d),
              ),
            if (_latest != null)
              ListTile(
                title: const Text('Latest reading'),
                subtitle: Text(_describe(_latest!)),
              ),
          ],
        ),
      );

  String _describe(DeviceReading r) => switch (r) {
        GlucoseReading(:final mgdl, :final trend) => '$mgdl mg/dL (${trend.name})',
        HeartRateReading(:final bpm) => '$bpm bpm',
        BatteryReading(:final percent) => 'battery $percent%',
        TemperatureReading(:final celsius) => '$celsius C',
        SpO2Reading(:final percent) => 'SpO2 $percent%',
      };
}
