plugins {
    // A pure JVM module, not an Android library. `core` has no Android
    // imports — that is the whole point of the layering — so building it as
    // an AAR bought nothing and cost a compileSdk, debug/release variants
    // that ran every test twice, and a dependency on the Android Gradle
    // Plugin. As a jar it is also the prerequisite for moving this module to
    // Kotlin Multiplatform later: the domain types and the parsers that
    // depend only on them could then be shared with a native iOS transport.
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
    signing
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // api, not implementation: the public API returns Flow<T>, so a consumer
    // cannot compile against scan() or readings without coroutines on their
    // compile classpath.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

publishing {
    repositories {
        // GitHub Packages, as an interim target until the SDK is on Maven
        // Central. Note it requires authentication even to READ a public
        // package, which is why it is interim rather than the destination.
        //
        // Credentials come from gradle.properties or the environment:
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
            from(components["java"])
            artifactId = project.name

            pom {
                name.set("Lumora BLE core")
                description.set(
                    "Domain model and the LumoraBle contract. Pure Kotlin, " +
                        "no Android dependencies."
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
//
//   ./gradlew publish -PsigningInMemoryKey="$(cat key.asc)" \
//     -PsigningInMemoryKeyPassword=<passphrase>
signing {
    val key = providers.gradleProperty("signingInMemoryKey").orNull
    val password = providers.gradleProperty("signingInMemoryKeyPassword").orNull
    if (key != null) {
        useInMemoryPgpKeys(key, password)
        sign(publishing.publications)
    }
}
