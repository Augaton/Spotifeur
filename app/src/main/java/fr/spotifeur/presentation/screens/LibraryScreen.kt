package fr.spotifeur.presentation.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import fr.spotifeur.R
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.HomeLibrary
import fr.spotifeur.data.LocalLibrary
import fr.spotifeur.data.Music
import fr.spotifeur.data.model.LibraryTabId
import fr.spotifeur.data.model.Song
import fr.spotifeur.data.model.SortOption
import fr.spotifeur.data.model.toAlbum
import fr.spotifeur.data.model.toArtist
import fr.spotifeur.data.model.toPlaylist
import fr.spotifeur.data.model.toSong
import fr.spotifeur.presentation.components.AlbumGridItemRedesigned
import fr.spotifeur.presentation.components.AlbumListItem
import fr.spotifeur.presentation.components.ArtistListItem
import fr.spotifeur.presentation.components.LibrarySortBottomSheet
import fr.spotifeur.presentation.components.MiniPlayerHeight
import fr.spotifeur.presentation.components.MusicOptionsHost
import fr.spotifeur.presentation.components.PlaylistItem
import fr.spotifeur.presentation.components.PlaylistNameDialog
import fr.spotifeur.presentation.components.SongOptionsHost
import fr.spotifeur.presentation.components.subcomps.EnhancedSongListItem
import fr.spotifeur.presentation.components.subcomps.LibraryActionRow
import fr.spotifeur.presentation.navigation.Screen
import fr.spotifeur.presentation.navigation.navigateSafely
import fr.spotifeur.presentation.navigation.openMusic
import fr.spotifeur.playback.DownloadedTrack
import fr.spotifeur.playback.CollectionProgress
import fr.spotifeur.data.MusicKind
import fr.spotifeur.utils.formatSongCount
import fr.spotifeur.presentation.viewmodel.PlayerViewModel
import fr.spotifeur.ui.theme.GoogleSansRounded
import fr.spotifeur.ui.theme.LocalPixelPlayDarkTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

private val libraryTabs = listOf(LibraryTabId.PLAYLISTS, LibraryTabId.LIKED, LibraryTabId.ALBUMS, LibraryTabId.ARTISTS, LibraryTabId.LOCAL, LibraryTabId.DOWNLOADS)

private fun sortOptions(tab: LibraryTabId): List<SortOption> = when (tab) {
    LibraryTabId.PLAYLISTS -> listOf(SortOption.SongDefaultOrder, SortOption.PlaylistNameAZ, SortOption.PlaylistNameZA)
    LibraryTabId.ALBUMS -> listOf(
        SortOption.SongDefaultOrder, SortOption.AlbumTitleAZ, SortOption.AlbumTitleZA,
        SortOption.AlbumArtist, SortOption.AlbumArtistDesc,
        SortOption.AlbumReleaseYear, SortOption.AlbumReleaseYearAsc,
    )
    LibraryTabId.ARTISTS -> listOf(SortOption.SongDefaultOrder, SortOption.ArtistNameAZ, SortOption.ArtistNameZA)
    // Saved tracks are paged from Spotify: sorting would only reorder what is already loaded.
    LibraryTabId.LIKED, LibraryTabId.DOWNLOADS -> emptyList()
    LibraryTabId.LOCAL -> listOf(SortOption.SongTitleAZ, SortOption.SongTitleZA, SortOption.SongArtist, SortOption.SongArtistDesc, SortOption.SongAlbum, SortOption.SongAlbumDesc, SortOption.SongDuration, SortOption.SongDurationAsc)
}

