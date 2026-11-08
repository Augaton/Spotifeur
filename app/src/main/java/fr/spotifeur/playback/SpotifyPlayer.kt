package fr.spotifeur.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import fr.spotifeur.data.SpotifyFailure
import fr.spotifeur.data.isLocalTrack
import fr.spotifeur.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Playback(
    val queue: List<Music> = emptyList(), val index: Int = 0, val playing: Boolean = false,
    val buffering: Boolean = false, val positionMs: Int = 0, val durationMs: Int = 0,
    val shuffle: Boolean = false, val repeat: Int = Player.REPEAT_MODE_OFF, val error: String? = null,
    val canNext: Boolean = false,
    val playWhenReady: Boolean = false,
    /** Measured from the selected audio track, for the file information of the player. */
    val audioFormat: String = "",
) { val track: Music? get() = queue.getOrNull(index) }

/** Outlives the activity, like the playback service whose queue it describes. */
private object ShuffleMemory {
    var enabled = false
    private var loaded = false
    /** Restores the last choice once, when the setting asks for it. */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val settings = fr.spotifeur.data.preferences.SettingsStore.get(context).settings.value
        enabled = settings.persistentShuffle && context.getSharedPreferences("player_state", 0).getBoolean("shuffle", false)
    }
    fun save(context: Context) = context.getSharedPreferences("player_state", 0).edit().putBoolean("shuffle", enabled).apply()
    /** Queue order to restore when shuffle is turned off. */
    var original: List<Music>? = null
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SpotifyPlayer(context: Context, scope: CoroutineScope) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(Playback())
    val state = mutableState.asStateFlow()
    private var controller: MediaController? = null
    private var pending: Pair<List<Music>, Int>? = null
    private val future = MediaController.Builder(context,
        SessionToken(context, ComponentName(context, SpotifeurPlaybackService::class.java))).buildAsync()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { update(player) }
    }
    init {
        ShuffleMemory.load(appContext)
        future.addListener({
            try {
                controller = future.get().also { it.addListener(listener) }
                pending?.let { (queue, index) -> pending = null; play(queue, index) } ?: controller?.let(::update)
            } catch (_: Exception) { mutableState.value = mutableState.value.copy(buffering = false, error = "The player could not start.") }
        }, ContextCompat.getMainExecutor(context))
        scope.launch { while (true) { delay(300); controller?.let(::update) } }
    }
    fun play(queue: List<Music>, index: Int = 0) {
        val selected = queue.getOrNull(index) ?: return
        if (!selected.isPlayableTrack()) return
        val playable = queue.filter(Music::isPlayableTrack)
        val first = queue.take(index).count(Music::isPlayableTrack)
        // Shuffle reorders the queue itself: the chosen track plays first and every other one follows.
        ShuffleMemory.original = playable.takeIf { ShuffleMemory.enabled }
        val tracks = if (ShuffleMemory.enabled) listOf(playable[first]) + (playable.take(first) + playable.drop(first + 1)).shuffled() else playable
        val start = if (ShuffleMemory.enabled) 0 else first
        mutableState.value = Playback(tracks, start, buffering = true, durationMs = selected.durationMs, shuffle = ShuffleMemory.enabled, canNext = start < tracks.lastIndex)
        val player = controller ?: run { pending = tracks to start; return }
        player.setMediaItems(tracks.map(::mediaItem), start, 0L); player.prepare(); player.play()
    }
    fun toggle() {
        val player = controller ?: return
        togglePlayback(player)
    }
    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPrevious() }
    fun seek(positionMs: Int) { controller?.let { player ->
        val failed = player.playerError != null
        player.seekTo(positionMs.coerceAtLeast(0).toLong())
        if (failed) player.prepare()
    } }
    fun shuffle() { controller?.let { player ->
        val items = (0 until player.mediaItemCount).map { music(player.getMediaItemAt(it)) }
        val current = player.currentMediaItemIndex
        ShuffleMemory.enabled = !ShuffleMemory.enabled
        ShuffleMemory.save(appContext)
        val original = ShuffleMemory.original
        when {
            items.isEmpty() -> ShuffleMemory.original = null
            ShuffleMemory.enabled -> {
                ShuffleMemory.original = items
                replaceAroundCurrent(player, emptyList(), (items.take(current) + items.drop(current + 1)).shuffled())
            }
            original != null -> {
                ShuffleMemory.original = null
                val position = original.indexOfFirst { it.id == items.getOrNull(current)?.id }
                if (position >= 0) replaceAroundCurrent(player, original.take(position), original.drop(position + 1))
            }
        }
        update(player)
    } }
    /** The current item is never removed, so its audio keeps playing while the queue changes around it. */
    private fun replaceAroundCurrent(player: Player, before: List<Music>, after: List<Music>) {
        val current = player.currentMediaItemIndex
        if (current + 1 < player.mediaItemCount) player.removeMediaItems(current + 1, player.mediaItemCount)
        if (current > 0) player.removeMediaItems(0, current)
        if (after.isNotEmpty()) player.addMediaItems(1, after.map(::mediaItem))
        if (before.isNotEmpty()) player.addMediaItems(0, before.map(::mediaItem))
    }
    fun repeat() { controller?.let { it.repeatMode = (it.repeatMode + 1) % 3 } }
    fun stop() {
        pending = null; ShuffleMemory.original = null
        controller?.stop(); controller?.clearMediaItems(); mutableState.value = Playback(shuffle = ShuffleMemory.enabled)
    }
    fun pause() { controller?.pause() }
    fun setVolume(volume: Float) { controller?.volume = volume }
    fun volume(): Float = controller?.volume ?: 1f
    fun seekToItem(index: Int) { controller?.let { player ->
        val failed = player.playerError != null
        player.seekToDefaultPosition(index)
        if (failed) player.prepare()
        player.play()
    } }
    fun insert(index: Int, tracks: List<Music>) {
        val valid = tracks.filter(Music::isPlayableTrack)
        if (valid.isEmpty()) return
        ShuffleMemory.original = ShuffleMemory.original?.plus(valid)
        controller?.let { it.addMediaItems(index.coerceIn(0, it.mediaItemCount), valid.map(::mediaItem)); update(it) }
    }
    fun removeAt(index: Int) { controller?.let { player ->
        val id = player.getMediaItemAt(index).mediaId
        ShuffleMemory.original = ShuffleMemory.original?.let { list ->
            list.indexOfFirst { it.id == id }.let { found -> if (found < 0) list else list.take(found) + list.drop(found + 1) }
        }
        player.removeMediaItem(index); update(player)
    } }
    fun move(from: Int, to: Int) { controller?.let { it.moveMediaItem(from, to); update(it) } }
    fun clearExceptCurrent() { controller?.let { player ->
        val current = player.currentMediaItemIndex
        if (current + 1 < player.mediaItemCount) player.removeMediaItems(current + 1, player.mediaItemCount)
        if (current > 0) player.removeMediaItems(0, current)
        ShuffleMemory.original = null
        update(player)
    } }
    private fun update(player: Player) {
        val queue = (0 until player.mediaItemCount).map { music(player.getMediaItemAt(it)) }
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        val duration = player.duration.takeUnless { it == C.TIME_UNSET || it < 0 }
            ?: queue.getOrNull(index)?.durationMs?.toLong() ?: 0
        val error = player.playerError?.let { failure ->
            generateSequence<Throwable>(failure) { it.cause }.take(12).filterIsInstance<SpotifyFailure>().firstOrNull()?.message
                ?: if (!fr.spotifeur.data.NetworkMonitor.online.value) "This track is not available offline."
                else playbackErrorMessage(failure.errorCode)
        }
        mutableState.value = Playback(queue, index, player.isPlaying, player.playbackState == Player.STATE_BUFFERING,
            player.currentPosition.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            ShuffleMemory.enabled, player.repeatMode, error, player.hasNextMediaItem(), player.playWhenReady, currentAudioFormat(player))
    }
    override fun close() { controller?.removeListener(listener); MediaController.releaseFuture(future); controller = null }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun currentAudioFormat(player: Player): String {
    val group = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected } ?: return ""
    val format = (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat) ?: return ""
    return fr.spotifeur.data.audioQualityLabel(format.sampleMimeType, format.sampleRate.takeIf { it > 0 }, format.bitrate.takeIf { it > 0 })
}
internal fun togglePlayback(player: Player) {
    if (player.playerError != null) { player.prepare(); player.play() }
    else if (player.playWhenReady) player.pause() else {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }
}
internal fun playbackErrorMessage(code: Int): String = when (code) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_TIMEOUT -> "The audio connection was interrupted. Check the network, then try again."
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Spotify could not provide the audio stream. Try again in a moment."
    PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
    PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED -> "Playback authorization could not be obtained. Try again."
    PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION -> "Spotify does not allow this track to play in this session."
    PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
    PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
    PlaybackException.ERROR_CODE_DECODING_FAILED -> "The received audio stream cannot be decoded. Try again or pick another track."
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "Audio output could not start. Check the audio device, then try again."
    else -> "This track cannot be played. Try again or pick another track."
}
private fun Music.isPlayableTrack(): Boolean =
    kind == MusicKind.Track && (id.startsWith("spotify:track:") || (isLocalTrack && mediaUri != null))

private fun mediaItem(track: Music): MediaItem = MediaItem.Builder().setMediaId(track.id)
    .setUri(track.mediaUri ?: track.id)
    .apply { if (!track.isLocalTrack) setMimeType(MimeTypes.APPLICATION_M3U8) }
    .apply { if (!BuildConfig.NATIVE_RUNTIME && !track.isLocalTrack) setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
        .setLicenseUri("https://spclient.wg.spotify.com/widevine-license/v1/audio/license")
        .setMultiSession(true).setPlayClearContentWithoutKey(true).build()) }
    .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.subtitle)
        .setArtworkUri(fr.spotifeur.data.largestSpotifyImage(track.imageUrl)?.let(Uri::parse)).setIsPlayable(true).setIsBrowsable(false)
        .setExtras(Bundle().apply {
            putInt("duration", track.durationMs); putInt("artwork", track.artwork)
            putString("album", track.album); putString("mediaUri", track.mediaUri); putString("quality", track.quality)
        }).build()).build()
private fun music(item: MediaItem) = musicOf(item)
