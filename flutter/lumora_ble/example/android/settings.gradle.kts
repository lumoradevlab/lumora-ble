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
    // Pinned to the versions the SDK's own build uses. Gradle refuses to load
    // two Android Gradle Plugin versions in one composite build, and the
    // includeBuild below pulls the SDK's build in.
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}

include(":app")

// The SDK is not published to Maven yet, so resolve `dev.lumora.ble:sdk:0.1.0`
// — the coordinate the Flutter plugin depends on — from the Gradle build at the
// repository root. Editing SDK source is then picked up by the next Flutter
// build with no publish step in between.
//
// Remove this block once the SDK is published; the plugin's declared
// coordinates already match, so nothing else has to change.
includeBuild("../../../..") {
    dependencySubstitution {
        substitute(module("dev.lumora.ble:sdk")).using(project(":sdk"))
        substitute(module("dev.lumora.ble:core")).using(project(":core"))
        substitute(module("dev.lumora.ble:transport")).using(project(":transport"))
    }
}
