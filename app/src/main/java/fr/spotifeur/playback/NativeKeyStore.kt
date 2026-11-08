package fr.spotifeur.playback

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores native key-response envelopes; only Orbit interprets their protected contents. */
internal class NativeKeyStore(context: Context, account: String) {
    private val accountId = MessageDigest.getInstance("SHA-256").digest(account.toByteArray())
        .joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
    private val root = File(context.noBackupFilesDir, "native-keys/$accountId")
    private val alias = "spotifeur-native-keys-$accountId"
    private fun file(id: String): AtomicFile {
        require(id.matches(Regex("[0-9a-f]{40}")))
        return AtomicFile(File(root, id))
    }
    @Synchronized private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(id: String): ByteArray? = runCatching {
        val stored = file(id).readFully()
        require(stored.size in 29..MAX_BYTES + 28)
        val secret = key(false) ?: return null
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, stored.copyOfRange(0, 12)))
            updateAAD("$accountId:$id".toByteArray())
            doFinal(stored, 12, stored.size - 12)
        }
    }.onFailure { android.util.Log.d("SpotifeurNativeKeys", "Envelope unavailable: ${it.javaClass.simpleName}") }.getOrNull()

    fun save(id: String, response: ByteArray) {
        if (response.isEmpty() || response.size > MAX_BYTES) return
        runCatching {
            val encrypted = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, checkNotNull(key(true)))
                updateAAD("$accountId:$id".toByteArray())
                iv + doFinal(response)
            }
            root.mkdirs()
            val target = file(id)
            val output = target.startWrite()
            try { output.write(encrypted); target.finishWrite(output); android.util.Log.d("SpotifeurNativeKeys", "Envelope persisted") }
            catch (failure: Exception) { target.failWrite(output); throw failure }
        }.onFailure { android.util.Log.w("SpotifeurNativeKeys", "Envelope persistence failed: ${it.javaClass.simpleName}") }
    }
    companion object { private const val MAX_BYTES = 65536 }
}
