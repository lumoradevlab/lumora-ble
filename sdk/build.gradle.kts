plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "dev.lumora.ble.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // These modules are part of the library and legitimately use the
        // shared transport plumbing. A consumer hits the opt-in error instead.
        freeCompilerArgs += "-opt-in=dev.lumora.ble.core.InternalLumoraApi"
    }

    // Required for maven-publish to find a `release` component.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // api, not implementation: consumers of the SDK need the core types.
    api(project(":core"))
    implementation(project(":transport"))
    // Deliberately NOT depending on the device modules, on any configuration.
    // Protocols are installed by the consumer, who depends only on the ones
    // they want — bundling them here would impose every vendor's terms of
    // service on an app that asked for none of them, and pull their classes
    // and resources into its APK.

    implementation(libs.kotlinx.coroutines.core)
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
    repositories {
        // GitHub Packages, as an interim target until the SDK is on Maven
        // Central. Far less setup than Sonatype — no namespace verification,
        // no GPG signing — but note the asymmetry it imposes on consumers:
        // GitHub Packages requires authentication even to READ a public
        // package, so every integrator needs a token. That is the reason it
        // is interim rather than the destination.
        //
        // Credentials come from gradle.properties or the environment, never
        // the repository:
        //   ./gradlew publishAllPublicationsToGitHubPackagesRepository \
        //     -PgithubUser=<user> -PgithubToken=<token>
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/lumoradevlab/BLE-Android")
            credentials {
                username = (project.findProperty("githubUser") as String?)
                    ?: System.getenv("GITHUB_ACTOR")
                password = (project.findProperty("githubToken") as String?)
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }

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
