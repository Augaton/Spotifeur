package fr.spotifeur.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.*
import androidx.media3.extractor.*
import androidx.media3.extractor.mp4.*
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.*

@UnstableApi
internal class NativeHlsExtractor(private val audio: NativeAudioProvider) : HlsExtractorFactory by DefaultHlsExtractorFactory() {
    override fun createExtractor(uri: Uri, format: Format, captions: MutableList<Format>?, adjuster: TimestampAdjuster,
                                 headers: MutableMap<String, MutableList<String>>, input: ExtractorInput, playerId: PlayerId): HlsMediaChunkExtractor {
        val mp4 = object : FragmentedMp4Extractor(0, adjuster) {
            override fun modifyTrack(track: Track?): Track? {
                if (track?.type == C.TRACK_TYPE_AUDIO) {
                    track.getSampleDescriptionEncryptionBox(0)?.let { box ->
                        audio.prepare(box.cryptoData.encryptionKey, NativeAudioScheme.fromWireName(checkNotNull(box.schemeType)),
                            NativeAudioProtocol.fileId(uri.toString(), box.cryptoData.encryptionKey))
                    }
                }
                return track
            }
        }
        val delegate = BundledHlsMediaChunkExtractor(mp4, format, adjuster)
        return object : HlsMediaChunkExtractor by delegate {
            override fun init(output: ExtractorOutput) {
                delegate.init(object : ExtractorOutput by output {
                    override fun track(id: Int, type: Int): TrackOutput {
                        val target = output.track(id, type)
                        return object : TrackOutput by target {
                            override fun format(format: Format) { target.format(format.buildUpon().setDrmInitData(null).build()) }
                            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                                if (flags and C.BUFFER_FLAG_ENCRYPTED != 0) PlaybackDiagnostics.encryptedSamples.incrementAndGet()
                                else PlaybackDiagnostics.clearSamples.incrementAndGet()
                                target.sampleMetadata(timeUs, flags, size, offset, cryptoData)
                            }
                        }
                    }
                })
            }
        }
    }
}