private fun List<Music>.sortedFor(option: SortOption): List<Music> = when (option) {
    SortOption.PlaylistNameAZ, SortOption.AlbumTitleAZ, SortOption.ArtistNameAZ -> sortedBy { it.title.lowercase() }
    SortOption.PlaylistNameZA, SortOption.AlbumTitleZA, SortOption.ArtistNameZA -> sortedByDescending { it.title.lowercase() }
    SortOption.AlbumArtist -> sortedBy { it.subtitle.lowercase() }
    SortOption.AlbumArtistDesc -> sortedByDescending { it.subtitle.lowercase() }
    SortOption.AlbumReleaseYear -> sortedByDescending { it.note.toIntOrNull() ?: 0 }
    SortOption.AlbumReleaseYearAsc -> sortedBy { it.note.toIntOrNull() ?: Int.MAX_VALUE }
    SortOption.SongTitleAZ -> sortedBy { it.title.lowercase() }
    SortOption.SongTitleZA -> sortedByDescending { it.title.lowercase() }
    SortOption.SongArtist -> sortedBy { it.subtitle.lowercase() }
    SortOption.SongArtistDesc -> sortedByDescending { it.subtitle.lowercase() }
    SortOption.SongAlbum -> sortedBy { it.album.lowercase() }
    SortOption.SongAlbumDesc -> sortedByDescending { it.album.lowercase() }
    SortOption.SongDuration -> sortedByDescending { it.durationMs }
    SortOption.SongDurationAsc -> sortedBy { it.durationMs }
    else -> this
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibraryScreen(model: SpotifeurModel, navController: NavHostController, paddingValues: PaddingValues) {
    val ui = model.ui
    val library by model.library.collectAsStateWithLifecycle()
    val stable by ui.stablePlayerState.collectAsStateWithLifecycle()
    var savedTab by rememberSaveable { mutableStateOf(0) }
    val pagerState = rememberPagerState(initialPage = savedTab) { libraryTabs.size }
    val requestedTab by ui.requestedLibraryTab.collectAsStateWithLifecycle()
    LaunchedEffect(requestedTab) {
        val requested = libraryTabs.indexOfFirst { it.storageKey == requestedTab }
        if (requested >= 0) { pagerState.scrollToPage(requested); ui.requestedLibraryTab.value = null }
    }
    val scope = rememberCoroutineScope()
    val currentTab = libraryTabs[pagerState.currentPage]
    var sortKeys by rememberSaveable { mutableStateOf(mapOf<String, String>()) }
    var albumsAsList by rememberSaveable { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }
    var creatingPlaylist by remember { mutableStateOf(false) }
    val localPlaylists by model.localPlaylists.collectAsStateWithLifecycle()
    val localTracks by model.localTracks.collectAsStateWithLifecycle()
    val localTitle = stringResource(R.string.library_tab_local)
    val downloads by ui.downloads.collectAsStateWithLifecycle()
    val downloadsTitle = stringResource(R.string.library_tab_downloads)
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { savedTab = it }
    }

    val options = remember(currentTab) { sortOptions(currentTab) }
    val selectedSort = options.firstOrNull { it.storageKey == sortKeys[currentTab.storageKey] } ?: options.firstOrNull()
    fun sortOf(tab: LibraryTabId): SortOption =
        sortOptions(tab).firstOrNull { it.storageKey == sortKeys[tab.storageKey] } ?: SortOption.SongDefaultOrder

    val open: (Music) -> Unit = { navController.openMusic(ui, it) }
    var optionsFor by remember { mutableStateOf<Music?>(null) }
    val pin: (Music) -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        optionsFor = it
    }

    val dark = LocalPixelPlayDarkTheme.current
    val gradientColors = if (dark) {
        listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), Color.Transparent)
    } else {
        listOf(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f), Color.Transparent)
    }
    val gradientBrush = remember(gradientColors) { Brush.verticalGradient(colors = gradientColors) }
    val headerContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)

    Scaffold(
        modifier = Modifier.background(brush = gradientBrush),
        topBar = {
            Column(Modifier.background(headerContainerColor)) {
                TopAppBar(
                    title = {
                        Text(
                            modifier = Modifier.padding(start = 8.dp),
                            text = stringResource(R.string.library_screen_title),
                            fontFamily = GoogleSansRounded,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 34.sp,
                            letterSpacing = 1.sp
                        )
                    },
                    actions = {
                        FilledIconButton(
                            modifier = Modifier.padding(end = 14.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            onClick = { navController.navigateSafely(Screen.Settings.route) }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.rounded_settings_24),
                                contentDescription = stringResource(R.string.library_cd_open_settings)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    )
                )
                PrimaryScrollableTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = Color.Transparent,
                    edgePadding = 12.dp,
                    indicator = {},
                    divider = {}
                ) {
                    libraryTabs.forEachIndexed { index, tab ->
                        TabAnimation(
                            index = index,
                            title = tab.storageKey,
                            selectedIndex = pagerState.currentPage,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } }
                        ) {
                            Text(
                                text = stringResource(tab.titleRes).uppercase(),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    ) { inner ->
        Box(Modifier.padding(top = inner.calculateTopPadding()).fillMaxSize()) {
            Column(Modifier.background(headerContainerColor).fillMaxSize()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = AbsoluteSmoothCornerShape(
                        cornerRadiusTL = 34.dp, smoothnessAsPercentBL = 60,
                        cornerRadiusBL = 0.dp, smoothnessAsPercentBR = 60,
                        cornerRadiusBR = 0.dp, smoothnessAsPercentTR = 60,
                        cornerRadiusTR = 34.dp, smoothnessAsPercentTL = 60
                    )
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LibraryActionRow(
                                modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
                                onMainActionClick = {
                                    if (currentTab == LibraryTabId.PLAYLISTS) { creatingPlaylist = true; return@LibraryActionRow }
                                    if (currentTab == LibraryTabId.DOWNLOADS) {
                                        ui.playSongsShuffled(downloads.values.filter { it.isComplete }.map { it.track.toSong() }, downloadsTitle)
                                        return@LibraryActionRow
                                    }
                                    if (currentTab == LibraryTabId.LOCAL) {
                                        ui.playSongsShuffled(localTracks.map { it.toSong() }, localTitle)
                                        return@LibraryActionRow
                                    }
                                    val content = library.content ?: return@LibraryActionRow
                                    when (currentTab) {
                                        LibraryTabId.LIKED -> ui.playSongsShuffled(content.savedTracks.map { it.toSong() }, ui.likedSongs.title)
                                        LibraryTabId.ALBUMS -> content.albums.randomOrNull()?.let(ui::shuffleItem)
                                        LibraryTabId.ARTISTS -> content.artists.randomOrNull()?.let(ui::shuffleItem)
                                        else -> Unit
                                    }
                                },
                                iconRotation = 0f,
                                showSortButton = options.isNotEmpty(),
                                onSortClick = { showSortSheet = true },
                                isPlaylistTab = currentTab == LibraryTabId.PLAYLISTS,
                                showImportButton = false,
                                isShuffleEnabled = stable.isShuffleEnabled,
                            )
                        }
                        val content = library.content
                        val bottomPadding = paddingValues.calculateBottomPadding() + MiniPlayerHeight + 24.dp
                        PullToRefreshBox(
                            isRefreshing = library.loading && content != null,
                            onRefresh = model::refreshLibrary,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            if (currentTab == LibraryTabId.DOWNLOADS && content == null) {
                                DownloadsTab(ui, navController, downloadsTitle, bottomPadding)
                            } else if (content == null) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    if (library.loading) ContainedLoadingIndicator()
                                    else Text(library.error ?: stringResource(R.string.lib_unavailable), Modifier.padding(24.dp))
                                }
                            } else {
                                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                                    when (libraryTabs[page]) {
                                        LibraryTabId.PLAYLISTS -> PlaylistsTab(localPlaylists.map { it.asMusic() } + content.playlists.sortedFor(sortOf(LibraryTabId.PLAYLISTS)), bottomPadding, open, pin)
                                        LibraryTabId.LIKED -> LikedTab(model, ui, navController, content, bottomPadding)
                                        LibraryTabId.ALBUMS -> AlbumsTab(content.albums.sortedFor(sortOf(LibraryTabId.ALBUMS)), albumsAsList, ui, bottomPadding, open, pin)
                                        LibraryTabId.ARTISTS -> ArtistsTab(content.artists.sortedFor(sortOf(LibraryTabId.ARTISTS)), bottomPadding, open, pin)
                                        LibraryTabId.DOWNLOADS -> DownloadsTab(ui, navController, downloadsTitle, bottomPadding)
                                        LibraryTabId.LOCAL -> LocalTab(model, ui, navController, localTracks.sortedFor(sortOf(LibraryTabId.LOCAL)), localTitle, bottomPadding)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    MusicOptionsHost(ui, navController, optionsFor) { optionsFor = null }
    if (creatingPlaylist) fr.spotifeur.presentation.components.SpotifyPlaylistEditor(model, onDismiss = { creatingPlaylist = false },
        onSaved = { created -> created?.let { ui.register(it); navController.openMusic(ui, it) } })

    if (showSortSheet && options.isNotEmpty()) {
        LibrarySortBottomSheet(
            title = stringResource(R.string.library_cd_sort_options),
            options = options,
            selectedOption = selectedSort,
            onDismiss = { showSortSheet = false },
            onOptionSelected = { option -> sortKeys = sortKeys + (currentTab.storageKey to option.storageKey) },
            showViewToggle = currentTab == LibraryTabId.ALBUMS,
            viewSectionTitle = stringResource(R.string.view_section),
            viewToggleLabel = stringResource(R.string.view_as_list),
            viewToggleChecked = albumsAsList,
            onViewToggleChange = { albumsAsList = it },
        )
    }
}

@Composable
private fun PlaylistsTab(playlists: List<Music>, bottom: Dp, open: (Music) -> Unit, onLongPress: (Music) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(playlists, key = Music::id) { item ->
            PlaylistItem(
                playlist = item.toPlaylist(),
                onClick = { open(item) },
                onLongPress = { onLongPress(item) },
            )
        }
    }
}

@Composable
private fun AlbumsTab(
    albums: List<Music>,
    asList: Boolean,
    ui: PlayerViewModel,
    bottom: Dp,
    open: (Music) -> Unit,
    onLongPress: (Music) -> Unit,
) {
    if (asList) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(albums, key = Music::id) { item ->
                AlbumListItem(
                    album = item.toAlbum(),
                    albumColorSchemePairFlow = ui.getAlbumColorSchemeFlow(item.imageUrl.orEmpty()),
                    onClick = { open(item) },
                    onLongPress = { onLongPress(item) },
                )
            }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(albums, key = Music::id) { item ->
                AlbumGridItemRedesigned(
                    album = item.toAlbum(),
                    albumColorSchemePairFlow = ui.getAlbumColorSchemeFlow(item.imageUrl.orEmpty()),
                    onClick = { open(item) },
                    onLongPress = { onLongPress(item) },
                )
            }
        }
    }
}

@Composable
private fun ArtistsTab(artists: List<Music>, bottom: Dp, open: (Music) -> Unit, onLongPress: (Music) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(artists, key = Music::id) { item ->
            ArtistListItem(artist = item.toArtist(), onClick = { open(item) }, onLongPress = { onLongPress(item) })
        }
    }
}

@Composable
private fun LikedTab(
    model: SpotifeurModel,
    ui: PlayerViewModel,
    navController: NavHostController,
    content: HomeLibrary,
    bottom: Dp,
) {
    val stable by ui.stablePlayerState.collectAsStateWithLifecycle()
    val saved by model.favorites.collectAsStateWithLifecycle()
    val songs = remember(content.savedTracks, saved) { content.savedTracks.map { it.toSong(it.id in saved) } }
    var selected by remember { mutableStateOf<Song?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(listState, content.savedTracksLoaded, content.savedTrackUris.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last ->
                if (last >= songs.size - 12 && content.savedTracksLoaded < content.savedTrackUris.size) model.loadMoreSavedTracks()
            }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(songs, key = { index, song -> "$index-${song.id}" }) { _, song ->
            val current = stable.currentSong?.id == song.id
            EnhancedSongListItem(
                song = song,
                isPlaying = current && stable.isPlaying,
                isCurrentSong = current,
                onMoreOptionsClick = { selected = it },
                onLongPress = { selected = song },
                onClick = { ui.playSongs(songs, song, ui.likedSongs.title) },
            )
        }
    }
    SongOptionsHost(ui, navController, selected, songs, ui.likedSongs.title) { selected = null }
}

@Composable
private fun LocalTab(
    model: SpotifeurModel,
    ui: PlayerViewModel,
    navController: NavHostController,
    tracks: List<Music>,
    title: String,
    bottom: Dp,
) {
    val stable by ui.stablePlayerState.collectAsStateWithLifecycle()
    var allowed by remember { mutableStateOf(model.localLibrary.hasPermission()) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        if (granted) model.refreshLocalTracks()
    }
    val songs = remember(tracks) { tracks.map { it.toSong() } }
    var selected by remember { mutableStateOf<Song?>(null) }
    when {
        !allowed -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.local_permission_title), style = MaterialTheme.typography.titleMedium)
            Button(onClick = { request.launch(LocalLibrary.PERMISSION) }, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.local_permission_action))
            }
        }
        songs.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.local_empty))
        }
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(songs, key = { it.id }) { song ->
                val current = stable.currentSong?.id == song.id
                EnhancedSongListItem(
                    song = song,
                    isPlaying = current && stable.isPlaying,
                    isCurrentSong = current,
                    onMoreOptionsClick = { selected = it },
                    onLongPress = { selected = song },
                    onClick = { ui.playSongs(songs, song, title) },
                )
            }
        }
    }
    SongOptionsHost(ui, navController, selected, songs, title) { selected = null }
}

