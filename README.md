# Lumora BLE

One SDK for connecting to health wearables — so an app integrates **once**
instead of learning three proprietary protocols. Covers BLE (Dexcom G6, Oura)
and NFC (FreeStyle Libre 1/2) behind a single API.

Usable from **native Android (Kotlin/Java)** and **Flutter**. The Flutter plugin
is a thin binding with no protocol logic of its own.

**The two platforms reach different devices, deliberately.** Android implements
the BLE and NFC protocols; iOS reads Apple Watch data from **HealthKit**, which
is the only route watchOS offers. `supportedDevices()` reports each platform's
real capabilities at runtime — check it rather than assuming parity.

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
| **Standard heart rate** | BLE | Works, with setup | Any SIG-conforming peripheral: Fitbit Charge 6 / Air, Pixel Watch 2+, Polar, Wahoo, Garmin straps. Live values only. |
| Fitbit **sync protocol** | BLE | Not supported | Encrypted under a per-device key provisioned via Fitbit's cloud. Use standard heart rate instead. |
| Pixel Watch **companion** | BLE | Not supported | Wear OS device with no GATT surface. Use standard heart rate instead. |
| **Apple Watch** | **HealthKit** | Works on **iOS only** | Read from the Health store, not over a radio. Android cannot reach it by any route. |

### The standard-profile path

One entry in that table is unlike the others. `HEART_RATE_MONITOR` is built on
**published Bluetooth SIG profiles** — Heart Rate `0x180D`, Battery `0x180F`,
Health Thermometer `0x1809`, Pulse Oximeter `0x1822` — not on
reverse-engineering. So it covers every conforming peripheral with one
implementation, carries no vendor-ToS exposure, and cannot be broken by a
firmware update.

It is also how Fitbit and Pixel Watch are actually reachable. Their own sync
protocols are closed, but **Fitbit Charge 6, Fitbit Air, and Pixel Watch 2+
broadcast live heart rate over the standard profile** — the same mechanism they
use to talk to Peloton, Zwift and Strava.

Two constraints worth designing around:

- **Broadcast is user-initiated and session-scoped.** On the watch: swipe down →
  Connected Fitness → Connect (a Pixel Watch may also need *Extended Pairing*).
  It is a workout-time broadcast, not a background connection.
- **Concurrent links are scarce.** A Charge 6 accepts **one**; a Pixel Watch 3+
  accepts **two**. A watch already connected to gym equipment will refuse.

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

**The vendor protocols are unofficial.** No vendor publishes a BLE spec for its
own devices; every constant in the `oura`, `libre` and `dexcom` modules comes
from public reverse-engineering work. Using them violates each vendor's terms of
service, and any firmware update can break them without notice. That is a
product and legal decision — make it deliberately. The `standard` module is the
exception: it implements published SIG profiles and carries none of that risk.

## Install

**Android**

**Protocols are opt-in.** Depend on the ones you want, and nothing else ships:

```kotlin
dependencies {
    implementation("dev.lumora.ble:sdk:0.1.0-alpha01")

    // Published Bluetooth SIG profiles. No vendor terms attached.
    implementation("dev.lumora.ble:standard:0.1.0-alpha01")

    // Vendor protocols are reverse-engineered and unofficial: adding one
    // means accepting that vendor's terms of service on your users' behalf.
    // implementation("dev.lumora.ble:dexcom:0.1.0-alpha01")
    // implementation("dev.lumora.ble:oura:0.1.0-alpha01")
    // implementation("dev.lumora.ble:libre:0.1.0-alpha01")
}
```

### Where the artifacts live

Not on Maven Central yet. Until then there are two options, and the trade-off
is worth stating plainly.

**Build it locally** — no accounts, no tokens:

```bash
git clone https://github.com/lumoradevlab/BLE-Android
cd BLE-Android && ./gradlew publishToMavenLocal
```

then add `mavenLocal()` to your repositories. This is the simplest route for
trying the SDK, and what the CI for this repository does.

