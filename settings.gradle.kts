pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "lumora-ble"

include(":core")
include(":transport")
include(":sdk")
include(":devices:oura")
include(":devices:libre")
include(":devices:dexcom")
include(":devices:standard")
