plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
    signing
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "dev.lumora.ble.transport"
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
    implementation(project(":core"))

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
            url = uri("https://maven.pkg.github.com/lumoradevlab/lumora-ble")
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
                url.set("https://github.com/lumoradevlab/lumora-ble")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                // scm and developers are not decoration: Maven Central
                // rejects a POM without them. Declared now so the metadata is
                // validated against GitHub Packages before Central makes a
                // published version permanent.
                developers {
                    developer {
                        id.set("lumoradevlab")
                        name.set("Lumora")
                        url.set("https://github.com/lumoradevlab")
                    }
                }
                scm {
                    url.set("https://github.com/lumoradevlab/lumora-ble")
                    connection.set(
                        "scm:git:https://github.com/lumoradevlab/lumora-ble.git")
                    developerConnection.set(
                        "scm:git:ssh://git@github.com/lumoradevlab/lumora-ble.git")
                }
            }
        }
    }
}

// Maven Central requires every artifact to be GPG-signed. Configured to
// activate only when a key is present, so local builds and GitHub Packages
// publishing — neither of which needs signatures — are unaffected.
signing {
    val key = providers.gradleProperty("signingInMemoryKey").orNull
    val password = providers.gradleProperty("signingInMemoryKeyPassword").orNull
    if (key != null) {
        useInMemoryPgpKeys(key, password)
        sign(publishing.publications)
    }
}
