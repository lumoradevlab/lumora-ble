package dev.lumora.ble.core

/**
 * Marks plumbing that is public only because Kotlin's `internal` stops at a
 * compilation module boundary.
 *
 * The device modules share transport code, so it cannot be `internal` without
 * breaking the build. Gradle's `implementation` keeps it off a consumer's
 * compile classpath, and this annotation states the intent for anyone reading
 * the source or bypassing that with a direct dependency.
 *
 * Anything marked here may change or disappear in a patch release. Binary
 * compatibility is promised only for the surface documented in the README:
 * [LumoraBle] and the types it exposes.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Internal Lumora plumbing, not public API. It may change in any " +
        "release. If you need this, please open an issue describing the use " +
        "case rather than opting in.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class InternalLumoraApi
