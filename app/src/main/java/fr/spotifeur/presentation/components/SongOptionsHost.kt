package fr.spotifeur.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import fr.spotifeur.data.model.Song
import fr.spotifeur.data.model.toMusic
import fr.spotifeur.data.model.toSong
import fr.spotifeur.presentation.navigation.openMusic
import fr.spotifeur.presentation.viewmodel.PlayerViewModel

/** The track sheet, wired for screens outside the player. */
@Composable
fun SongOptionsHost(
    ui: PlayerViewModel,
    navController: NavController,
    selected: Song?,
    context: List<Song>,
    queueName: String,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val song = selected ?: return
    val saved by ui.model.favorites.collectAsStateWithLifecycle()
    val isFavorite = song.id in saved
    SongInfoBottomSheet(
        song = song.copy(isFavorite = isFavorite),
        isFavorite = isFavorite,
        playerViewModel = ui,
        onToggleFavorite = { ui.toggleFavoriteSpecificSong(song) },
        onDismiss = onDismiss,
        onPlaySong = { ui.playSongs(context.ifEmpty { listOf(song) }, song, queueName) },
        onAddToQueue = { ui.addSongToQueue(song) },
        onAddNextToQueue = { ui.addSongNextToQueue(song) },
        onNavigateToAlbum = { onDismiss(); ui.openAlbumOf(song) },
        onNavigateToArtist = { onDismiss(); ui.openArtistOf(song) },
        onStartRadio = { onDismiss(); ui.startRadio(song.toMusic()) { navController.openMusic(ui, it) } },
        onRemoveFromPlaylist = onRemoveFromPlaylist,
    )
}

/** The long-press menu of any library element: a track menu, or its album/artist/playlist menu. */
@Composable
fun MusicOptionsHost(ui: PlayerViewModel, navController: NavController, selected: Music?, onDismiss: () -> Unit) {
    val item = selected ?: return
    if (item.kind == MusicKind.Track) {
        val song = item.toSong()
        SongOptionsHost(ui, navController, song, listOf(song), item.title, null, onDismiss)
    } else {
        EntityOptionsSheet(
            item = item,
            playerViewModel = ui,
            onOpen = { navController.openMusic(ui, item) },
            onStartRadio = { onDismiss(); ui.startRadio(item) { navController.openMusic(ui, it) } },
            onDismiss = onDismiss,
        )
    }
}
