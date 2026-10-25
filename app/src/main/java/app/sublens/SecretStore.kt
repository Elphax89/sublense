package app.sublens

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps API keys encrypted. The encryption key lives in the Android Keystore and never leaves the
 * device; only the encrypted key text is stored in the app's settings (and is useless when copied elsewhere).
 */
class SecretStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("sublens_secrets", Context.MODE_PRIVATE)

    fun get(name: String): String? {
        val stored = sp.getString(name, null) ?: return null
        return try {
            val (ivB64, dataB64) = stored.split(':', limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(dataB64, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            // E.g. restored from a backup on another phone: the Keystore key is gone.
            Log.w("SubLens", "Stored key unreadable", e)
            null
        }
    }

    fun put(name: String, value: String?) {
        if (value.isNullOrBlank()) {
            sp.edit().remove(name).apply()
            return
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val data = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
            val text = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
            sp.edit().putString(name, text).apply()
        } catch (e: Exception) {
            Log.w("SubLens", "Could not store key", e)
        }
    }

    fun has(name: String): Boolean = sp.contains(name)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "sublens_api_keys"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
