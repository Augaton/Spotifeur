package fr.spotifeur.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.spotifeur.R
import fr.spotifeur.data.Music
import fr.spotifeur.presentation.viewmodel.PlayerViewModel
import fr.spotifeur.playback.downloadProgressText

@Composable
fun CollectionDownloadButton(ui: PlayerViewModel, item: Music) {
    val collections by ui.downloadCollections.collectAsStateWithLifecycle()
    val preparations by ui.downloadPreparations.collectAsStateWithLifecycle()
    val progress = collections[item.id]
    val complete = progress?.isComplete == true
    val requested = progress != null || item.id in preparations
    FilledTonalIconButton(
        onClick = { ui.toggleDownloadItem(item) },
        colors = if (complete) IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
        ) else IconButtonDefaults.filledTonalIconButtonColors(),
    ) {
        Icon(if (complete) Icons.Rounded.DownloadForOffline else Icons.Outlined.DownloadForOffline,
            stringResource(if (complete) R.string.download_remove else if (requested) R.string.download_cancel else R.string.download_action))
    }
}

@Composable
fun CollectionDownloadProgress(ui: PlayerViewModel, item: Music) {
    val collections by ui.downloadCollections.collectAsStateWithLifecycle()
    val preparations by ui.downloadPreparations.collectAsStateWithLifecycle()
    if (item.id in preparations) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.download_preparing), style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        return
    }
    val progress = collections[item.id] ?: return
    if (progress.isComplete) return
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(downloadProgressText(context, progress.percent, progress.completed, progress.total, progress.remainingSeconds),
            style = MaterialTheme.typography.labelMedium)
        LinearProgressIndicator(progress = { progress.percent / 100f }, modifier = Modifier.fillMaxWidth())
        if (progress.paused) Text(stringResource(R.string.download_waiting_network), style = MaterialTheme.typography.bodySmall)
        if (progress.failed) TextButton(onClick = { ui.downloadItem(item) }) { Text(stringResource(R.string.download_retry_needed)) }
    }
}
