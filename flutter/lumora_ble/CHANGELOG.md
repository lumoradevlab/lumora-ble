## 0.1.0-alpha.1

First preview. The API is not stable.

**Android** — Oura Ring, Dexcom G6, FreeStyle Libre 1/2 over NFC, and standard
SIG heart rate (Fitbit Charge 6, Pixel Watch 2+, Polar, Wahoo, chest straps).

**iOS** — Apple Watch via HealthKit. The BLE and NFC protocols are Android-only;
watchOS exposes no BLE service for health data, so HealthKit is the only route
to a watch. Call `supportedDevices()` at runtime rather than assuming parity.

Verified on hardware: the iOS HealthKit path, and Android scan/connect/discover
with a characteristic read parsed from a real peripheral. Not yet verified:
heart rate parsing against a live peripheral, and the three vendor protocols
against their devices.

Requires iOS host-app setup — `NSHealthShareUsageDescription` and the HealthKit
capability. Without the plist key iOS terminates the process rather than
throwing.
