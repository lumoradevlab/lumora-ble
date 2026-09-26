pluginManagement {
    val flutterSdkPath =
        run {
            val properties = java.util.Properties()
            file("local.properties").inputStream().use { properties.load(it) }
            val flutterSdkPath = properties.getProperty("flutter.sdk")
            require(flutterSdkPath != null) { "flutter.sdk not set in local.properties" }
            flutterSdkPath
        }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    // Pinned to the SDK's versions only because of the composite build below.
    // This constraint is an artifact of developing inside the monorepo; an app
    // resolving the published artifacts keeps whatever versions it likes.
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}

include(":app")

// MONOREPO CONVENIENCE — NOT THE INTEGRATION PATH.
//
// This example lives in the SDK's own repository, so it substitutes the
// published coordinates for the local Gradle projects: editing SDK source is
// picked up by the next Flutter build with no publish step in between.
//
// An integrating app must NOT copy this. A composite build pulls the SDK's
// whole Gradle build into the consumer's, and Gradle refuses two Android
// Gradle Plugin versions in one build — which is why the `plugins` block above
// is pinned to this repository's AGP and Kotlin rather than Flutter's
// defaults. In a real app that would pin the app's toolchain to ours.
//
// Consumers resolve dev.lumora.ble:* from a Maven repository and keep their
// own versions; verified against AGP 9.0.1 / Kotlin 2.3.20 while this build
// uses 8.7.3 / 2.1.0. See the README's Install section.
includeBuild("../../../..") {
    dependencySubstitution {
        substitute(module("dev.lumora.ble:sdk")).using(project(":sdk"))
        substitute(module("dev.lumora.ble:core")).using(project(":core"))
        substitute(module("dev.lumora.ble:transport")).using(project(":transport"))
        substitute(module("dev.lumora.ble:standard")).using(project(":devices:standard"))
        substitute(module("dev.lumora.ble:oura")).using(project(":devices:oura"))
        substitute(module("dev.lumora.ble:libre")).using(project(":devices:libre"))
        substitute(module("dev.lumora.ble:dexcom")).using(project(":devices:dexcom"))
    }
}
