package fr.spotifeur.data

import fr.spotifeur.data.diagnostics.DiagnosticLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import android.os.SystemClock
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal open class SpotifyFailure(message: String, val status: Int? = null) : IOException(message)

/** The saved account itself was refused. Anything else leaves the session in place. */
internal class SpotifyAuthRequired(message: String) : SpotifyFailure(message, 401)

/** HTTP 429: the service asks this account or address to wait before the next request. */
internal class SpotifyRateLimited(message: String, val waitMs: Long) : SpotifyFailure(message, 429)

/**
 * Marks requests made to keep the library available offline. They wait for a rate limit to end and
 * give way to what the user opened.
 */
internal object BackgroundRequests : AbstractCoroutineContextElement(BackgroundRequestsKey)
internal object BackgroundRequestsKey : CoroutineContext.Key<BackgroundRequests>

internal class SpotifyTransport {
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", SpotifyAndroid.userAgent).build())
        }
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .connectTimeout(6, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
    /** Per host: elapsed-realtime instant before which the service asked not to be called. */
    private val limitedUntil = ConcurrentHashMap<String, Long>()
    private val foreground = AtomicInteger()

    init {
        NetworkMonitor.onConnectionsInvalidated {
            // Closing a TLS socket may write to it, which the main thread must not do.
            if (!NetworkMonitor.online.value) client.dispatcher.cancelAll()
            client.dispatcher.executorService.execute { client.connectionPool.evictAll() }
        }
    }

    suspend fun send(request: Request, operation: String): ByteArray {
        val background = coroutineContext[BackgroundRequestsKey] != null
        val host = request.url.host
        var limited = 0
        if (!background) foreground.incrementAndGet()
        try {
            while (true) {
                if (background) {
                    // What the user opened goes first; the library sync can wait.
                    var waited = 0
                    while (foreground.get() > 0 && waited < MAX_YIELD_MS) { delay(100); waited += 100 }
                }
                val wait = (limitedUntil[host] ?: 0) - SystemClock.elapsedRealtime()
                if (wait > 0) delay(if (background) wait else minOf(wait, MAX_FOREGROUND_WAIT_MS))
                try { return sendOnce(request, operation) }
                catch (failure: SpotifyRateLimited) {
                    limitedUntil.merge(host, SystemClock.elapsedRealtime() + failure.waitMs, ::maxOf)
                    DiagnosticLog.record("http", "rate-limited operation=\"$operation\" wait=${failure.waitMs / 1000}s background=$background")
                    if (++limited > (if (background) MAX_BACKGROUND_RETRIES else MAX_FOREGROUND_RETRIES)) throw failure
                }
            }
        } finally { if (!background) foreground.decrementAndGet() }
    }

    private suspend fun sendOnce(request: Request, operation: String): ByteArray = suspendCancellableCoroutine { continuation ->
        if (!NetworkMonitor.online.value) {
            continuation.resumeWithException(SpotifyFailure("$operation: connection unavailable."))
            return@suspendCancellableCoroutine
        }
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) DiagnosticLog.record("http", "failed operation=\"$operation\" cause=${e.javaClass.simpleName}")
                continuation.resumeWithException(SpotifyFailure("$operation: connection unavailable."))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (response.code == 429) {
                            val seconds = response.header("Retry-After")?.trim()?.toLongOrNull()
                            throw SpotifyRateLimited("$operation: Spotify is limiting requests. Try again in a minute.",
                                (seconds ?: DEFAULT_RETRY_AFTER_SECONDS).coerceIn(1, MAX_RETRY_AFTER_SECONDS) * 1000)
                        }
                        if (!response.isSuccessful) {
                            DiagnosticLog.record("http", "status=${response.code} operation=\"$operation\"")
                            throw SpotifyFailure("$operation: HTTP response ${response.code}.", response.code)
                        }
                        val body = response.body ?: throw SpotifyFailure("$operation: empty response.")
                        val source = body.source()
                        if (source.request(MAX_RESPONSE_BYTES + 1)) {
                            DiagnosticLog.record("http", "too-large operation=\"$operation\"")
                            throw SpotifyFailure("$operation: response too large.")
                        }
                        continuation.resume(source.readByteArray())
                    } catch (failure: IOException) {
                        continuation.resumeWithException(failure)
                    }
                }
            }
        })
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024
        const val DEFAULT_RETRY_AFTER_SECONDS = 5L
        const val MAX_RETRY_AFTER_SECONDS = 120L
        const val MAX_FOREGROUND_WAIT_MS = 15_000L
        const val MAX_FOREGROUND_RETRIES = 2
        const val MAX_BACKGROUND_RETRIES = 6
        const val MAX_YIELD_MS = 10_000
    }
}
