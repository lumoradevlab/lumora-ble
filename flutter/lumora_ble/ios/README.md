# iOS — Apple Watch via HealthKit

The iOS build reads Apple Watch data from **HealthKit**. It does not implement
the BLE device protocols; those are Android-only. See
`../../../docs/PROTOCOL-STATUS.md`.

## Required host-app setup

Both steps happen in the *consuming app*, not in this plugin. Without them the
app will not work, and the first failure is a hard crash rather than an error.

### 1. Info.plist

iOS terminates the process — it does not throw — when an app requests Health
access without a usage description. Add to `ios/Runner/Info.plist`:

```xml
<key>NSHealthShareUsageDescription</key>
<string>Reads heart rate and related measurements from your Apple Watch.</string>
<key>NSHealthUpdateUsageDescription</key>
<string>This app does not write health data.</string>
```

`NSHealthUpdateUsageDescription` is required even though this SDK never writes,
because the key is validated at link time against the HealthKit framework.

### 2. HealthKit capability

In Xcode: **Signing & Capabilities → + Capability → HealthKit**. This adds the
entitlement; without it `HKHealthStore.isHealthDataAvailable()` is false and
every read returns nothing.

## How the iOS API differs

| Call | Behaviour |
|---|---|
| `scan()` | **Fails** with `ProtocolViolation`. HealthKit exposes no devices to scan for. |
| `connect()` | Requests Health authorization and starts observing. No radio link is established. |
| `readings` | Samples as HealthKit receives them from the watch — near-live, delivered in batches. |
| `backfill()` | **Better than the BLE path**: returns real recorded history, where standard GATT returns nothing. Includes staged sleep intervals. |
| `setDexcomTransmitter()` / `readLibreSensor()` | Fail with `ProtocolViolation` — Android only. |

Check `supportedDevices()` at runtime rather than assuming: the two platforms
return genuinely different matrices.

## Sleep

Returned by `backfill()` as `SleepReading`s — one per stage transition, not a
nightly summary, because that is how HealthKit stores it. A night is dozens of
intervals; fold them yourself:

```dart
final asleep = readings
    .whereType<SleepReading>()
    .where((r) => r.stage != SleepStage.awake && r.stage != SleepStage.inBed)
    .fold(Duration.zero, (sum, r) => sum + r.duration);
```

Excluding `awake` and `inBed` is the part worth getting right — counting them
inflates time-asleep by however long the user lay reading.

Stage detail (`asleepCore`, `asleepDeep`, `asleepRem`) requires **iOS 16+**
and a watch that records staging. Older systems and older watches report
`asleepUnspecified`, which is handled rather than dropped.

Sleep is backfill-only: there is no live sleep stream, because staging is
computed after the fact.

## The permission caveat that matters

**iOS never reports that read access was denied.** A denied data type is
indistinguishable from one that has no data — by design, so that a user hiding
a condition is not itself detectable. An empty result is therefore never proof
of a permission problem, and an app must not tell the user their permissions
are wrong on that basis.
