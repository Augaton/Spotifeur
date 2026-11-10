package fr.spotifeur.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.datasource.HttpDataSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

@UnstableApi
internal object PlaybackDiagnostics : AnalyticsListener {
    val nonSilentBuffers = AtomicLong()
    val decodedBytes = AtomicLong()
    val lastSignalAt = AtomicLong()
    val outputStarts = AtomicLong()
    val encryptedSamples = AtomicLong()
    val clearSamples = AtomicLong()
    @Volatile var lastError: String? = null

    private fun failure(kind: String, error: Exception) {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
        val status = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        val nativeCode = causes.filterIsInstance<NativeAudioException>().firstOrNull()?.nativeCode
        lastError = "$kind:${(error as? PlaybackException)?.errorCodeName.orEmpty()}:${causes.joinToString(">") { it.javaClass.simpleName }}:http=${status ?: 0}:native=${nativeCode ?: 0}"
        Log.w("SpotifeurAudio", lastError!!)
        // Messages are Spotifeur's own for these types; others may carry addresses.
        val reason = causes.lastOrNull { it is NativeAudioException || it is fr.spotifeur.data.SpotifyFailure }?.message
        fr.spotifeur.data.diagnostics.DiagnosticLog.record("player", lastError + (reason?.let { " \"$it\"" } ?: ""))
    }
    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) = failure("player", error)
    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) = failure("sink", audioSinkError)
    override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) = failure("codec", audioCodecError)
    override fun onDrmSessionManagerError(eventTime: AnalyticsListener.EventTime, error: Exception) = failure("drm", error)
    override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) { outputStarts.incrementAndGet() }
}

@UnstableApi
internal class ExtractorProbe : androidx.media3.exoplayer.hls.HlsExtractorFactory by androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory() {
    private val delegate = androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory()
    override fun createExtractor(uri: android.net.Uri, format: androidx.media3.common.Format, captions: MutableList<androidx.media3.common.Format>?, adjuster: androidx.media3.common.util.TimestampAdjuster, headers: MutableMap<String, MutableList<String>>, input: androidx.media3.extractor.ExtractorInput, playerId: androidx.media3.exoplayer.analytics.PlayerId): androidx.media3.exoplayer.hls.HlsMediaChunkExtractor =
        wrap(delegate.createExtractor(uri, format, captions, adjuster, headers, input, playerId))
    private fun wrap(extractor: androidx.media3.exoplayer.hls.HlsMediaChunkExtractor): androidx.media3.exoplayer.hls.HlsMediaChunkExtractor = object : androidx.media3.exoplayer.hls.HlsMediaChunkExtractor by extractor {
        override fun recreate() = wrap(extractor.recreate())
        override fun init(output: androidx.media3.extractor.ExtractorOutput) {
            extractor.init(object : androidx.media3.extractor.ExtractorOutput by output {
                override fun track(id: Int, type: Int): androidx.media3.extractor.TrackOutput {
                    val track = output.track(id, type)
                    if(type != C.TRACK_TYPE_AUDIO) return track
                    return object : androidx.media3.extractor.TrackOutput by track {
                        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: androidx.media3.extractor.TrackOutput.CryptoData?) {
                            if(flags and C.BUFFER_FLAG_ENCRYPTED != 0) PlaybackDiagnostics.encryptedSamples.incrementAndGet()
                            else PlaybackDiagnostics.clearSamples.incrementAndGet()
                            track.sampleMetadata(timeUs, flags, size, offset, cryptoData)
                        }
                    }
                }
            })
        }
    }
}

@UnstableApi
internal class SignalProbe : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val size = inputBuffer.remaining()
        val samples = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var signal = false
        while (samples.remaining() >= 2) if (kotlin.math.abs(samples.short.toInt()) > 32) { signal = true; break }
        PlaybackDiagnostics.decodedBytes.addAndGet(size.toLong())
        if (signal) {
            PlaybackDiagnostics.nonSilentBuffers.incrementAndGet()
            PlaybackDiagnostics.lastSignalAt.set(SystemClock.elapsedRealtime())
        }
        replaceOutputBuffer(size).put(inputBuffer).flip()
    }
}
