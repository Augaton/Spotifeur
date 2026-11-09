package fr.spotifeur.presentation.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.spotifeur.R
import fr.spotifeur.data.MusicSource

/** Compact lists: smaller rows and spacing in the library, playlists and albums. Off by default. */
val LocalCompactLists = staticCompositionLocalOf { false }

/** Whether the tracks of a multi-service playlist show where they come from. */
val LocalSourceBadges = staticCompositionLocalOf { true }

/** Space between the rows of a track or library list. */
@Composable
fun listSpacing(): Dp = if (LocalCompactLists.current) 3.dp else 8.dp

/** A small mark of where a track's audio comes from: the service's logo, or a folder for a file on the device. */
@Composable
fun SourceBadge(source: MusicSource, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current.copy(alpha = 0.7f)) {
    val description = stringResource(when (source) {
        MusicSource.Spotify -> R.string.source_spotify
        MusicSource.YouTubeMusic -> R.string.source_youtube
        MusicSource.Local -> R.string.source_local
    })
    val iconModifier = modifier.size(14.dp)
    when (source) {
        MusicSource.Spotify -> Icon(painterResource(R.drawable.ic_source_spotify), description, iconModifier, tint = tint)
        MusicSource.YouTubeMusic -> Icon(painterResource(R.drawable.ic_source_youtube_music), description, iconModifier, tint = tint)
        MusicSource.Local -> Icon(Icons.Rounded.Folder, description, iconModifier, tint = tint)
    }
}
