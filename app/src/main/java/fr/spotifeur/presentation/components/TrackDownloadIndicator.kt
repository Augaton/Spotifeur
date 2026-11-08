package fr.spotifeur.presentation.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.spotifeur.R
import fr.spotifeur.playback.DownloadCenter

/** A track's stored state is independent of the album or playlist currently displaying it. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun TrackDownloadIndicator(trackId: String, title: String) {
    val downloads by DownloadCenter.downloads.collectAsStateWithLifecycle()
    val entry = downloads[trackId] ?: return
    val label = when {
        entry.isComplete -> stringResource(R.string.track_download_complete, title)
        entry.failed -> stringResource(R.string.track_download_failed, title)
        entry.paused -> stringResource(R.string.track_download_paused, title)
        entry.queued -> stringResource(R.string.track_download_queued, title)
        else -> stringResource(R.string.track_download_progress, title, entry.percent.toInt().coerceIn(0, 99))
    }
    val modifier = Modifier.size(18.dp)
    when {
        entry.isComplete -> Icon(Icons.Rounded.DownloadForOffline, label, modifier, tint = MaterialTheme.colorScheme.primary)
        entry.failed -> Icon(Icons.Outlined.ErrorOutline, label, modifier, tint = MaterialTheme.colorScheme.error)
        entry.paused || (!entry.queued && entry.percent > 0f) -> CircularProgressIndicator(
            progress = { entry.percent / 100f }, modifier = modifier.semantics { contentDescription = label },
            strokeWidth = 2.dp, trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
        )
        else -> CircularProgressIndicator(modifier = modifier.semantics { contentDescription = label }, strokeWidth = 2.dp)
    }
    Spacer(Modifier.width(6.dp))
}
