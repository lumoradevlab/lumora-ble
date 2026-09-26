# Protocol status

Honest per-device assessment. Read this before promising a device to a customer.

**The headline: generation matters more than brand.** For both CGMs the older
generation is supportable and the current one is not. Check which hardware your
users actually have before planning.

| Device | Transport | Status | Notes |
|---|---|---|---|
| Dexcom **G6** | BLE | **Works** | Needs the transmitter serial; Android bonds on first connect |
| Dexcom **G7 / ONE+** | BLE | **Not supported** | EC-J-PAKE; rejected with a clear error |
| Libre **1 / 2** | NFC | **Works** | Tap to read; sensor activated by the official app |
| Libre **3** | BLE | **Not supported** | Requires an Abbott-issued certificate |
| Oura Ring 3/4/5 | BLE | **Works, with setup** | Ring must be factory-reset |
| **Standard GATT profiles** | BLE | **Works, with setup** | Published SIG spec; covers Fitbit Charge 6 / Air, Pixel Watch 2+, Polar, Wahoo, Garmin straps |
| Fitbit **sync protocol** | BLE | **Not supported** | Per-device key provisioned via Fitbit's cloud |
| Pixel Watch **companion** | BLE | **Not supported** | Wear OS; no GATT surface to connect to |
| **Apple Watch** | **HealthKit** | **Works (iOS build only)** | Read from the Health store; unreachable from Android |

No vendor publishes a specification for its own protocol. Every constant in the
`oura`, `libre` and `dexcom` modules comes from reverse-engineering work, using
those protocols violates each vendor's terms of service, and a firmware update
can break them without notice. The `standard` module is the deliberate exception
— it implements published Bluetooth SIG profiles, so it carries neither the
legal exposure nor the fragility. **For an SDK
shipped to third parties this is a licensing and liability decision, not just a
technical one** — see "Shipping this" at the bottom.

---

## Dexcom G6 — works

Implemented in `devices/dexcom`.

The key is derived entirely from the transmitter serial printed on the
applicator — there is no key exchange, no certificate, nothing to provision:

```
key = UTF-8("00" + SERIAL + "00" + SERIAL)   // exactly 16 bytes, AES-128
```

Handshake:

1. Phone → `01 <random 8-byte token> <slot>`
2. Transmitter → `03 <our token, encrypted> <its 8-byte challenge>`
3. Phone verifies the echo, then → `04 <challenge, encrypted>`
4. Transmitter → `05 <authenticated> <bonded>`, then Android bonding

Encryption is AES-128-ECB over the 8-byte value **doubled** to fill one block,
keeping the first 8 bytes of ciphertext.

**Step 3 is mutual authentication and must not be skipped.** Verifying that the
transmitter encrypted our token the same way we did is what stops a nearby
impostor from feeding an app fabricated glucose values. `DexcomConnection`
treats a mismatch as a hard failure.

Sensor state gates everything: only `CalibrationState.OK` yields a reading.
Warm-up, calibration-needed, and sensor-failed states return nothing rather than
a misleading number.

Not implemented: **backfill**. The G6 stores recent readings and replays them on
request, but the backfill characteristic has its own chunked framing that needs a
real transmitter to validate. Live readings work; history does not.

## Dexcom G7 — not supported

The G7 replaced the scheme above with **EC-J-PAKE**. Two independent blockers:

1. **No EC-J-PAKE on Android.** The JCE has no J-PAKE at all; BouncyCastle ships
   only the finite-field variant, not the elliptic-curve one the G7 uses. The
   primitive would have to be written from scratch against mbedtls' wire format.
2. **Channel contention.** The G7 exposes three BLE channels and third-party
   collectors can only use the pairing channel — the same one the official app
   and Omnipod 5 use. Reports consistently show the sensor dropping the link
   right after the J-PAKE exchange when another client holds it.

`DexcomAuth.requireG6()` rejects a G7 by its advertised name (`DXCM…`) with an
explanatory error rather than letting it hang in a retry loop.

