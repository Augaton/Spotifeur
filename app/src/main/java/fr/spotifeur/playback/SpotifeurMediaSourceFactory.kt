package fr.spotifeur.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/** Spotify tracks keep their native HLS path; anything else is a plain file on the device. */
@UnstableApi
internal class SpotifeurMediaSourceFactory(
    private val spotify: MediaSource.Factory,
    private val files: MediaSource.Factory,
) : MediaSource.Factory {
    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        spotify.setDrmSessionManagerProvider(provider)
        return this
    }
    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        spotify.setLoadErrorHandlingPolicy(policy); files.setLoadErrorHandlingPolicy(policy)
        return this
    }
    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_OTHER)
    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        if (mediaItem.localConfiguration?.uri?.scheme == "spotify") spotify.createMediaSource(mediaItem)
        else files.createMediaSource(mediaItem)
}
