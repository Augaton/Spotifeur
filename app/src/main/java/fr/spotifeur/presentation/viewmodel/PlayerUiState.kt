package fr.spotifeur.presentation.viewmodel

import androidx.compose.runtime.Immutable
import fr.spotifeur.data.model.Song
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class PlayerUiState(
    val currentPlaybackQueue: ImmutableList<Song> = persistentListOf(),
    val currentQueueSourceName: String = "",
    val preparingSongId: String? = null,
    val showDismissUndoBar: Boolean = false,
    val undoBarVisibleDuration: Long = 4000L,
    val showQueueItemUndoBar: Boolean = false,
    val lastRemovedQueueSong: Song? = null,
    val lastRemovedQueueIndex: Int = -1,
)
