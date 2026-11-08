package fr.spotifeur.playback

import android.content.Context
import fr.spotifeur.data.SpotifyAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Retains the opaque envelope, then releases the native subscription instead of keeping a
 * live media handle for every track of a potentially very large queue. */
internal object OfflinePreparation {
    private val gate = Mutex()
    suspend fun retain(context: Context, fileId: String) = gate.withLock {
        val keys = NativeKeyStore(context, SpotifyAccount.require(context).username())
        if (keys.load(fileId) != null) return@withLock
        SpotifyNativeRuntime.open(context).use { runtime ->
            val ready = CompletableFuture<Unit>()
            runtime.subscribe(fileId, { result -> when (result) {
                is MediaHandleResult.Ready -> ready.complete(Unit)
                MediaHandleResult.Failed, MediaHandleResult.Sunset -> ready.completeExceptionally(IOException("Audio preparation rejected."))
                else -> Unit
            } }, ready::completeExceptionally).use {
                runInterruptible(Dispatchers.IO) { ready.get(20, TimeUnit.SECONDS) }
                check(keys.load(fileId) != null) { "Offline preparation missing." }
            }
        }
    }
}
