package fr.spotifeur.presentation.navigation

import androidx.navigation.NavController
import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import fr.spotifeur.presentation.viewmodel.PlayerViewModel

/** Opens a Spotify entity on the screen for its kind. Tracks start playing instead. */
fun NavController.openMusic(ui: PlayerViewModel, item: Music) {
    val id = ui.register(item)
    when (item.kind) {
        MusicKind.Album -> navigateSafely(Screen.AlbumDetail.createRoute(id))
        MusicKind.Artist -> navigateSafely(Screen.ArtistDetail.createRoute(id))
        MusicKind.Playlist, MusicKind.Radio -> navigateSafely(Screen.PlaylistDetail.createRoute(item.id))
        MusicKind.Track -> ui.model.play(listOf(item), 0)
    }
}
