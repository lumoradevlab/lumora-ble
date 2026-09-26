plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
    signing
}

// :sdk depends on this module, so its POM references these coordinates. Left
// unpublished, a consumer resolving :sdk fails on "unspecified" versions.
group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "dev.lumora.ble.oura"
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

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":transport"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
}

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
                    "Oura Ring protocol for the Lumora BLE SDK. Reverse-engineered and unofficial; using it violates Oura Health's terms of service."
                )
                url.set("https://github.com/lumoradevlab/BLE-Android")
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
                    url.set("https://github.com/lumoradevlab/BLE-Android")
                    connection.set(
                        "scm:git:https://github.com/lumoradevlab/BLE-Android.git")
                    developerConnection.set(
                        "scm:git:ssh://git@github.com/lumoradevlab/BLE-Android.git")
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
