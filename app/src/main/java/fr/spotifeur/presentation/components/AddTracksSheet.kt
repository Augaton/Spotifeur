package fr.spotifeur.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.spotifeur.R
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.Music
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Searches tracks and adds them to [playlist]. A Spotify playlist takes Spotify tracks and local
 * files; a multi-service playlist also takes YouTube Music tracks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTracksSheet(
    model: SpotifeurModel,
    playlist: Music,
    spotifyPlaylist: Boolean,
    onAddLocal: (List<Music>) -> Unit,
    onAddSpotify: suspend (List<Music>) -> Unit,
    onDismiss: () -> Unit,
) {
    val discovery by model.discovery.collectAsStateWithLifecycle()
    val sources = buildList {
        if (discovery.spotifyEnabled) add(SpotifeurModel.TRACKS_SPOTIFY to R.string.search_source_spotify)
        if (!spotifyPlaylist) add(SpotifeurModel.TRACKS_YOUTUBE to R.string.search_source_youtube)
        add(SpotifeurModel.TRACKS_FILES to R.string.library_tab_local)
    }
    var source by remember { mutableStateOf(sources.first().first) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Music>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf<Set<String>>(emptySet()) }
    var adding by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.playlist_add_failed)
    LaunchedEffect(query, source) {
        if (query.trim().length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        loading = true
        results = try { model.searchTracks(query, source) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.playlist_add_tracks_to, playlist.title), Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, placeholder = { Text(stringResource(R.string.search_placeholder)) },
                shape = RoundedCornerShape(28.dp),
            )
            if (sources.size > 1) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                sources.forEachIndexed { index, (key, label) ->
                    SegmentedButton(selected = source == key, onClick = { source = key },
                        shape = SegmentedButtonDefaults.itemShape(index, sources.size)) { Text(stringResource(label), maxLines = 1) }
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 520.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(results, key = { it.id }) { track ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        SmartImage(model = track.imageUrl, contentDescription = null, shape = RoundedCornerShape(12.dp),
                            contentScale = ContentScale.Crop, targetSize = SmartImageCompactListTargetSize, modifier = Modifier.size(48.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SourceBadge(track.source, Modifier.padding(end = 6.dp))
                                Text(track.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        val done = track.id in added
                        FilledTonalIconButton(
                            enabled = !done && adding == null,
                            colors = if (done) IconButtonDefaults.filledTonalIconButtonColors(
                                disabledContainerColor = MaterialTheme.colorScheme.primary, disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                            ) else IconButtonDefaults.filledTonalIconButtonColors(),
                            onClick = {
                                if (!spotifyPlaylist) { onAddLocal(listOf(track)); added = added + track.id }
                                else {
                                    adding = track.id
                                    scope.launch {
                                        try { onAddSpotify(listOf(track)); added = added + track.id }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (failure: Exception) { model.showMessage(failure.message ?: failed) }
                                        finally { adding = null }
                                    }
                                }
                            },
                        ) { Icon(if (done) Icons.Rounded.Check else Icons.Rounded.Add, stringResource(R.string.playlist_add_tracks)) }
                    }
                }
            }
        }
    }
}

private val Music.source get() = fr.spotifeur.data.musicSourceOf(id)
