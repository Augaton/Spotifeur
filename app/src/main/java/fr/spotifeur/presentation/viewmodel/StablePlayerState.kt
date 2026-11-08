package fr.spotifeur.presentation.viewmodel

import androidx.compose.runtime.Immutable
import androidx.media3.common.Player
import fr.spotifeur.data.model.Lyrics
import fr.spotifeur.data.model.Song

@Immutable
data class StablePlayerState(
    val currentSong: Song? = null,
    val currentMediaItemIndex: Int = -1,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val totalDuration: Long = 0L,
    val isShuffleEnabled: Boolean = false,
    val isShuffleTransitionInProgress: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val isBuffering: Boolean = false,
    val isLoadingLyrics: Boolean = false,
    val lyrics: Lyrics? = null,
)
