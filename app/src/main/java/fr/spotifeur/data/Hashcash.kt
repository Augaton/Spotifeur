package fr.spotifeur.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Same Login5/ClientToken suffix and SHA-1 condition, searched in disjoint CPU ranges. */
internal suspend fun solveHashcash(context: ByteArray, prefix: ByteArray, bits: Int): ByteArray = coroutineScope {
    if (bits !in 0..64 || prefix.size !in 1..256) throw SpotifyFailure("Invalid Hashcash challenge.")
    val started = System.nanoTime()
    val seed = ByteBuffer.wrap(MessageDigest.getInstance("SHA-1").digest(context)).getLong(12)
    val count = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    val workers = List(count) { worker ->
        async(Dispatchers.Default) {
            val digest = MessageDigest.getInstance("SHA-1")
            // A single update avoids a provider/JNI call for every candidate prefix.
            val input = prefix.copyOf(prefix.size + 16)
            val candidate = ByteBuffer.wrap(input)
            val hash = ByteArray(20)
            val result = ByteBuffer.wrap(hash)
            var counter = worker.toLong()
            var iterations = 0
            while (true) {
                if (iterations++ and 1023 == 0) {
                    ensureActive()
                    // This is a local resource bound, not a server-supplied challenge expiry.
                    if (System.nanoTime() - started >= 30_000_000_000L) {
                        throw SpotifyFailure("Solving the Spotify challenge is taking too long. Try signing in again.")
                    }
                }
                candidate.putLong(prefix.size, seed + counter).putLong(prefix.size + 8, counter)
                digest.update(input)
                digest.digest(hash, 0, hash.size)
                if (java.lang.Long.numberOfTrailingZeros(result.getLong(12)) >= bits) {
                    return@async input.copyOfRange(prefix.size, input.size)
                }
                counter += count
            }
            @Suppress("UNREACHABLE_CODE")
            error("Unreachable")
        }
    }
    try {
        select { workers.forEach { worker -> worker.onAwait { it } } }
    } finally {
        // Stop the other ranges immediately after a solution, failure or user cancellation.
        workers.forEach { it.cancel() }
    }
}
