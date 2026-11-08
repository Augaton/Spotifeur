package fr.spotifeur.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class HashcashDeviceTest {
    @Test
    fun solvesLongIndependentVector(): Unit = runBlocking {
        // First sequential solution is counter 8,939,803 (generated independently in Python).
        val context = "spotifeur-hashcash-context".toByteArray()
        val prefix = "spotifeur-regression-long".toByteArray()
        val start = System.nanoTime()
        val suffix = solveHashcash(context, prefix, 24)
        val elapsed = (System.nanoTime() - start) / 1_000_000
        assertEquals(16, suffix.size)
        val sha1 = MessageDigest.getInstance("SHA-1")
        val seed = ByteBuffer.wrap(sha1.digest(context)).getLong(12)
        val counter = ByteBuffer.wrap(suffix).getLong(8)
        assertEquals(seed + counter, ByteBuffer.wrap(suffix).getLong(0))
        assertTrue(counter >= 8_939_803)
        val hash = sha1.digest(prefix + suffix)
        assertTrue(hash.takeLast(3).all { it == 0.toByte() })
        Log.i("SpotifeurHashcashTest", "24-bit challenge solved in ${elapsed} ms.")
    }

    @Test
    fun cancelsAllWorkersPromptly() = runBlocking {
        val computation = async(Dispatchers.Default) {
            solveHashcash(byteArrayOf(1), byteArrayOf(2), 64)
        }
        delay(100)
        computation.cancel()
        withTimeout(2_000) { computation.join() }
        assertTrue(computation.isCancelled)
    }
}
