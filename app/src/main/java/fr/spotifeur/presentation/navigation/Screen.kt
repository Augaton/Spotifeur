package fr.spotifeur.presentation.navigation

import androidx.compose.runtime.Immutable
import java.net.URLEncoder

@Immutable
sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Search : Screen("search")
    object Library : Screen("library")
    object Settings : Screen("settings")
    object Equalizer : Screen("equalizer")
    object History : Screen("history")
    object GoogleSignIn : Screen("google_sign_in")
    object Convert : Screen("convert/{playlistId}/{target}") {
        fun createRoute(playlistId: String, target: String) = "convert/${URLEncoder.encode(playlistId, "UTF-8")}/$target"
    }
    object SettingsCategory : Screen("settings_category/{categoryId}") {
        fun createRoute(categoryId: String) = "settings_category/$categoryId"
    }
    object PlaylistDetail : Screen("playlist_detail/{playlistId}") {
        fun createRoute(playlistId: String) = "playlist_detail/${URLEncoder.encode(playlistId, "UTF-8")}"
    }
    object AlbumDetail : Screen("album_detail/{albumId}") {
        fun createRoute(albumId: Long) = "album_detail/$albumId"
    }
    object ArtistDetail : Screen("artist_detail/{artistId}") {
        fun createRoute(artistId: Long) = "artist_detail/$artistId"
    }
}
