plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "dev.lumora.ble.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Required for maven-publish to find a `release` component.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {


    // api, not implementation: the public API returns Flow<T>, so a consumer
    // cannot compile against scan() or readings without coroutines on their
    // compile classpath.
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
}

// Configured here rather than in the root script: the AAR's `release`
// software component is created by the Android plugin during this project's
// own evaluation, so a root-level afterEvaluate cannot see it.
publishing {
    publications {
        register<MavenPublication>("maven") {
            afterEvaluate { from(components["release"]) }
            artifactId = project.name

            pom {
                name.set("Lumora BLE ${project.name}")
                description.set(
                    "One SDK for health wearables: Oura, Dexcom G6, " +
                        "FreeStyle Libre and standard GATT heart rate."
                )
                url.set("https://github.com/lumoradevlab/BLE-Android")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}