@Composable
private fun DownloadsTab(
    ui: PlayerViewModel,
    navController: NavHostController,
    title: String,
    bottom: Dp,
) {
    val stable by ui.stablePlayerState.collectAsStateWithLifecycle()
    val requests by ui.downloadCollections.collectAsStateWithLifecycle()
    val entries = requests.values.sortedBy { it.collection.item.title.lowercase() }
    val singles = entries.filter { it.collection.item.kind == MusicKind.Track }
    val songs = singles.filter { it.isComplete }.map { it.collection.item.toSong() }
    var selected by remember { mutableStateOf<Song?>(null) }
    var options by remember { mutableStateOf<Music?>(null) }
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.downloads_empty)) }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val groups = listOf(
            R.string.downloads_playlists to entries.filter { it.collection.item.kind != MusicKind.Track && it.collection.item.kind != MusicKind.Album },
            R.string.downloads_albums to entries.filter { it.collection.item.kind == MusicKind.Album },
        )
        groups.forEach { (heading, group) ->
            if (group.isNotEmpty()) item(key = "heading:$heading") {
                Text(stringResource(heading), Modifier.padding(horizontal = 8.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
            }
            items(group, key = { it.collection.item.id }) { entry ->
                val item = entry.collection.item
                Column {
                    if (item.kind == MusicKind.Album) AlbumListItem(
                        album = item.toAlbum().copy(songCount = entry.total),
                        albumColorSchemePairFlow = ui.getAlbumColorSchemeFlow(item.imageUrl.orEmpty()),
                        onClick = { navController.openMusic(ui, item) }, onLongPress = { options = item })
                    else PlaylistItem(playlist = item.toPlaylist().copy(description = formatSongCount(entry.total)),
                        onClick = { navController.openMusic(ui, item) }, onLongPress = { options = item })
                    fr.spotifeur.presentation.components.CollectionDownloadProgress(ui, item)
                }
            }
        }
        if (singles.isNotEmpty()) item(key = "single_heading") {
            Text(stringResource(R.string.downloads_singles), Modifier.padding(horizontal = 8.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
        }
        items(singles, key = { it.collection.item.id }) { entry ->
            val song = entry.collection.item.toSong()
            val current = stable.currentSong?.id == song.id
            Column {
                EnhancedSongListItem(song = song, isPlaying = current && stable.isPlaying, isCurrentSong = current,
                    onMoreOptionsClick = { selected = it }, onLongPress = { selected = song },
                    onClick = { if (entry.isComplete) ui.playSongs(songs, song, title) })
                fr.spotifeur.presentation.components.CollectionDownloadProgress(ui, entry.collection.item)
            }
        }
    }
    SongOptionsHost(ui, navController, selected, songs, title) { selected = null }
    MusicOptionsHost(ui, navController, options) { options = null }
}
