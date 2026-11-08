package fr.spotifeur.data.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import fr.spotifeur.R

@Immutable
enum class LibraryTabId(val storageKey: String, @StringRes val titleRes: Int) {
    PLAYLISTS("PLAYLISTS", R.string.library_tab_playlists),
    LIKED("LIKED", R.string.library_tab_liked),
    ALBUMS("ALBUMS", R.string.library_tab_albums),
    ARTISTS("ARTIST", R.string.library_tab_artists),
    LOCAL("LOCAL", R.string.library_tab_local),
    DOWNLOADS("DOWNLOADS", R.string.library_tab_downloads),
}