## FreeStyle Libre 1/2 — works, over NFC

Implemented in `devices/libre`.

These are **NFC devices, not BLE**. The phone is held against the sensor and the
whole 344-byte FRAM image transfers in one pass — no pairing, no encryption, no
connection to maintain. That is exactly why they remain practical.

The image holds two wrapping ring buffers:

- **trend**: 16 slots, one per minute
- **history**: 32 slots, one per 15 minutes

Each slot is 6 bytes; glucose is the low 13 bits. The newest entry sits one slot
*before* the write pointer and older entries walk backwards with modulo
arithmetic — getting this wrong yields plausible-looking but time-shifted data,
which is why `LibreFramTest` covers the wrap explicitly.

Libre Pro/H keeps its counters at different offsets; that is the one layout
branch in the parser.

**Caveat on accuracy.** The implemented path is *uncalibrated*: raw counts are
converted with a fixed multiplier. Real sensors carry per-unit calibration
parameters that the official algorithm applies on top, so values can drift from
what the vendor app shows. Do not present these as clinically equivalent.

## FreeStyle Libre 3 — not supported

Libre 3 moved to BLE-only with a certificate-gated handshake: `startECDH` →
`loadCertificate` → **162-byte app certificate** → ECDH over P-256 ephemeral
keys → symmetric challenge → session keys.

The app certificate is **issued and signed by Abbott** and embedded in their app.
It cannot be synthesized. This is a licensing problem wearing an engineering
costume, and no amount of effort changes it. The supported route is LibreLinkUp
or an Abbott partner agreement.

## Oura Ring — works, with a setup cost

Implemented in `devices/oura`.

Connect → subscribe → `2f 01 2b` for a 15-byte nonce → AES-128-ECB encrypt under
the 16-byte shared key → `2f 11 2d <16 bytes>` → `2f 02 2e 00` on success.

**The catch.** The key is installed with `24 10 <key>`, which only succeeds on a
**factory-reset ring**; a ring already paired with the Oura app answers `0x05`.
So the user must reset the ring and give up the official app for it. Viable for
research cohorts and dedicated hardware, a hard sell for consumers.

Raw samples only — Oura's sleep and readiness scores come from proprietary
models that do not run on the ring.

## Standard GATT profiles — works, and unlike everything above

Implemented in `devices/standard`.

**This is the only module in the SDK with no reverse-engineering in it.** The
Bluetooth SIG publishes these profiles, so the UUIDs and payload layouts are
specified rather than inferred:

| Profile | Service | Measurement characteristic |
|---|---|---|
| Heart Rate | `0x180D` | `0x2A37` |
| Battery | `0x180F` | `0x2A19` |
| Health Thermometer | `0x1809` | `0x2A1C` |
| Pulse Oximeter | `0x1822` | `0x2A5F` |

Three consequences follow, and together they are the argument for this module:
there is **no vendor ToS to violate**, a firmware update **cannot break the
parsing**, and one implementation covers **every conforming peripheral** rather
than one vendor. Flow is connect → discover → subscribe → parse. There is no
authentication step and no credential, which is why `StandardGattConnection`
takes no `CredentialStore`.

### Why this is the Fitbit and Pixel Watch answer

Both vendors' own protocols are closed (below), but both broadcast live heart
rate over `0x180D`. Google documents this: **Fitbit Charge 6, Fitbit Air, and
Pixel Watch 2/3/4/5** support it, and it is how they already drive Peloton,
Zwift, Strava, Concept2 and Wahoo equipment.

Worth knowing for product scoping: **Samsung Galaxy Watch does not broadcast
heart rate** — it remains a long-standing open feature request — so this is a
point of difference for the Google ecosystem rather than a generic wearable
capability.

Constraints that shape the integration:

