package net.qmindtech.tmap.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

private val Context.tokenDataStore by preferencesDataStore(name = "tmap_tokens")

/**
 * Refresh token at rest: a Keystore-resident AES/GCM key encrypts it; ciphertext+IV live (Base64) in a
 * Preferences DataStore. The access token is in-memory only. Crypto stays behind [TokenStore] so the
 * repository/authenticator logic can be tested with FakeTokenStore (AndroidKeyStore is absent under Robolectric).
 */
class KeystoreTokenStore(private val context: Context) : TokenStore {

    override var accessToken: String? = null

    override suspend fun saveRefreshToken(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ct = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        context.tokenDataStore.edit { prefs ->
            prefs[KEY_CIPHERTEXT] = Base64.encodeToString(ct, Base64.NO_WRAP)
            prefs[KEY_IV] = Base64.encodeToString(iv, Base64.NO_WRAP)
        }
    }

    override suspend fun readRefreshToken(): String? {
        val prefs = context.tokenDataStore.data.first()
        val ctB64 = prefs[KEY_CIPHERTEXT] ?: return null
        val ivB64 = prefs[KEY_IV] ?: return null
        val ct = Base64.decode(ctB64, Base64.NO_WRAP)
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    override suspend fun clear() {
        accessToken = null
        context.tokenDataStore.edit { it.remove(KEY_CIPHERTEXT); it.remove(KEY_IV) }
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "tmap_refresh_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        val KEY_CIPHERTEXT = stringPreferencesKey("refresh_ciphertext")
        val KEY_IV = stringPreferencesKey("refresh_iv")
    }
}
