# lumora_ble

One API for health wearables, so an app integrates once instead of learning a
protocol per device.

> **The platforms are not symmetric, and that is not a bug.** Android talks to
> BLE and NFC devices directly. iOS reads Apple Watch data from HealthKit,
> because watchOS exposes no BLE service for health data at all. A Flutter
> plugin usually promises identical behaviour on both platforms; this one
> cannot, so it reports its real capabilities at runtime and your UI must ask.

## Device support by platform

| Device | Android | iOS |
|---|---|---|
| Standard BLE heart rate (Fitbit Charge 6, Pixel Watch 2+, Polar, Wahoo, chest straps) | ✅ | ❌ |
| Oura Ring 3/4/5 | ✅ setup required | ❌ |
| Dexcom G6 | ✅ setup required | ❌ |
| FreeStyle Libre 1/2 | ✅ NFC tap | ❌ |
| **Apple Watch** | ❌ | ✅ HealthKit |

iOS is HealthKit-only: the BLE and NFC protocols are implemented natively on
Android and would each need reimplementing over CoreBluetooth/CoreNFC. An
Apple Watch cannot pair with Android at all, so the gap runs both ways.

## Build an adaptive UI

**Never hardcode a device list.** Ask the SDK what this platform can reach:

```dart
class DevicePicker extends StatefulWidget {
  const DevicePicker({super.key});
  @override
  State<DevicePicker> createState() => _DevicePickerState();
}

class _DevicePickerState extends State<DevicePicker> {
  List<DeviceSupport> _support = const [];

  @override
  void initState() {
    super.initState();
    LumoraBle.instance.supportedDevices().then(
      (s) => setState(() => _support = s),
    );
  }

  @override
  Widget build(BuildContext context) {
    // isUsable is false for BLOCKED devices — on iOS that is every BLE
    // protocol, on Android it is Apple Watch. Filtering here is what stops
    // a user picking a device that cannot work on their phone.
    final usable = _support.where((s) => s.isUsable).toList();

    if (usable.isEmpty) {
      return const Text('No supported devices on this platform.');
    }

    return ListView(
      children: [
        for (final device in usable)
          ListTile(
            title: Text(_label(device.kind)),
            // prerequisite is the out-of-band step the user must do first:
            // factory-resetting an Oura ring, starting heart rate sharing on
            // a Pixel Watch, granting Health access on iOS.
            subtitle: device.prerequisite != null
                ? Text(device.prerequisite!)
                : null,
            trailing: device.transport == Transport.healthKit
                ? const Icon(Icons.favorite)   // nothing to scan for
                : const Icon(Icons.bluetooth_searching),
            onTap: () => _start(device),
          ),
      ],
    );
  }

  /// The transport decides the interaction, not the platform.
  void _start(DeviceSupport device) {
    switch (device.transport) {
      case Transport.healthKit:
        // No scan: connect() begins observing the Health store.
        LumoraBle.instance.connect(const DiscoveredDevice(
          id: DeviceId(address: 'healthkit', kind: DeviceKind.appleWatch),
          rssi: 0,
        ));
      case Transport.nfc:
        // Libre is tapped, never scanned. Hand the tag from your NFC
        // callback to readLibreTag().
        _showTapInstructions();
      case Transport.ble:
        _scanFor(device.kind);
    }
  }
}
```

Three flags drive the whole UI:

- **`isUsable`** — false for anything BLOCKED on this platform. Filter on it
  rather than branching on `Platform.isIOS`, so a device becoming supported
  needs no app change.
- **`transport`** — `ble` scans, `nfc` is a deliberate tap, `healthKit` has
  nothing to discover. This changes the screen, not just a label.
- **`prerequisite`** — the out-of-band step to show before the user tries.

## Calls that are platform-specific

| Call | Android | iOS |
|---|---|---|
| `scan()` | Works | **Throws** — HealthKit exposes no devices to discover |
| `connect()` | Opens a BLE link | Starts observing HealthKit |
| `backfill()` | Empty for standard profiles | **Returns real history** |
| `readLibreSensor()` | Works | Throws |

Handle the difference rather than assuming:

```dart
try {
  await for (final device in LumoraBle.instance.scan(kind)) {
    // ...
  }
} on LumoraBleException catch (e) {
  if (e.requiresUserAction) showSetupInstructions(e.message);
}
```

## iOS setup

Two host-app steps the plugin cannot do for you. **Without the first, iOS
terminates the process rather than throwing an error.**

```xml
<!-- ios/Runner/Info.plist -->
<key>NSHealthShareUsageDescription</key>
<string>Reads heart rate and related measurements from your Apple Watch.</string>
<key>NSHealthUpdateUsageDescription</key>
<string>This app does not write health data.</string>
```

Then in Xcode: **Signing & Capabilities → + Capability → HealthKit**.

One behaviour worth designing around: **iOS never reports that read access was
denied.** A denied data type is indistinguishable from one holding no data, by
design, so an empty result is never proof of a permission problem — do not
tell the user their permissions are wrong on that basis.

## Android setup

`minSdk 26`. The plugin declares the Bluetooth permissions; your app requests
them at runtime.

The plugin's Android side resolves `dev.lumora.ble:*` from a Maven repository.
Until those are on Maven Central, add one of these to
`android/build.gradle.kts`:

```kotlin
allprojects {
    repositories {
        google()
        mavenCentral()

        // Either: built locally with ./gradlew publishToMavenLocal
        mavenLocal()

        // Or: GitHub Packages. Note this needs a personal access token with
        // read:packages even though the repository is public.
        maven {
            url = uri("https://maven.pkg.github.com/lumoradevlab/lumora-ble")
            credentials {
                username = providers.gradleProperty("githubUser").get()
                password = providers.gradleProperty("githubToken").get()
            }
        }
    }
}
```

**Do not add a Gradle composite build for this plugin.** `includeBuild` pulls
the SDK's whole build into yours, and Gradle refuses two Android Gradle Plugin
versions in one build — your app's AGP and Kotlin would be pinned to the SDK's.
This is the most common way a Flutter plugin integration breaks. Resolving
real artifacts avoids it entirely.

## These protocols are unofficial

Every vendor protocol here is reverse-engineered. Using them violates each
vendor's terms of service, and a firmware update can break them without
notice. Unlike the native Android integration — where protocols are opt-in
Gradle dependencies — the Flutter plugin bundles all of them, because a Dart
consumer cannot express a per-protocol dependency. **Integrating this plugin
means accepting Dexcom's, Ōura's and Abbott's terms on your users' behalf.**
Only the standard SIG heart rate profile and the HealthKit path are free of
that exposure.

Not a medical device. Do not use it for diagnosis or treatment decisions.

## Status

`0.1.0-alpha.1`. The iOS HealthKit path and the Android scan/connect path are
verified on real hardware; heart rate parsing and the three vendor protocols
are not. See [docs/PROTOCOL-STATUS.md](https://github.com/lumoradevlab/lumora-ble/blob/main/docs/PROTOCOL-STATUS.md).

## Licence

Apache 2.0. Not affiliated with or endorsed by Ōura Health, Abbott, Dexcom,
Google, or Apple.