- **Broadcast is user-initiated, per session.** Quick Settings → Connected
  Fitness → Connect. A Pixel Watch may additionally need **Extended Pairing**,
  which uses a public Bluetooth address and skips the standard pairing flow.
  This is why the kind is `REQUIRES_SETUP` rather than `SUPPORTED`: a scan that
  finds nothing usually means the user has not started sharing.
- **Concurrent connection budget.** Charge 6 allows **one** link; Pixel Watch 3+
  allows **two**. A watch already talking to a treadmill will refuse.
- **Live only.** The standard profiles define no stored-history characteristic,
  so `backfill()` returns an empty list by design rather than throwing.
- **Battery cost.** Google notes broadcasting measurably shortens battery life.

### Parsing notes

The Heart Rate Measurement layout is variable, driven by its leading flags byte:
bit 0 selects uint8 vs uint16 BPM, bit 3 adds a 2-byte energy field, bit 4 adds
RR intervals. **Assuming uint8 is the classic bug** — it yields a
plausible-but-wrong BPM on a uint16 device and desynchronises every field after
it, so `StandardGattParsersTest` pins each flag combination explicitly.

RR intervals arrive in units of 1/1024 s and are converted to milliseconds so
`HeartRateSample.ibiMs` means what its name says. Temperature and SpO2 use
IEEE-11073 FLOAT/SFLOAT, not IEEE-754 — a detail that silently produces
nonsense if missed.

Two safety rules: a sample of 0 bpm (or above 300) is dropped rather than
emitted, and a device that explicitly reports **no sensor contact** has its
samples dropped. An unworn strap reporting 0 would otherwise surface in a
consuming app as cardiac arrest.

## Fitbit sync protocol — not supported

Fitbit's own protocol carries what people actually want from a Fitbit: steps,
sleep stages, and stored history. It is not reachable by a third party.

Authentication runs over Fitbit's "Airlink" protocol — the tracker issues a
nonce and the authentication key is derived from it together with a per-device
secret provisioned at manufacture through Fitbit's cloud. Activity payloads are
themselves encrypted with AES or XTEA under a pre-installed key held only by the
tracker and Fitbit's servers. **The client never possesses the key material, so
no amount of protocol work derives it** — the same class of blocker as the Libre
3 certificate.

The published academic work on this (Edinburgh, 2017-2018) targeted firmware
that was frequently still operating in plaintext mode. Google has since shipped
firmware to Charge 6, Sense 2 and Versa 4 explicitly adding "new Bluetooth
security features" and forcing users to re-pair.

The clearest practical signal: **Gadgetbridge supports 434 device models across
41 brands — including Xiaomi, Huawei, Amazfit, Garmin and Withings — and lists
no Fitbit device at all.** That is not an oversight in a project of that scope.

**Use instead:** `HEART_RATE_MONITOR` for live heart rate, or the Google Health
API for history. Note the timing — the legacy Fitbit Web API shut down in
**September 2026**; it was a hard cutoff, OAuth tokens did not carry over, and
the replacement is an aggregation layer over a user's Google account rather than
a device API.

## Pixel Watch companion — not supported

A Pixel Watch is not a BLE peripheral with a companion protocol to reverse. It
is a **Wear OS computer**: sensor data lives in on-device Health Services and
syncs to Google's cloud through the phone's Google Play Services, with no GATT
surface exposed for a third-party client to connect to. There is no protocol
here that is merely difficult — there is nothing to connect to.

Reaching a Pixel Watch properly means shipping a **Wear OS app** that reads
Health Services on the watch itself, which is a different product with a
different distribution story, or using the **Google Health API** server-side.

**Use instead:** `HEART_RATE_MONITOR`, which works on Pixel Watch 2 and newer
while the user is broadcasting.

## Apple Watch — works, on iOS, and not over a radio

Implemented in `flutter/lumora_ble/ios/Classes`.

**There is no BLE path to an Apple Watch, and this is structural rather than
difficult.** watchOS exposes no GATT service for health data. The watch pairs
only with its iPhone, over a proprietary link, and writes into HealthKit on that
phone. Nothing advertises, so nothing can be scanned for or connected to. It is
a different kind of blocker from Libre 3 or Fitbit: there is no protocol to
implement, no key to obtain, and no amount of work that changes it.

