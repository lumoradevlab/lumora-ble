package dev.lumora.ble.sdk

import android.content.Context
import dev.lumora.ble.core.CredentialStore
import dev.lumora.ble.core.LumoraBle
import dev.lumora.ble.core.ProtocolRegistry

/**
 * Creates an SDK instance speaking the protocols you install.
 *
 * Protocols are opt-in rather than bundled, and that is a legal decision as
 * much as a technical one. Every vendor protocol here is reverse-engineered
 * and unofficial; using one violates that vendor's terms of service. Bundling
 * them would impose Dexcom's, Ōura's and Abbott's terms on an app that only
 * wanted a standard heart rate strap, so a build ships the code it asks for
 * and no more.
 *
 * Only the standard SIG profile is free of that exposure:
 *
 * ```
 * // build.gradle.kts — depend on what you install
 * implementation("dev.lumora.ble:sdk:0.1.0-alpha01")
 * implementation("dev.lumora.ble:standard:0.1.0-alpha01")
 *
 * val sdk = LumoraBle.create(context) {
 *     install(StandardGattProtocol)
 * }
 * ```
 *
 * Adding a vendor protocol is then a deliberate, visible act:
 *
 * ```
 * install(DexcomProtocolFactory("8UMS7E", sessionStart = Instant.now()))
 * ```
 *
 * A kind whose protocol is not installed is absent from [LumoraBle.supportedDevices]
 * and fails with an error naming what *is* installed — never a silent no-op.
 *
 * [credentials] defaults to an encrypted store; supply your own only if you
 * already have a secure keystore. Never back it with plain SharedPreferences —
 * these are the keys to a user's health data.
 *
 * The returned instance owns a coroutine scope: call [LumoraBle.close] when
 * the component that created it goes away.
 *
 * @throws IllegalArgumentException if no protocol is installed, which would
 *   otherwise produce an SDK that silently supports nothing.
 */
fun LumoraBle.Companion.create(
    context: Context,
    credentials: CredentialStore = EncryptedCredentialStore(context),
    protocols: ProtocolRegistry.() -> Unit,
): LumoraBle {
    val registry = ProtocolRegistry().apply(protocols)
    require(!registry.isEmpty()) {
        "No protocols installed. Add a device module to your build and install " +
            "it, e.g. LumoraBle.create(context) { install(StandardGattProtocol) }"
    }
    return LumoraBleImpl(context.applicationContext, credentials, registry)
}
