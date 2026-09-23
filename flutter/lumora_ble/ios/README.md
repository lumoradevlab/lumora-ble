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
| `backfill()` | **Better than the BLE path**: returns real recorded history, where standard GATT returns nothing. |
| `setDexcomTransmitter()` / `readLibreSensor()` | Fail with `ProtocolViolation` — Android only. |

Check `supportedDevices()` at runtime rather than assuming: the two platforms
return genuinely different matrices.

## The permission caveat that matters

**iOS never reports that read access was denied.** A denied data type is
indistinguishable from one that has no data — by design, so that a user hiding
a condition is not itself detectable. An empty result is therefore never proof
of a permission problem, and an app must not tell the user their permissions
are wrong on that basis.