The supported route is therefore the **HealthKit store**, which is why
`Transport.HEALTH_KIT` exists alongside `BLE` and `NFC`. Like the NFC
distinction, it changes the UX completely: the user grants permission once and
the system delivers data.

An Apple Watch also **cannot pair with an Android phone at all**, so the Android
build reports `APPLE_WATCH` as BLOCKED with that reason.

### What the iOS build does and does not do

Only HealthKit. The Oura, Dexcom, Libre and standard-GATT protocols are
Android-only; porting each means reimplementing it over CoreBluetooth or
CoreNFC. `IosSupportMatrix` therefore reports a genuinely different matrix from
the Android `SupportMatrix`, rather than echoing promises the iOS build cannot
keep.

Read types: heart rate, HRV (SDNN), oxygen saturation, body temperature. An
anchored query delivers new samples as HealthKit receives them, so each update
carries only what is new rather than replaying history.

### Three things integrators must not miss

1. **`scan()` fails on iOS**, deliberately and loudly. There are no devices to
   discover, so returning an empty stream would look like a hardware fault.
   Call `connect()` directly.
2. **iOS never reports that read access was denied.** A denied type is
   indistinguishable from one holding no data — by design, so a user hiding a
   condition is not itself detectable. **An empty result is never evidence of a
   permission problem**, and an app must not tell the user otherwise.
3. **Host-app setup is mandatory and fails hard.** Without
   `NSHealthShareUsageDescription` in Info.plist, iOS *terminates the process*
   rather than throwing. The HealthKit capability is also required. See
   `flutter/lumora_ble/ios/README.md`.

### Where HealthKit beats the BLE path

`backfill()` returns real history. The watch has been recording continuously, so
stored samples are genuinely available — whereas the standard GATT profiles
define no history characteristic and return an empty list. Latency is the
trade: samples arrive in batches as the watch syncs, so this is near-live rather
than the sub-second cadence of a BLE chest strap.

---

## What has been verified on hardware

Distinct from what passes a unit test. Worth stating explicitly: these are
reverse-engineered protocols, so a green test suite says the code does what its
author expected, not that a device agrees.

| Path | Verified | Notes |
|---|---|---|
| iOS HealthKit | **Yes** | Live heart rate read from a paired Apple Watch |
| Standard GATT — scan/connect/discover | **Yes** | Real peripheral; a characteristic read parsed correctly |
| Standard GATT — heart rate parsing | No | Needs a peripheral actually serving `0x2A37` |
| Oura, Dexcom G6, Libre | No | Needs the respective hardware |

One finding from that testing shaped the code. A peripheral advertising
`0x180D` connected and served no heart rate service at all — the advertisement
and the GATT table are independent, and they disagreed. (It was an iPhone:
iOS does not let a third-party app publish arbitrary GATT services, so an app
there can advertise a UUID it cannot serve.) `StandardGattConnection` therefore
branches only on what service discovery returns, and logs the real service list
on connect so the two can never be confused again.

---

## Shipping this

This SDK is intended for third-party developers, which raises the stakes above
using the same code in your own app:

- **Terms of service.** Every protocol here is unofficial. Your users will be
  building products on protocols their vendors do not sanction. Say so plainly
  in your own terms rather than letting integrators discover it.
- **Medical framing.** CGM data drives treatment decisions. The Libre path is
  uncalibrated and the G6 path has no backfill; neither is a substitute for the
  vendor's own app or alerting. The SDK must not be presented as a medical
  device, and integrators should be told the same.
- **Breakage.** A firmware update can end any of these overnight. Version the
  SDK so a broken device can be disabled without forcing integrators to ship a
  new app.

`SupportMatrix` reports all of this at runtime so an integrating app can grey out
a device rather than failing at connect time.
