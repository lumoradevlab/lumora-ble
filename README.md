# Lumora BLE

One SDK for connecting to health wearables — so an app integrates **once**
instead of learning three proprietary protocols. Covers BLE (Dexcom G6, Oura)
and NFC (FreeStyle Libre 1/2) behind a single API.

Usable from **native Android (Kotlin/Java)** and **Flutter**, over a single
native implementation. The Flutter plugin is a thin binding with no protocol
logic of its own, so both consumers get identical behaviour.

> **Read [docs/PROTOCOL-STATUS.md](docs/PROTOCOL-STATUS.md) before planning
> around this.** Not every generation of every device is supported, and the
> reasons are legal and cryptographic rather than technical. The summary is below and the SDK reports
> it at runtime.

## Device support

**Generation matters more than brand.** For both CGMs the older generation is
supportable and the current one is not.

| Device | Transport | Status | What it means |
|---|---|---|---|
| Dexcom **G6** | BLE | Works | Needs the 6-character transmitter serial. Android bonds on first connect. |
| Dexcom **G7 / ONE+** | BLE | Not supported | EC-J-PAKE. Rejected with a clear error, never a hang. |
| Libre **1 / 2** | **NFC** | Works | Tap to read. Uncalibrated — see the caveat below. |
| Libre **3** | BLE | Not supported | Needs an Abbott-issued certificate that cannot be synthesized. |
| Oura Ring 3/4/5 | BLE | Works, with setup | The ring must be factory-reset, giving up the official Oura app for it. |

Two things integrators must not miss: **Libre 1/2 are NFC, not BLE** — they are
tapped, never scanned — and their readings are **uncalibrated**, so they can
drift from what the vendor app shows.

The SDK reports this at runtime, so your UI can disable a device rather than
failing at connect time:

```kotlin
sdk.supportedDevices
    .filter { it.status != SupportStatus.BLOCKED }
    .forEach { showInPicker(it.kind) }
```

**These protocols are unofficial.** No vendor publishes a BLE spec; every
constant here comes from public reverse-engineering work. Using them violates
each vendor's terms of service, and any firmware update can break them without
notice. That is a product and legal decision — make it deliberately.

## Install

**Android**

```kotlin
dependencies {
    implementation("dev.lumora.ble:sdk:0.1.0")
}
```

**Flutter**

```yaml
dependencies:
  lumora_ble: ^0.1.0
```

## Use it

Kotlin:

```kotlin
val sdk = LumoraBle.create(context)

// A G6's encryption key is derived from its serial, so set it before connecting.
sdk.setDexcomTransmitter("8UMS7E", sessionStart = Instant.now())

sdk.scan(DeviceKind.DEXCOM_SENSOR).collect { device ->
    sdk.connect(device)
}

sdk.readings.collect { reading ->
    when (reading) {
        is DeviceReading.Glucose -> show(reading.reading.mgdl)
        is DeviceReading.HeartRate -> show(reading.sample.bpm)
        else -> Unit
    }
}
```

Dart:

```dart
final sdk = LumoraBle.instance;

await sdk.requestPermissions();
await sdk.setDexcomTransmitter('8UMS7E', DateTime.now());

final sub = sdk.scan(DeviceKind.dexcomSensor).listen((device) async {
  await sdk.connect(device);
});

sdk.glucoseReadings.listen((r) => print('${r.mgdl} mg/dL ${r.trend.name}'));

// Cancelling the subscription is what stops the radio.
await sub.cancel();
```

Handle the expected failures rather than treating them as crashes:

```dart
try {
  await sdk.connect(device);
} on LumoraBleException catch (e) {
  if (e.requiresUserAction) showSetupInstructions(e.message);
}
```

## Layout

```
core/         Domain model + the LumoraBle interface. No Android BLE types.
transport/    GATT plumbing: serialized op queue, scanner, permissions.
devices/
  oura/       Nonce/AES-ECB auth, commands, event parsing.
  libre/      Libre 1/2 FRAM parser + NFC reader. (NFC, not BLE.)
  dexcom/     G6 mutual auth, message codecs, glucose decoding.
sdk/          Wires it together; encrypted credential storage.
flutter/      Flutter plugin + example app.
```

`core` deliberately has no Android Bluetooth imports, so the domain types and the
`LumoraBle` contract stay unit-testable on the JVM.

### Why the transport layer looks the way it does

Android's BLE stack has sharp edges that cause most real-world bugs:

- **One GATT operation at a time.** A second call before the first callback is
  silently dropped. `GattQueue` serializes them.
- **`setCharacteristicNotification` is not enough.** It flips a local flag; the
  device sends nothing until the CCCD descriptor is written.
- **GATT clients leak.** An app gets ~32; connecting per scan result exhausts
  them and yields permanent `status 133`. `GattConnection.close()` is idempotent
  and runs on every exit path.
- **Scanning must stop.** Cancelling the scan flow stops the radio.

## Security

Device credentials are sealed with an AES-GCM key in the Android Keystore
(`EncryptedCredentialStore`). The wrapping key is non-exportable and hardware-backed
where a TEE is available. These are keys to a user's health data — do not
substitute plain `SharedPreferences`.

## Build

```bash
./gradlew test                                    # native, 64 tests
cd flutter/lumora_ble && flutter test             # Dart, 13 tests
```

## Not implemented

- **iOS.** The Dart API is platform-neutral, but only Android has a native
  implementation. The iOS platform is intentionally left out of `pubspec.yaml`
  so it fails with a clear "unsupported platform" instead of a missing symbol.
- **Cloud fallbacks.** For most products the vendor APIs (Oura Cloud v2,
  LibreLinkUp, Dexcom v3) are the right integration; they are not in this repo
  yet. Note the Dexcom API delays data 1h (US) / 3h (elsewhere) by regulatory
  design, so it cannot drive live alerts.
- **Dexcom backfill.** The G6 replays stored readings, but that characteristic
  has its own chunked framing that needs a real transmitter to validate. Live
  readings work; history does not.
- **Libre calibration.** The FRAM path is uncalibrated; sensors carry per-unit
  parameters the official algorithm applies on top.

## Licence

Apache 2.0. See [LICENSE](LICENSE).

Not affiliated with or endorsed by Ōura Health, Abbott, or Dexcom. Not a medical
device: do not use it for diagnosis or treatment decisions.
