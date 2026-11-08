package fr.spotifeur.playback

import android.content.Context
import android.net.Uri
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setStopReceiverApplicationWhenEndingSession(true)
        .build()
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/**
 * Serves local audio files to the Cast device of the running session, and to nothing else: unknown
 * peers are refused, paths are unguessable and only files of the casted queue are reachable.
 */
internal class LocalCastServer(context: Context, private val allowedPeer: InetAddress, private val bindAddress: InetAddress) : AutoCloseable {
    private val app = context.applicationContext
    private val files = ConcurrentHashMap<String, Uri>()
    private val server = ServerSocket(0, 16, bindAddress)
    private val random = SecureRandom()

    init {
        thread(name = "cast-file-server", isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                thread(name = "cast-file-request", isDaemon = true) { socket.use(::serve) }
            }
        }
    }

    fun publish(uri: Uri): String {
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        files[token] = uri
        return "http://${bindAddress.hostAddress}:${server.localPort}/$token"
    }

    private fun serve(socket: Socket) {
        if (socket.inetAddress != allowedPeer) return
        val input = socket.getInputStream().bufferedReader()
        val request = input.readLine()?.split(' ') ?: return
        var rangeStart = 0L
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) {
                rangeStart = line.substringAfter("bytes=", "0").substringBefore('-').toLongOrNull() ?: 0L
            }
        }
        val output = socket.getOutputStream()
        val uri = files[request.getOrNull(1)?.trimStart('/').orEmpty()]
        if (uri == null || request.firstOrNull() !in setOf("GET", "HEAD")) { respond(output, "404 Not Found", "Content-Length: 0\r\n"); return }
        val resolver = app.contentResolver
        val length = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val type = resolver.getType(uri) ?: "application/octet-stream"
        if (length < 0 || rangeStart >= length && length > 0) { respond(output, "416 Range Not Satisfiable", "Content-Length: 0\r\n"); return }
        val headers = "Content-Type: $type\r\nAccept-Ranges: bytes\r\nContent-Length: ${length - rangeStart}\r\n" +
            if (rangeStart > 0) "Content-Range: bytes $rangeStart-${length - 1}/$length\r\n" else ""
        respond(output, if (rangeStart > 0) "206 Partial Content" else "200 OK", headers)
        if (request.first() == "HEAD") return
        resolver.openInputStream(uri)?.use { raw ->
            BufferedInputStream(raw).use { stream ->
                var skipped = 0L
                while (skipped < rangeStart) {
                    val step = stream.skip(rangeStart - skipped)
                    if (step <= 0) return
                    skipped += step
                }
                try { stream.copyTo(output) } catch (_: IOException) { /* The receiver seeked or left. */ }
            }
        }
    }

    private fun respond(output: OutputStream, status: String, headers: String) {
        output.write("HTTP/1.1 $status\r\n${headers}Connection: close\r\n\r\n".toByteArray())
        output.flush()
    }

    override fun close() {
        files.clear()
        server.close()
    }
}
