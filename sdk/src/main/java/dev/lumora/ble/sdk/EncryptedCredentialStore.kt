package dev.lumora.ble.sdk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.lumora.ble.core.CredentialStore
import dev.lumora.ble.core.DeviceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device credentials sealed with an AES-GCM key held in the Android Keystore.
 *
 * The wrapping key is non-exportable and lives in hardware where the device has
 * a TEE/StrongBox, so the stored blobs are useless if the app's private files
 * are extracted. These credentials grant access to a user's health data, so
 * plain SharedPreferences is not an acceptable fallback.
 */
class EncryptedCredentialStore(
    context: Context,
    private val keyAlias: String = DEFAULT_ALIAS,
) : CredentialStore {

    private val prefs = context.applicationContext
        .getSharedPreferences("lumora_ble_credentials", Context.MODE_PRIVATE)

    override suspend fun load(id: DeviceId): ByteArray? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(id.key(), null) ?: return@withContext null
        val blob = android.util.Base64.decode(stored, android.util.Base64.NO_WRAP)
        if (blob.size <= IV_LENGTH) return@withContext null

        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, blob, 0, IV_LENGTH),
        )
        runCatching { cipher.doFinal(blob, IV_LENGTH, blob.size - IV_LENGTH) }.getOrNull()
    }

    override suspend fun save(id: DeviceId, credential: ByteArray) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        // GCM generates its own IV; prepend it so decryption can recover it.
        val blob = cipher.iv + cipher.doFinal(credential)
        prefs.edit()
            .putString(id.key(), android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP))
            .apply()
    }

    override suspend fun clear(id: DeviceId) = withContext(Dispatchers.IO) {
        prefs.edit().remove(id.key()).apply()
    }

    private fun DeviceId.key() = "${kind.name}:$address"

    private fun secretKey(): SecretKey {
        val keystore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keystore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val DEFAULT_ALIAS = "lumora_ble_credential_key"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
