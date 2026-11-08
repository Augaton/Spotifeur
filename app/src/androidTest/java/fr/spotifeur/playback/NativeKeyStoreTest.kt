package fr.spotifeur.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeKeyStoreTest {
    @Test fun encryptedAccountBoundAndAuthenticated() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = "vault-test-${System.nanoTime()}"
        val first = NativeKeyStore(context, account)
        val other = NativeKeyStore(context, "$account-other")
        val id = "0123456789abcdef0123456789abcdef01234567"
        val envelope = "native-envelope-fixture-only".toByteArray()
        first.save(id, envelope)
        assertArrayEquals(envelope, NativeKeyStore(context, account).load(id))
        assertNull(other.load(id))
        val root = File(context.noBackupFilesDir, "native-keys")
        val target = root.walkTopDown().first { it.isFile && it.name == id }
        val ciphertext = target.readBytes()
        assertFalse(ciphertext.toList().windowed(envelope.size).any { it == envelope.toList() })
        ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
        target.writeBytes(ciphertext)
        assertNull(first.load(id))
        target.delete()
        assertNull(first.load("../bad"))
    }
}
