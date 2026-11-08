package fr.spotifeur.presentation.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import fr.spotifeur.R
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.LOCAL_PLAYLIST_PREFIX
import fr.spotifeur.presentation.components.PlaylistNameDialog
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import fr.spotifeur.data.model.Song
import fr.spotifeur.data.model.toSong
import fr.spotifeur.presentation.components.PlaylistSortBottomSheet
import fr.spotifeur.presentation.viewmodel.PlaylistSort
import fr.spotifeur.presentation.viewmodel.sortedForPlaylist
import fr.spotifeur.presentation.components.SongOptionsHost
import fr.spotifeur.presentation.components.subcomps.EnhancedSongListItem
import fr.spotifeur.presentation.navigation.openMusic
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import fr.spotifeur.data.Music
import fr.spotifeur.data.SpotifyAccount
import fr.spotifeur.presentation.components.SpotifyPlaylistEditor

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlaylistDetailScreen(model: SpotifeurModel, navController: NavHostController, playlistId: String) {
    val ui = model.ui
    val context = LocalContext.current
    val item = remember(playlistId) { ui.entity(playlistId) }
    if (item == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    val details by model.details.collectAsStateWithLifecycle()
    val pins by model.pins.collectAsStateWithLifecycle()
    val saved by model.favorites.collectAsStateWithLifecycle()
    val stable by ui.stablePlayerState.collectAsStateWithLifecycle()
    LaunchedEffect(playlistId) { model.loadDetail(item) }
    val detail = details[playlistId]
    val content = detail?.content
    val current = content?.item ?: item
    var sort by remember(playlistId) { mutableStateOf(ui.playlistSort(playlistId)) }
    var sortMenu by remember { mutableStateOf(false) }
    // Positions in the stored playlist, so removing a track still targets the right one once sorted.
    val ordered = remember(content?.tracks, saved, sort) {
        content?.tracks.orEmpty().mapIndexed { index, music -> index to music.toSong(music.id in saved) }
            .sortedForPlaylist(sort) { it.second.title }
    }
    val songs = remember(ordered) { ordered.map { it.second } }
    val isPinned = pins.any { it.id == playlistId }
    val isLiked = playlistId == ui.likedSongs.id
    val isLocalList = playlistId.startsWith(LOCAL_PLAYLIST_PREFIX)
    val scope = rememberCoroutineScope()
    val online by model.online.collectAsStateWithLifecycle()
    var isOwned by remember(playlistId) { mutableStateOf(false) }
    var suggestions by remember(playlistId) { mutableStateOf<List<Music>>(emptyList()) }
    var suggestionsLoading by remember { mutableStateOf(false) }
    var suggestionError by remember { mutableStateOf<String?>(null) }
    var addingSuggestion by remember { mutableStateOf<String?>(null) }
    var suggestionRevision by remember { mutableStateOf(0) }
    LaunchedEffect(playlistId, current.ownerUsername, online) {
        if (!isLocalList && !isLiked && online) {
            isOwned = runCatching {
                current.ownerUsername?.let { it == SpotifyAccount.require(context).username() } ?: model.spotifyPlaylists.isOwned(playlistId)
            }.getOrDefault(false)
        }
    }
    LaunchedEffect(isOwned, content?.tracks, online, suggestionRevision) {
        if (!isOwned || !online || content == null) return@LaunchedEffect
        suggestionsLoading = true; suggestionError = null
        try { suggestions = model.playlistSuggestions(current, content.tracks).let { list ->
            val offset = if (list.isEmpty()) 0 else (suggestionRevision * 5) % list.size
            list.drop(offset) + list.take(offset)
        } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { suggestionError = failure.message }
        finally { suggestionsLoading = false }
    }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableStateOf(-1) }
    var selected by remember { mutableStateOf<Song?>(null) }
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Stable identities let a track appearing twice be dragged without the list jumping.
    val rowKeys = remember(songs) {
        val seen = HashMap<String, Int>()
        songs.map { song -> val occurrence = seen.merge(song.id, 1, Int::plus) ?: 1; "${song.id}#$occurrence" }
    }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        if (from.index > 0 && to.index > 0) model.localPlaylistStore.move(playlistId, from.index - 1, to.index - 1)
    }

    // Another order only means something once the whole playlist is there.
    LaunchedEffect(sort, content?.nextPage, detail?.loading) {
        if (sort != PlaylistSort.DEFAULT && content?.nextPage != null && detail?.loading != true) model.loadDetail(item, more = true)
    }
    LaunchedEffect(sort) { listState.scrollToItem(0) }

    LaunchedEffect(listState, songs.size, content?.nextPage) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (content?.nextPage != null && last >= songs.size - 12) model.loadDetail(item, more = true) }
    }

    CollectionDetailLayout(
        current.copy(subtitle = current.ownerUsername ?: current.subtitle),
        content?.total ?: songs.size, ui, navController, listState,
    ) {
        item(key = "playlist_actions") {
            DetailActionsRow(current, !isLocalList && !isLiked, playlistId in saved, isPinned,
                { model.toggleFavorite(current) }, { ui.togglePin(current) },
                { ui.startRadio(current) { navController.openMusic(ui, it) } }, ui,
            ) {
                FilledTonalIconButton(onClick = { sortMenu = true }) {
                    Icon(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.playlist_sort))
                }
                if (isOwned || isLocalList) FilledTonalIconButton(onClick = { renaming = true }, enabled = isLocalList || online) {
                    Icon(Icons.Rounded.Edit, stringResource(R.string.spotify_playlist_edit))
                }
                if (isLocalList) FilledTonalIconButton(onClick = { deleting = true }) {
                    Icon(Icons.Rounded.Delete, stringResource(R.string.playlist_delete))
                }
            }
            fr.spotifeur.presentation.components.CollectionDownloadProgress(ui, current)
        }
        if (songs.isEmpty() && detail?.loading != false) item {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
        }
        if (detail?.error != null) item { Text(detail.error, color = MaterialTheme.colorScheme.error) }
                    if (songs.isEmpty()) item { Text(stringResource(R.string.playlist_empty_title), Modifier.padding(16.dp)) }
                    itemsIndexed(songs, key = { index, song -> rowKeys.getOrElse(index) { "$index-${song.id}" } }, contentType = { _, _ -> "playlist_song" }) { index, song ->
                        val isCurrent = stable.currentSong?.id == song.id
                        val row: @Composable (Modifier) -> Unit = { modifier ->
                            EnhancedSongListItem(
                                modifier = modifier,
                                song = song,
                                isPlaying = isCurrent && stable.isPlaying,
                                isCurrentSong = isCurrent,
                                onMoreOptionsClick = { selected = it; selectedIndex = index },
                                onLongPress = { selected = song; selectedIndex = index },
                                onClick = { ui.playSongs(songs, song, current.title) },
                            )
                        }
                        if (isLocalList && sort == PlaylistSort.DEFAULT) {
                            ReorderableItem(reorderState, key = rowKeys[index]) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = {}, modifier = Modifier.draggableHandle()) {
                                        Icon(Icons.Rounded.DragHandle, stringResource(R.string.playlist_rename))
                                    }
                                    row(Modifier.weight(1f))
                                }
                            }
                        } else row(Modifier)
                    }
                    if (isOwned) {
                        item(key = "recommendation_heading") {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.spotify_playlist_suggestions), style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.spotify_playlist_suggestions_description), style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { suggestionRevision++ }, enabled = online && !suggestionsLoading) { Text(stringResource(R.string.spotify_playlist_refresh)) }
                            }
                        }
                        if (suggestionsLoading) item { androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(12.dp)) }
                        if (suggestionError != null) item { Text(suggestionError.orEmpty(), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
                        itemsIndexed(suggestions.take(5), key = { _, track -> "suggestion:${track.id}" }) { _, track ->
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                coil.compose.AsyncImage(track.imageUrl, null, Modifier.size(48.dp))
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(track.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(enabled = online && addingSuggestion == null, onClick = {
                                    addingSuggestion = track.id
                                    scope.launch {
                                        try {
                                            model.addSpotifyPlaylistTracks(current, listOf(track))
                                            suggestions = suggestions.filterNot { it.id == track.id }
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (failure: Exception) { model.showMessage(failure.message.orEmpty()) }
                                        finally { addingSuggestion = null }
                                    }
                                }) { Icon(Icons.Rounded.Add, stringResource(R.string.spotify_playlist_add_suggestion)) }
                            }
                        }
                    }
    }
    if (sortMenu) PlaylistSortBottomSheet(
        options = listOf(
            PlaylistSort.DEFAULT to R.string.sort_display_default_order,
            PlaylistSort.REVERSED to R.string.playlist_sort_reversed,
            PlaylistSort.TITLE to R.string.sort_display_title_az,
        ),
        selected = sort,
        onDismiss = { sortMenu = false },
    ) { option ->
        sortMenu = false
        sort = option
        ui.setPlaylistSort(playlistId, option)
    }
    SongOptionsHost(
        ui, navController, selected, songs, current.title,
        onRemoveFromPlaylist = if (isLocalList) ({ ordered.getOrNull(selectedIndex)?.let { model.localPlaylistStore.removeAt(playlistId, it.first) } }) else null,
    ) { selected = null }
    if (renaming && isOwned) SpotifyPlaylistEditor(model, playlist = current, onDismiss = { renaming = false })
    if (renaming && isLocalList) PlaylistNameDialog(stringResource(R.string.playlist_rename), current.title, { renaming = false }) { name ->
        model.localPlaylistStore.rename(playlistId, name)
        renaming = false
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text(stringResource(R.string.playlist_delete)) },
        text = { Text(stringResource(R.string.playlist_delete_confirm, current.title)) },
        confirmButton = {
            TextButton(onClick = {
                deleting = false
                model.localPlaylistStore.delete(playlistId)
                navController.popBackStack()
            }) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}
