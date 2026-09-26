# Consumer rules for dev.lumora.ble:libre
#
# The module publishes as its own artifact, so these ship with its AAR and
# apply to the integrating app's R8 run.

# Public enums are read by name — some cross the Flutter channel, and all are
# branched on by consumers. R8 may rename or strip members it cannot see used,
# which surfaces only in a release build.
-keepclassmembers enum dev.lumora.ble.libre.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public static ** entries();
    *;
}

# Result and message types a consumer receives.
-keep class dev.lumora.ble.libre.** { public protected *; }
