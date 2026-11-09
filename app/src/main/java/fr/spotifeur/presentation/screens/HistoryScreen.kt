package fr.spotifeur.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import fr.spotifeur.R
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.HistoryEntry
import fr.spotifeur.data.model.Song
import fr.spotifeur.data.model.toSong
import fr.spotifeur.presentation.components.SongOptionsHost
import fr.spotifeur.presentation.components.subcomps.EnhancedSongListItem
import fr.spotifeur.ui.theme.GoogleSansRounded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Every track played, in Spotifeur from any source and on the Spotify account elsewhere, grouped by day. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HistoryScreen(model: SpotifeurModel, navController: NavHostController) {
    val ui = model.ui
    val local by model.listeningHistory.entries.collectAsStateWithLifecycle()
    val saved by model.favorites.collectAsStateWithLifecycle()
    val stable by ui.stablePlayerState.collectAsStateWithLifecycle()
    var entries by remember { mutableStateOf<List<HistoryEntry>?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Song?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(local, revision) {
        refreshing = entries != null
        try { entries = model.fullListeningHistory() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { entries = local }
        finally { refreshing = false }
    }
    val list = entries.orEmpty()
    val songs = remember(list, saved) { list.map { it.track.toSong(it.track.id in saved) } }
    val title = stringResource(R.string.history_title)
    val today = stringResource(R.string.history_today)
    val yesterday = stringResource(R.string.history_yesterday)
    val zone = ZoneId.systemDefault()
    val dayFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL) }
    val timeFormat = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    fun dayOf(entry: HistoryEntry) = Instant.ofEpochMilli(entry.playedAt).atZone(zone).toLocalDate()
    fun dayLabel(day: LocalDate) = when (day) {
        LocalDate.now(zone) -> today
        LocalDate.now(zone).minusDays(1) -> yesterday
        else -> day.format(dayFormat).replaceFirstChar { it.titlecase() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontFamily = GoogleSansRounded, modifier = Modifier.padding(start = 8.dp)) },
                navigationIcon = {
                    FilledTonalIconButton(
                        modifier = Modifier.padding(start = 10.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        onClick = { navController.popBackStack() },
                    ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back)) }
                },
                actions = {
                    if (local.isNotEmpty()) IconButton(onClick = { clearing = true }, modifier = Modifier.padding(end = 8.dp)) {
                        Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.history_clear))
                    }
                },
            )
        },
    ) { inner ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { revision++ }, modifier = Modifier.fillMaxSize().padding(inner)) {
            when {
                entries == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
                list.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.bodyLarge)
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(fr.spotifeur.presentation.components.listSpacing()),
                ) {
                    itemsIndexed(list, key = { index, entry -> "$index:${entry.playedAt}:${entry.track.id}" }) { index, entry ->
                        val day = dayOf(entry)
                        if (index == 0 || dayOf(list[index - 1]) != day) Text(
                            dayLabel(day), style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = if (index == 0) 4.dp else 16.dp, bottom = 4.dp),
                        )
                        val song = songs[index]
                        val current = stable.currentSong?.id == song.id
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                Instant.ofEpochMilli(entry.playedAt).atZone(zone).format(timeFormat),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, softWrap = false,
                                modifier = Modifier.width(64.dp),
                            )
                            EnhancedSongListItem(
                                modifier = Modifier.weight(1f),
                                song = song,
                                isPlaying = current && stable.isPlaying,
                                isCurrentSong = current,
                                showSourceBadge = true,
                                onMoreOptionsClick = { selected = it },
                                onLongPress = { selected = song },
                                onClick = { ui.playSongs(songs, song, title) },
                            )
                        }
                    }
                }
            }
        }
    }
    SongOptionsHost(ui, navController, selected, songs, title) { selected = null }
    if (clearing) AlertDialog(
        onDismissRequest = { clearing = false },
        title = { Text(stringResource(R.string.history_clear)) },
        text = { Text(stringResource(R.string.history_clear_message)) },
        confirmButton = { TextButton(onClick = { clearing = false; scope.launch(kotlinx.coroutines.Dispatchers.IO) { model.listeningHistory.clear() } }) { Text(stringResource(R.string.history_clear_confirm)) } },
        dismissButton = { TextButton(onClick = { clearing = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
}
