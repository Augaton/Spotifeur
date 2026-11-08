package fr.spotifeur.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal open class SpotifyFailure(message: String, val status: Int? = null) : IOException(message)

/** The saved account itself was refused. Anything else leaves the session in place. */
internal class SpotifyAuthRequired(message: String) : SpotifyFailure(message, 401)

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

    init {
        NetworkMonitor.onConnectionsInvalidated {
            // Closing a TLS socket may write to it, which the main thread must not do.
            if (!NetworkMonitor.online.value) client.dispatcher.cancelAll()
            client.dispatcher.executorService.execute { client.connectionPool.evictAll() }
        }
    }

    suspend fun send(request: Request, operation: String): ByteArray = suspendCancellableCoroutine { continuation ->
        if (!NetworkMonitor.online.value) {
            continuation.resumeWithException(SpotifyFailure("$operation: connection unavailable."))
            return@suspendCancellableCoroutine
        }
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(SpotifyFailure("$operation: connection unavailable."))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful) {
                            throw SpotifyFailure("$operation: HTTP response ${response.code}.", response.code)
                        }
                        val body = response.body ?: throw SpotifyFailure("$operation: empty response.")
                        val source = body.source()
                        if (source.request(MAX_RESPONSE_BYTES + 1)) {
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
        const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024
    }
}