**GitHub Packages** — for a team that wants a shared remote. Published by
`.github/workflows/publish.yml` on a GitHub release, or manually via
`workflow_dispatch`; consumers add:

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/lumoradevlab/BLE-Android")
        credentials {
            // Never commit these. Put them in ~/.gradle/gradle.properties.
            username = providers.gradleProperty("githubUser").get()
            password = providers.gradleProperty("githubToken").get()
        }
    }
}
```

**The catch:** GitHub Packages requires authentication even to *read* a public
package, so every developer on your team needs a personal access token with
`read:packages`, and so does your CI. That is why it is an interim step rather
than the destination — Maven Central needs no token at all.

What neither option requires is a Gradle composite build. `includeBuild` pulls
this repository's whole build into yours, and Gradle refuses two Android
Gradle Plugin versions in one build, so it would pin your app's toolchain to
ours. Resolving real artifacts — from `mavenLocal()` or GitHub Packages —
keeps your AGP and Kotlin versions yours; verified against AGP 9.0.1 / Kotlin
2.3.20 while this repository builds with 8.7.3 / 2.1.0.

This is a legal boundary as much as a packaging one. Bundling every protocol
would impose Dexcom's, Ōura's and Abbott's terms on an app that only wanted a
standard heart rate strap, so `:sdk` depends on no device module on any
configuration.

The uninstalled protocols are **absent, not merely unreachable**. A release
APK built against only `:standard` contains no `dev/lumora/ble/dexcom`,
`oura` or `libre` classes at all — verified by dumping the DEX — so the
opt-in saves code size as well as legal exposure.

The SDK owns a coroutine scope, so scope it to something and close it:

```kotlin
class HealthViewModel(app: Application) : AndroidViewModel(app) {
    private val sdk = LumoraBle.create(app) { install(StandardGattProtocol) }

    override fun onCleared() = sdk.close()
}
```

`close()` is synchronous and thread-safe, and `LumoraBle` is `Closeable`, so
it fits a teardown callback directly. That is deliberate: launching teardown
into a coroutine races the scope's own cancellation, and a lost race leaks a
GATT client interface — Android caps an app at roughly 32, and a leaked one
stays leaked until the process restarts.

**Flutter**

```yaml
dependencies:
  lumora_ble: ^0.1.0
```

The Flutter plugin resolves the Android SDK from Maven like any other
dependency, so your app keeps its own AGP and Kotlin versions. Do not add a
composite build for it: `includeBuild` pulls this repository's Gradle build
into yours, and Gradle refuses two Android Gradle Plugin versions in one
build — which would pin your app's toolchain to ours. The composite build in
`flutter/lumora_ble/example` exists only because that app lives inside this
repository.

Unlike the native integration, the Flutter distribution bundles every
protocol: a Dart consumer cannot express a per-protocol Gradle dependency, so
the vendor terms-of-service note above applies to any Flutter integration.

## Use it

Kotlin:

```kotlin
val sdk = LumoraBle.create(context) {
    install(StandardGattProtocol)
    // A G6's encryption key derives from its serial, so the protocol cannot
    // be built without one — it is a constructor argument, not a later setter.
    install(DexcomProtocolFactory("8UMS7E", sessionStart = Instant.now()))
}

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
  standard/   SIG standard GATT profiles. No auth, no vendor protocol.
sdk/          Wires it together; encrypted credential storage.
flutter/      Flutter plugin + example app.
  ios/        Swift/HealthKit implementation for Apple Watch. See ios/README.md.
  example/    Flutter app exercising whichever path the platform supports.
testapp/      Native Android harness for the standard GATT path.
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
  and runs on every exit path. It is also synchronous and lock-guarded: the
  `BluetoothGatt` reference is held under a `ReentrantLock` for the whole of
  each use, so a `close()` arriving on another thread cannot release the client
  between the operation pump reading the field and calling into it.
- **Scanning must stop.** Cancelling the scan flow stops the radio.

## Security

Device credentials are sealed with an AES-GCM key in the Android Keystore
(`EncryptedCredentialStore`). The wrapping key is non-exportable and hardware-backed
where a TEE is available. These are keys to a user's health data — do not
substitute plain `SharedPreferences`.

## Build

```bash
./gradlew test                                    # native, 95 tests
cd flutter/lumora_ble && flutter test             # Dart, 17 tests
./gradlew publishToMavenLocal                     # install locally to try it
```

### On-device harnesses

Unit tests cover the parsers; they cannot cover a radio or a health store.
Two harnesses exist for that:

