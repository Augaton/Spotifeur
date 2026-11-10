package fr.spotifeur.presentation.components

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.spotifeur.R
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.Music
import fr.spotifeur.data.isLocalPlaylist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Imports a CSV file exported by Spotifeur, or a YouTube, YouTube Music or Spotify playlist link. */
@Composable
fun ImportPlaylistDialog(model: SpotifeurModel, onDismiss: () -> Unit, onImported: (Music) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.import_failed)
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun run(action: suspend () -> Music) {
        busy = true; error = null
        scope.launch {
            try { onImported(action()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = (failure as? IllegalArgumentException)?.message ?: failed }
            finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run {
            val (name, text) = withContext(Dispatchers.IO) { displayName(context, uri) to readText(context, uri) }
            model.importCsv(text, name)
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.playlist_import)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/csv", "*/*")) },
                    enabled = !busy, modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.import_from_file)) }
                Text(stringResource(R.string.import_from_link), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = link, onValueChange = { link = it.trim() }, singleLine = true, enabled = !busy,
                    label = { Text(stringResource(R.string.import_link_label)) }, modifier = Modifier.fillMaxWidth(),
                )
                if (busy) {
                    Text(stringResource(R.string.import_running), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { run { model.importLink(link) } }, enabled = !busy && link.isNotBlank()) {
                Text(stringResource(R.string.import_action))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Returns a function that asks where to save a playlist as CSV, then writes it there. */
@Composable
fun rememberPlaylistCsvExport(model: SpotifeurModel): (Music) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exported = stringResource(R.string.playlist_exported)
    val failed = stringResource(R.string.backup_failed)
    var pending by remember { mutableStateOf<Music?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val item = pending
        pending = null
        if (uri != null && item != null) scope.launch {
            val ok = try {
                val csv = model.exportCsv(item)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) } ?: error("No output")
                }
                true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            Toast.makeText(context, if (ok) exported else failed, Toast.LENGTH_SHORT).show()
        }
    }
    return { item ->
        pending = item
        launcher.launch(item.title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "playlist" } + ".csv")
    }
}

/** Confirms, then deletes a multi-service playlist from the device or a Spotify playlist from the library. */
@Composable
fun DeletePlaylistDialog(model: SpotifeurModel, item: Music, onDismiss: () -> Unit, onConfirmed: () -> Unit = {}) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_delete)) },
        text = {
            Text(stringResource(when {
                item.isLocalPlaylist -> R.string.playlist_delete_confirm
                item.id.startsWith(fr.spotifeur.data.youtube.YTM_PLAYLIST_PREFIX) ->
                    if (model.youTubePlaylistOwned(item)) R.string.playlist_delete_youtube_confirm else R.string.playlist_remove_youtube_confirm
                else -> R.string.playlist_delete_spotify_confirm
            }, item.title))
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); model.deletePlaylist(item); onConfirmed() }) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun readText(context: android.content.Context, uri: Uri): String =
    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("Unreadable file")

private fun displayName(context: android.content.Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "playlist.csv"
