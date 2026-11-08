package fr.spotifeur.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class SessionVault(context: Context) {
    private val prefs = context.getSharedPreferences("spotify_accounts", Context.MODE_PRIVATE)

    fun load(): StoredCredential? {
        val blob = prefs.getString("login5", null) ?: return null
        val key = keyStore().getKey(KEY, null) as? SecretKey ?: return null
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        require(bytes.size > IV_SIZE)
        val clear = Cipher.getInstance(CIPHER).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, IV_SIZE)))
            doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE)
        }
        return try {
            val json = JSONObject(clear.decodeToString())
            StoredCredential(json.getString("username"), Base64.decode(json.getString("credential"), Base64.NO_WRAP))
        } finally {
            clear.fill(0)
        }
    }

    fun save(credential: StoredCredential) {
        val key = keyStore().getKey(KEY, null) as? SecretKey ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
        ).apply {
            init(KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
        val blob = Cipher.getInstance(CIPHER).run {
            init(Cipher.ENCRYPT_MODE, key)
            check(iv.size == IV_SIZE)
            val clear = JSONObject().put("username", credential.username)
                .put("credential", Base64.encodeToString(credential.bytes, Base64.NO_WRAP))
                .toString().encodeToByteArray()
            try {
                iv + doFinal(clear)
            } finally {
                clear.fill(0)
            }
        }
        prefs.edit { putString("login5", Base64.encodeToString(blob, Base64.NO_WRAP)) }
    }

    /** Short-lived tokens kept next to the account, under the same key, so a start needs no request. */
    fun saveTokens(tokens: JSONObject) {
        val key = keyStore().getKey(KEY, null) as? SecretKey ?: return
        val blob = Cipher.getInstance(CIPHER).run {
            init(Cipher.ENCRYPT_MODE, key)
            check(iv.size == IV_SIZE)
            iv + doFinal(tokens.toString().encodeToByteArray())
        }
        prefs.edit { putString("tokens", Base64.encodeToString(blob, Base64.NO_WRAP)) }
    }

    fun loadTokens(): JSONObject? = try {
        val blob = prefs.getString("tokens", null)
        val key = keyStore().getKey(KEY, null) as? SecretKey
        if (blob == null || key == null) null else {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            JSONObject(Cipher.getInstance(CIPHER).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, IV_SIZE)))
                doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE)
            }.decodeToString())
        }
    } catch (_: java.security.GeneralSecurityException) { null }
    catch (_: org.json.JSONException) { null }
    catch (_: IllegalArgumentException) { null }

    fun removeLegacySession() = prefs.edit { remove("refresh") }

    fun clear() = prefs.edit { remove("login5"); remove("tokens") }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val IV_SIZE = 12
        const val KEY = "spotify-login5-v1"
        const val CIPHER = "AES/GCM/NoPadding"
    }
}