```bash
./gradlew :testapp:installDebug                   # native Android, BLE path
cd flutter/lumora_ble/example && flutter run      # Flutter, both platforms
```

`testapp/` scans for the standard profile and shows discovered devices, live
readings and a timestamped log. The Flutter example branches by platform: the
BLE scan on Android, HealthKit authorization and history on iOS.

iOS needs two pieces of host-app setup that fail hard when missing — without
`NSHealthShareUsageDescription` the process is terminated rather than an error
thrown. See [flutter/lumora_ble/ios/README.md](flutter/lumora_ble/ios/README.md).

## What is public API

Binary compatibility is promised for the `core` module only — the `LumoraBle`
interface and the types it exposes. Everything else is plumbing.

That boundary is enforced by Gradle rather than by Kotlin's `internal`, which
scopes to a single compilation module: `GattConnection` is shared by three
device modules, so marking it `internal` would not compile. Instead every
module declares `:transport` with `implementation`, so it resolves at runtime
scope and never reaches a consumer's compile classpath — importing
`dev.lumora.ble.transport.GattConnection` from an app does not resolve. The
shared plumbing is additionally marked `@InternalLumoraApi`, an opt-in
annotation that fails compilation with an explanatory message for anyone who
adds a direct dependency to bypass the scoping.

Practical consequence: the SDK must expose everything a consumer legitimately
needs, because they cannot reach around it. `LumoraBle.requiredPermissions`
exists for exactly that reason — `requestPermissions()` only reports status,
so without it an app could not prompt at all.

## Verified on hardware

What has actually run against real devices, as opposed to passing a unit test.
Stated plainly because every protocol here is unofficial, and "compiles" is a
weaker claim than it looks.

| Path | Status |
|---|---|
| iOS HealthKit — Apple Watch | **Verified.** Live heart rate read from a paired watch |
| Android BLE — scan, connect, discover | **Verified.** Real peripheral, characteristic read parsed |
| Android BLE — heart rate parsing | **Not yet.** Needs a peripheral serving `0x2A37` |
| Oura, Dexcom, Libre | **Not yet.** Needs the respective hardware |

`StandardGattParsers` has 16 unit tests covering every flags-byte combination,
but has not met a live heart rate peripheral. Treat it accordingly.

One lesson from that testing is baked into the SDK: a peripheral can advertise
a service UUID it does not actually serve. `StandardGattConnection` therefore
branches on what service discovery returns, never on the advertisement, and
logs the real GATT table on connect.

## Not implemented

- **The BLE protocols on iOS.** iOS now has a native implementation, but it
  reads Apple Watch data through HealthKit only — the Oura, Dexcom, Libre and
  standard-GATT paths are Android-only and would each need reimplementing over
  CoreBluetooth/CoreNFC. `supportedDevices()` returns a genuinely different
  matrix per platform; check it rather than assuming.
- **Cloud fallbacks.** For most products the vendor APIs (Oura Cloud v2,
  LibreLinkUp, Dexcom v3) are the right integration; they are not in this repo
  yet. Note the Dexcom API delays data 1h (US) / 3h (elsewhere) by regulatory
  design, so it cannot drive live alerts. For Fitbit history the route is now
  the Google Health API — the legacy Fitbit Web API shut down in September 2026.
- **Dexcom backfill.** The G6 replays stored readings, but that characteristic
  has its own chunked framing that needs a real transmitter to validate. Live
  readings work; history does not.
- **Libre calibration.** The FRAM path is uncalibrated; sensors carry per-unit
  parameters the official algorithm applies on top.
- **Maven publication.** The SDK is not published, so the Flutter plugin's
  declared coordinate `dev.lumora.ble:sdk:0.1.0` is resolved by a composite
  build in `flutter/lumora_ble/example/android/settings.gradle.kts`. A consumer
  outside this repository needs that substitution, or the published artifact.
  Note the composite build pins the example to the SDK's AGP and Kotlin
  versions — Gradle refuses two Android Gradle Plugin versions in one build.

## Licence

Apache 2.0. See [LICENSE](LICENSE).

Not affiliated with or endorsed by Ōura Health, Abbott, or Dexcom. Not a medical
device: do not use it for diagnosis or treatment decisions.
