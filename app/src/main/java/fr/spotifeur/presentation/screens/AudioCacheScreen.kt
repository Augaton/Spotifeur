package fr.spotifeur.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import fr.spotifeur.R
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.playback.CachedAudio
import fr.spotifeur.ui.theme.GoogleSansRounded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class CacheSort { RECENT, SIZE, TITLE }

/** What the size slider offers, from a few hours of listening to a very large collection. */
private val SIZE_STEPS_MB = listOf(512, 1024, 2048, 4096, 8192, 16384, 32768)
private const val MAX_PRELOADED_TRACKS = 20

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioCacheScreen(model: SpotifeurModel, navController: NavHostController) {
    val scope = rememberCoroutineScope()
    val settings by model.ui.settings.collectAsStateWithLifecycle()
    var entries by remember { mutableStateOf<List<CachedAudio>>(emptyList()) }
    var used by remember { mutableLongStateOf(0L) }
    var free by remember { mutableLongStateOf(0L) }
    var revision by remember { mutableIntStateOf(0) }
    var sort by remember { mutableStateOf(CacheSort.RECENT) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(revision, settings.audioCacheLimitMb) {
        while (true) {
        withContext(Dispatchers.IO) {
            entries = model.ui.audioCacheEntries()
            used = model.ui.audioCacheBytes()
            free = model.ui.freeStorageBytes()
        }
        selected = selected.intersect(entries.map { it.track.id }.toSet())
        kotlinx.coroutines.delay(2_000)
        }
    }
    val sorted = remember(entries, sort) {
        when (sort) {
            CacheSort.RECENT -> entries.sortedByDescending(CachedAudio::lastPlayedMs)
            CacheSort.SIZE -> entries.sortedByDescending(CachedAudio::sizeBytes)
            CacheSort.TITLE -> entries.sortedBy { it.track.title.lowercase() }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.audio_cache_title), fontFamily = GoogleSansRounded, modifier = Modifier.padding(start = 8.dp)) },
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
            )
        },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                UsageCard(used, settings.audioCacheLimitMb * MB, free, entries)
            }
            item {
                var position by remember(settings.audioCacheLimitMb) {
                    mutableFloatStateOf(SIZE_STEPS_MB.indexOfFirst { it >= settings.audioCacheLimitMb }.coerceAtLeast(0).toFloat())
                }
                SliderSettingsItem(
                    label = stringResource(R.string.audio_cache_limit),
                    value = position,
                    valueRange = 0f..SIZE_STEPS_MB.lastIndex.toFloat(),
                    steps = SIZE_STEPS_MB.size - 2,
                    onValueChange = { position = it },
                    onValueChangeFinished = { model.ui.setAudioCacheLimit(SIZE_STEPS_MB[position.roundToInt()]) },
                    valueText = { formatSize(SIZE_STEPS_MB[it.roundToInt()] * MB) },
                )
            }
            item {
                SwitchSettingItem(stringResource(R.string.audio_cache_mobile), stringResource(R.string.audio_cache_mobile_description), settings.preloadOnMobile,
                    { enabled -> model.ui.updateSettings { it.copy(preloadOnMobile = enabled) } }, leadingIcon = {})
            }
            item {
                var count by remember(settings.preloadTracks) { mutableFloatStateOf(settings.preloadTracks.toFloat()) }
                SliderSettingsItem(
                    label = stringResource(R.string.audio_cache_preload),
                    value = count,
                    valueRange = 0f..MAX_PRELOADED_TRACKS.toFloat(),
                    steps = MAX_PRELOADED_TRACKS - 1,
                    onValueChange = { count = it },
                    onValueChangeFinished = { model.ui.updateSettings { s -> s.copy(preloadTracks = count.roundToInt()) } },
                    valueText = { it.roundToInt().toString() },
                )
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(sort == CacheSort.RECENT, { sort = CacheSort.RECENT }, { Text(stringResource(R.string.audio_cache_sort_recent)) })
                    FilterChip(sort == CacheSort.SIZE, { sort = CacheSort.SIZE }, { Text(stringResource(R.string.audio_cache_sort_size)) })
                    FilterChip(sort == CacheSort.TITLE, { sort = CacheSort.TITLE }, { Text(stringResource(R.string.audio_cache_sort_title)) })
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = selected.isNotEmpty(),
                        onClick = {
                            val ids = selected
                            scope.launch {
                                withContext(Dispatchers.IO) { model.ui.removeFromAudioCache(ids) }
                                selected = emptySet()
                                revision++
                            }
                        },
                    ) { Text(stringResource(R.string.audio_cache_remove_selected, selected.size)) }
                    OutlinedButton(
                        enabled = entries.isNotEmpty(),
                        onClick = {
                            selected = if (selected.size == entries.size) emptySet() else entries.map { it.track.id }.toSet()
                        },
                    ) { Text(stringResource(R.string.audio_cache_select_all)) }
                }
            }
            items(sorted, key = { it.track.id }) { entry ->
                CachedTrackRow(entry, entry.track.id in selected) {
                    selected = if (entry.track.id in selected) selected - entry.track.id else selected + entry.track.id
                }
            }
        }
    }
}

@Composable
private fun UsageCard(used: Long, limit: Long, free: Long, entries: List<CachedAudio>) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.audio_cache_usage, formatSize(used), formatSize(limit)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            LinearProgressIndicator(
                progress = { (used.toFloat() / limit.coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.audio_cache_summary, entries.count { it.isComplete }, entries.count { !it.isComplete }, formatSize(free)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CachedTrackRow(entry: CachedAudio, selected: Boolean, onToggle: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onToggle),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    selected -> Icon(Icons.Rounded.Check, stringResource(R.string.audio_cache_selected), tint = MaterialTheme.colorScheme.primary)
                    entry.track.imageUrl != null -> AsyncImage(entry.track.imageUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else -> Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    entry.track.title.ifEmpty { stringResource(R.string.audio_cache_unknown_track) },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.track.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(entryDetail(entry), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun entryDetail(entry: CachedAudio): String {
    val size = formatSize(entry.sizeBytes)
    val state = if (entry.isComplete) stringResource(R.string.audio_cache_complete)
    else stringResource(R.string.audio_cache_partial, (entry.sizeBytes * 100 / entry.expectedBytes.coerceAtLeast(1)).toInt())
    val heard = if (entry.lastPlayedMs > 0) {
        android.text.format.DateUtils.getRelativeTimeSpanString(
            entry.lastPlayedMs, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    } else stringResource(R.string.audio_cache_preloaded)
    return "$size · $state · $heard"
}

private const val MB = 1024L * 1024
