# Consumer rules for dev.lumora.ble:core
#
# These ship with the AAR and apply to the integrating app's R8 run.

# Enums cross the Flutter platform channel BY NAME (DeviceKind.valueOf,
# enum name lookups on the Dart side), and DeviceReading subtypes are
# matched reflectively nowhere but constructed by name in tests. R8 is
# free to rename or strip enum members it cannot see being used, which
# turns into an IllegalArgumentException at runtime in a release build
# only — the failure never appears in debug.
-keepclassmembers enum dev.lumora.ble.core.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public static ** entries();
    *;
}

# The sealed hierarchies are part of the public API surface: consumers
# branch on them exhaustively, and Kotlin's `when` on a sealed type
# relies on the subclasses existing.
-keep class dev.lumora.ble.core.DeviceReading { *; }
-keep class dev.lumora.ble.core.DeviceReading$* { *; }
-keep class dev.lumora.ble.core.ConnectionState { *; }
-keep class dev.lumora.ble.core.ConnectionState$* { *; }
-keep class dev.lumora.ble.core.DeviceError { *; }
-keep class dev.lumora.ble.core.DeviceError$* { *; }

# Data classes crossing the channel keep their component names.
-keep class dev.lumora.ble.core.DeviceId { *; }
-keep class dev.lumora.ble.core.DiscoveredDevice { *; }
-keep class dev.lumora.ble.core.DeviceSupport { *; }
-keep class dev.lumora.ble.core.GlucoseReading { *; }
-keep class dev.lumora.ble.core.HeartRateSample { *; }
-keep class dev.lumora.ble.core.BatteryLevel { *; }
