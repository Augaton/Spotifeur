package fr.spotifeur

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.protobuf.InvalidProtocolBufferException
import fr.spotifeur.data.CatalogDetail
import fr.spotifeur.data.CatalogStore
import fr.spotifeur.data.FavoriteChange
import fr.spotifeur.data.NetworkMonitor
import fr.spotifeur.data.SpotifyAuthRequired
import fr.spotifeur.data.applyFavoriteChanges
import fr.spotifeur.data.playlistMosaic
import fr.spotifeur.data.decodePins
import fr.spotifeur.data.encodePins
import fr.spotifeur.data.HomeLibrary
import fr.spotifeur.data.LOCAL_PLAYLIST_PREFIX
import fr.spotifeur.data.LocalLibrary
import fr.spotifeur.data.LocalPlaylistStore
import fr.spotifeur.data.MusicKind
import fr.spotifeur.data.isLocalPlaylist
import fr.spotifeur.data.isLocalTrack
import fr.spotifeur.data.PinnedStore
import fr.spotifeur.data.SpotifyAccount
import fr.spotifeur.data.spotifyAttribute
import fr.spotifeur.presentation.viewmodel.PlayerViewModel
import fr.spotifeur.data.SpotifyCatalog
import fr.spotifeur.data.SpotifyPlaylists
import fr.spotifeur.data.SpotifyLibrary
import fr.spotifeur.data.SpotifyLyricsSource
import fr.spotifeur.data.SpotifyRadio
import fr.spotifeur.playback.SpotifyPlayer
import fr.spotifeur.data.SessionVault
import fr.spotifeur.data.Music
import fr.spotifeur.data.SpotifyFailure
import fr.spotifeur.data.SpotifySearch
import fr.spotifeur.data.SpotifySession
import fr.spotifeur.data.SpotifyTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import java.util.UUID

data class Discovery(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<Music> = emptyList(),
    val nextPage: String? = null,
    val error: String? = null,
    val spotifyEnabled: Boolean = false,
)

data class AccountUi(
    val working: Boolean = false,
    val codeLength: Int? = null,
    val codeEmail: String? = null,
    val error: String? = null,
    val restoring: Boolean = false,
)

data class LibraryUi(val content: HomeLibrary? = null, val loading: Boolean = false, val error: String? = null) {
    val items: List<Music> get() = content?.let { it.playlists + it.albums + it.artists }.orEmpty()
    val tracks: List<Music> get() = content?.savedTracks.orEmpty()
}
data class DetailUi(val content: CatalogDetail? = null, val loading: Boolean = false, val error: String? = null)

class SpotifeurModel(private val app: Application) : AndroidViewModel(app) {
    val player = SpotifyPlayer(app, viewModelScope)
    private val pinnedStore = PinnedStore(app)
    val pins = pinnedStore.pins
    fun togglePin(item: Music) = pinnedStore.toggle(item)
    fun movePin(from: Int, to: Int) = pinnedStore.move(from, to)
    val ui by lazy { PlayerViewModel(this, viewModelScope) }
    val localLibrary = LocalLibrary(app)
    private val mutableLocalTracks = MutableStateFlow<List<Music>>(emptyList())
    val localTracks = mutableLocalTracks.asStateFlow()
    val localPlaylistStore = LocalPlaylistStore(app)
    val localPlaylists = localPlaylistStore.playlists
    fun refreshLocalTracks() { viewModelScope.launch { mutableLocalTracks.value = localLibrary.load() } }
    val pinnedStoreForBackup get() = pinnedStore
    private val preferences = app.getSharedPreferences("music_library", 0)
    private val savedIds = MutableStateFlow<Set<String>>(emptySet())
    val favorites = savedIds.asStateFlow()

    private val mutableDiscovery = MutableStateFlow(Discovery())
    val discovery = mutableDiscovery.asStateFlow()
    private var searchJob: Job? = null
    private var accountJob: Job? = null
    private val mutableAccount = MutableStateFlow(AccountUi())
    val account = mutableAccount.asStateFlow()
    private val transport = SpotifyTransport()
    private val vault = SessionVault(app)
    private val devicePreferences = app.getSharedPreferences("spotify_device", 0)
    private val deviceId = devicePreferences.getString("id", null) ?: UUID.randomUUID().toString().also { id ->
        devicePreferences.edit { putString("id", id) }
    }
    private var pendingCode: CompletableDeferred<String>? = null
    private val loginFlowId by lazy { UUID.randomUUID().toString() }
    private var remote: SpotifySearch? = null
    private var catalog: SpotifyCatalog? = null
    private var collections: SpotifyLibrary? = null
    private var radio: SpotifyRadio? = null
    private var libraryJob: Job? = null
    private val detailJobs = mutableMapOf<String, Job>()
    private val pendingMutations = mutableSetOf<String>()
    private val store = CatalogStore(app)
    internal val catalogStore get() = store
    /** Likes the library read does not show yet. Main thread only. */
    private val favoriteChanges = LinkedHashMap<String, FavoriteChange>()
    /** Mosaic covers of playlists without their own picture, from their stored pages. Main thread only. */
    private val playlistCovers = HashMap<String, String>()
    private fun rememberCover(detail: CatalogDetail) {
        val item = detail.item
        if (item.kind != MusicKind.Playlist || item.imageUrl == null || playlistCovers[item.id] == item.imageUrl) return
        val shown = mutableLibrary.value.content?.playlists?.firstOrNull { it.id == item.id } ?: return
        if (shown.imageUrl != null && shown.id !in playlistCovers) return
        playlistCovers[item.id] = item.imageUrl
        saveCovers()
        mutableLibrary.value.content?.let { installLibrary(it, mutableLibrary.value.loading) }
    }
    private val coverLookups = HashSet<String>()
    /** A playlist without picture whose page is on the device gets its mosaic without a request. */
    private fun coverFromStoredPage(id: String) {
        if (!coverLookups.add(id)) return
        viewModelScope.launch {
            val detail = withContext(Dispatchers.IO) { store.loadDetail(id) } ?: return@launch
            val cover = playlistMosaic(detail.tracks) ?: return@launch
            playlistCovers[id] = cover
            saveCovers()
            mutableLibrary.value.content?.let { installLibrary(it, mutableLibrary.value.loading) }
        }
    }
    private fun saveCovers() = preferences.edit { putString(KEY_PLAYLIST_COVERS, org.json.JSONObject(playlistCovers as Map<*, *>).toString()) }
    private fun loadCovers() {
        val json = runCatching { org.json.JSONObject(preferences.getString(KEY_PLAYLIST_COVERS, null) ?: return) }.getOrNull() ?: return
        json.keys().forEach { playlistCovers[it] = json.getString(it) }
    }
    private var offlineSyncJob: Job? = null
    val online = NetworkMonitor.online
    private val mutableLibrary = MutableStateFlow(LibraryUi())
    val library = mutableLibrary.asStateFlow()
    private val mutableDetails = MutableStateFlow<Map<String, DetailUi>>(emptyMap())
    val details = mutableDetails.asStateFlow()
    private val mutableRecent = MutableStateFlow<List<Music>>(emptyList())
    val recent = mutableRecent.asStateFlow()
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()

    init {
        viewModelScope.launch {
            localPlaylistStore.playlists.collect { playlists ->
                playlists.forEach { ui.register(it.asMusic()) }
                val known = mutableDetails.value.keys.filter { it.startsWith(LOCAL_PLAYLIST_PREFIX) }
                known.forEach(::installLocalPlaylist)
            }
        }
        refreshLocalTracks()
        viewModelScope.launch {
            player.state.collect { playback ->
                val track = playback.track
                if (track != null && playback.playing && mutableRecent.value.firstOrNull()?.id != track.id) {
                    mutableRecent.update { (listOf(track) + it.filterNot { previous -> previous.id == track.id }).take(12) }
                }
            }
        }
        viewModelScope.launch {
            var wasOnline = online.value
            online.collect { now ->
                if (now && !wasOnline && collections != null) {
                    sendPendingFavorites()
                    val age = withContext(Dispatchers.IO) { store.libraryAgeMs() }
                    if (age > LIBRARY_TTL_MS) refreshLibrary()
                }
                wasOnline = now
            }
        }
        accountJob = viewModelScope.launch {
            mutableAccount.value = AccountUi(working = true, restoring = true)
            try {
                val saved = withContext(Dispatchers.IO) {
                    vault.removeLegacySession()
                    runCatching { vault.load() }.getOrNull()
                }
                if (saved != null) {
                    // Activity recreation must keep the session that owns active audio handles.
                    SpotifyAccount.restore(app)?.let(::finishAccount)
                }
                mutableAccount.value = AccountUi()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                showAccountError(failure)
            }
        }
    }

    fun toggleFavorite(item: Music) {
        if (item.isLocalTrack || item.isLocalPlaylist) return
        if (item.kind != MusicKind.Track && item.kind != MusicKind.Album && item.kind != MusicKind.Artist) {
            mutableMessage.value = "This item cannot be changed in your favorites."
            return
        }
        val source = collections ?: return
        if (!pendingMutations.add(item.id)) return
        val saved = item.id !in savedIds.value
        changeFavorite(FavoriteChange(item, saved))
        viewModelScope.launch {
            try { sendFavorite(source, item, saved) } finally { pendingMutations.remove(item.id) }
        }
    }

    private fun changeFavorite(change: FavoriteChange) {
        favoriteChanges[change.item.id] = change
        savePendingFavorites()
        val current = mutableLibrary.value
        if (current.content != null) installLibrary(current.content, current.loading)
        else savedIds.update { if (change.saved) it + change.item.id else it - change.item.id }
    }

    /** Refused by the service: undone. Not reached: kept and sent with the next connection. */
    private suspend fun sendFavorite(source: SpotifyLibrary, item: Music, saved: Boolean) {
        try {
            source.setSaved(item, saved)
            if (favoriteChanges[item.id]?.saved == saved) {
                favoriteChanges[item.id] = FavoriteChange(item, saved, System.currentTimeMillis())
            }
            savePendingFavorites()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (auth: SpotifyAuthRequired) { accountLost(auth) }
        catch (failure: SpotifyFailure) {
            if (failure.status == null) return
            favoriteChanges.remove(item.id)
            savePendingFavorites()
            val current = mutableLibrary.value
            savedIds.update { if (saved) it - item.id else it + item.id }
            if (current.content != null) installLibrary(current.content, current.loading)
            mutableMessage.value = readable(failure)
        } catch (failure: IOException) {
            // Kept for the next connection.
            android.util.Log.i("SpotifeurLibrary", "Favorite kept for later: ${failure.javaClass.simpleName}")
        }
    }

    private fun savePendingFavorites() {
        val pending = favoriteChanges.values.filter { it.confirmedAtMs == null }
        preferences.edit {
            putString(KEY_PENDING_LIKES, encodePins(pending.filter { it.saved }.map { it.item }))
            putString(KEY_PENDING_UNLIKES, encodePins(pending.filterNot { it.saved }.map { it.item }))
        }
    }

    private fun loadPendingFavorites() {
        decodePins(preferences.getString(KEY_PENDING_LIKES, null)).forEach { favoriteChanges[it.id] = FavoriteChange(it, true) }
        decodePins(preferences.getString(KEY_PENDING_UNLIKES, null)).forEach { favoriteChanges[it.id] = FavoriteChange(it, false) }
    }

    private fun sendPendingFavorites() {
        val source = collections ?: return
        favoriteChanges.values.filter { it.confirmedAtMs == null }.forEach { change ->
            if (pendingMutations.add(change.item.id)) viewModelScope.launch {
                try { sendFavorite(source, change.item, change.saved) } finally { pendingMutations.remove(change.item.id) }
            }
        }
    }

    fun refreshLibrary() {
        val source = collections ?: return
        if (!online.value && mutableLibrary.value.content != null) return
        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            mutableLibrary.update { it.copy(loading = true, error = null) }
            val previous = mutableLibrary.value.content
            try {
                val result = source.load(previous) { installLibrary(it, true) }
                installLibrary(result, false)
                saveLibrary()
                completeSavedTracks(source)
                keepCatalogAvailable()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (auth: SpotifyAuthRequired) { accountLost(auth) }
            catch (failure: Exception) {
                // What was last read stays on screen, without an error for what the connection prevented.
                mutableLibrary.update { it.copy(loading = false, error = if (it.content == null) readable(failure) else null) }
            }
        }
    }
    private fun saveLibrary() {
        val content = mutableLibrary.value.content ?: return
        viewModelScope.launch(Dispatchers.IO) { store.saveLibrary(content) }
    }
    /** Fetches, page by page, the metadata of liked songs this device does not have yet. */
    private suspend fun completeSavedTracks(source: SpotifyLibrary) {
        var pages = 0
        while (online.value) {
            val content = mutableLibrary.value.content ?: return
            if (content.savedTracksLoaded >= content.savedTrackUris.size) break
            val before = content.savedTracksLoaded
            val next = source.loadMore(content)
            if (next.savedTracksLoaded == before) break
            installLibrary(next, false)
            if (++pages % SAVE_EVERY_PAGES == 0) saveLibrary()
            delay(BACKGROUND_REQUEST_GAP_MS)
        }
        if (pages > 0) saveLibrary()
    }
    /**
     * Keeps every page of the library readable without network, a little at a time. Pages come back
     * with what the device already knows, so only what actually changed costs a metadata request.
     */
    private fun keepCatalogAvailable() {
        val catalogSource = catalog ?: return
        offlineSyncJob?.cancel()
        offlineSyncJob = viewModelScope.launch {
            val content = mutableLibrary.value.content ?: return@launch
            val pinned = pins.value.filter { it.id.startsWith("spotify:") && it.id != LIKED_SONGS && it.kind != MusicKind.Track }
            val items = (pinned + content.playlists + content.albums + content.artists).distinctBy(Music::id).take(MAX_BACKGROUND_PAGES)
            items.forEach { item ->
                if (!online.value) return@launch
                val age = withContext(Dispatchers.IO) { store.detailAgeMs(item.id) }
                if (age < revalidateAfterMs(item) || detailJobs[item.id]?.isActive == true) return@forEach
                try {
                    val known = withContext(Dispatchers.IO) { store.loadDetail(item.id) }
                    known?.let { catalogSource.seed(it.tracks + it.related) }
                    val full = fullDetail(catalogSource, item)
                    withContext(Dispatchers.IO) { store.saveDetail(item.id, full) }
                    rememberCover(full)
                    keepCovers(full)
                    delay(BACKGROUND_REQUEST_GAP_MS)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: IOException) { /* The next start tries again. */ }
            }
        }
    }
    /** On an unmetered network, the covers a stored page shows, so it looks the same without network. */
    private fun keepCovers(detail: CatalogDetail) {
        val network = app.getSystemService(android.net.ConnectivityManager::class.java)
        if (network.isActiveNetworkMetered || coversRequested >= MAX_BACKGROUND_COVERS) return
        val loader = coil.Coil.imageLoader(app)
        (listOf(detail.item) + detail.tracks.take(COVERS_PER_PAGE)).mapNotNull(Music::imageUrl).distinct()
            .filter(requestedCovers::add).forEach { url ->
                if (coversRequested++ < MAX_BACKGROUND_COVERS) loader.enqueue(
                    coil.request.ImageRequest.Builder(app).data(url).memoryCachePolicy(coil.request.CachePolicy.DISABLED).build(),
                )
            }
    }
    private val requestedCovers = HashSet<String>()
    private var coversRequested = 0
    private suspend fun fullDetail(source: SpotifyCatalog, item: Music, atLeast: Int = Int.MAX_VALUE, pageLimit: Int = MAX_DETAIL_PAGES): CatalogDetail {
        var detail = source.detail(item)
        var pages = 1
        val cursors = HashSet<String>()
        while (detail.nextPage != null && detail.tracks.size < atLeast && pages < pageLimit) {
            val cursor = checkNotNull(detail.nextPage)
            if (!cursors.add(cursor)) throw SpotifyFailure("Spotify returned a repeated page. Try again.")
            val next = source.detail(item, cursor)
            detail = next.copy(tracks = detail.tracks + next.tracks)
            pages++
        }
        return detail
    }
    private fun revalidateAfterMs(item: Music): Long = when (item.kind) {
        MusicKind.Playlist, MusicKind.Radio -> PLAYLIST_TTL_MS
        MusicKind.Artist -> ARTIST_TTL_MS
        else -> ALBUM_TTL_MS
    }
    private fun installLibrary(content: HomeLibrary, loading: Boolean) {
        val previous = mutableLibrary.value.content
        val merged = if (loading && content.savedTracksLoaded == 0 && previous != null) content.copy(
            albums = previous.albums, artists = previous.artists, savedTracks = previous.savedTracks,
            savedTrackCount = previous.savedTrackCount, savedTrackUris = previous.savedTrackUris,
            savedTracksLoaded = previous.savedTracksLoaded,
        ) else content
        // A read can predate a like: confirmed changes stay on top for as long as the service may lag.
        val now = System.currentTimeMillis()
        favoriteChanges.values.removeAll { it.confirmedAtMs != null && now - it.confirmedAtMs > FAVORITE_LAG_MS }
        val visible = applyFavoriteChanges(merged, favoriteChanges.values).let { library ->
            library.copy(playlists = library.playlists.map { playlist ->
                val mosaic = playlistCovers[playlist.id]
                when {
                    playlist.imageUrl == null -> mosaic?.let { playlist.copy(imageUrl = it) } ?: playlist.also { coverFromStoredPage(it.id) }
                    mosaic != null && playlist.imageUrl != mosaic -> {
                        // The playlist got a picture of its own.
                        playlistCovers.remove(playlist.id); saveCovers(); playlist
                    }
                    else -> playlist
                }
            })
        }
        val warning = visible.warning.takeIf { online.value && visible.playlists.isEmpty() }
        mutableLibrary.value = LibraryUi(visible, loading, warning)
        savedIds.value = (visible.savedTrackUris + (visible.albums + visible.artists + visible.playlists).map(Music::id)).toSet()
        val liked = mutableDetails.value[LIKED_SONGS]?.content
        if (liked != null) mutableDetails.update { it + (LIKED_SONGS to DetailUi(liked.copy(
            tracks = visible.savedTracks,
            nextPage = visible.savedTracksLoaded.takeIf { size -> size < visible.savedTrackUris.size }?.toString(),
            total = visible.savedTrackCount,
        ))) }
    }
    fun loadMoreSavedTracks() {
        val source = collections ?: return
        val current = mutableLibrary.value.content ?: return
        if (mutableLibrary.value.loading || current.savedTracksLoaded >= current.savedTrackUris.size) return
        libraryJob = viewModelScope.launch {
            mutableLibrary.update { it.copy(loading = true, error = null) }
            try { installLibrary(source.loadMore(current), false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableLibrary.update { it.copy(loading = false, error = readable(failure)) } }
        }
    }
    fun loadDetail(item: Music, refresh: Boolean = false, more: Boolean = false) {
        if (item.isLocalPlaylist) { installLocalPlaylist(item.id); return }
        val source = catalog ?: return
        val shown = mutableDetails.value[item.id]
        if (!more && !refresh && shown?.content != null && shown.error == null) return
        if (shown?.loading == true && !refresh) return
        detailJobs[item.id]?.cancel()
        detailJobs[item.id] = viewModelScope.launch {
            mutableDetails.update { it + (item.id to (shown ?: DetailUi()).copy(loading = true, error = null)) }
            // What the device knows is on screen at once; the service is only asked when it is old.
            var stored: CatalogDetail? = null
            if (shown?.content == null && item.id != LIKED_SONGS) {
                stored = withContext(Dispatchers.IO) { store.loadDetail(item.id) }?.let { page ->
                    // Pages stored before mosaics took the first track's cover for a playlist without picture.
                    val bare = mutableLibrary.value.content?.playlists?.firstOrNull { it.id == item.id }?.let { it.id in playlistCovers } == true
                    if (bare) page.copy(item = page.item.copy(imageUrl = playlistMosaic(page.tracks) ?: page.item.imageUrl)) else page
                }
                stored?.let {
                    source.seed(it.tracks + it.related)
                    mutableDetails.update { all -> all + (item.id to DetailUi(it, loading = true)) }
                }
            }
            val previous = stored?.let { DetailUi(it) } ?: shown
            if (!more && !refresh && item.id != LIKED_SONGS && previous?.content != null) {
                val age = withContext(Dispatchers.IO) { store.detailAgeMs(item.id) }
                if (!online.value || age < revalidateAfterMs(item)) {
                    mutableDetails.update { it + (item.id to DetailUi(previous.content)) }
                    return@launch
                }
            }
            try {
                val page = if (more) previous?.content?.nextPage else null
                val result = if (item.id == LIKED_SONGS) {
                    val saved = mutableLibrary.value.content
                    val from = page?.toIntOrNull() ?: 0
                    val uris = saved?.savedTrackUris.orEmpty()
                    val tracks = if (from == 0) saved?.savedTracks.orEmpty() else source.hydrate(uris.drop(from).take(100))
                    val next = if (from == 0) saved?.savedTracksLoaded ?: tracks.size else from + 100
                    CatalogDetail(item.copy(note = "${saved?.savedTrackCount ?: 0} saved tracks"), tracks,
                        nextPage = next.takeIf { it < uris.size }?.toString(), total = saved?.savedTrackCount ?: 0)
                } else if (more) source.detail(item, page)
                // Revalidating must not shorten what was already read in several pages.
                else fullDetail(source, item, atLeast = previous?.content?.tracks?.size ?: 0)
                val merged = if (more && previous?.content != null) result.copy(tracks = previous.content.tracks + result.tracks) else result
                mutableDetails.update { it + (item.id to DetailUi(merged)) }
                rememberCover(merged)
                if (item.id != LIKED_SONGS) withContext(Dispatchers.IO) { store.saveDetail(item.id, merged) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (auth: SpotifyAuthRequired) { accountLost(auth) }
            catch (failure: Exception) {
                val known = previous?.content
                // Known pages stay readable; only what cannot be shown at all is an error.
                mutableDetails.update { it + (item.id to if (known != null) DetailUi(known) else DetailUi(error = readable(failure))) }
            }
        }
    }
    private fun installLocalPlaylist(id: String) {
        val playlist = localPlaylistStore.get(id) ?: return
        val music = playlist.asMusic()
        ui.register(music)
        mutableDetails.update { it + (id to DetailUi(CatalogDetail(music, playlist.tracks))) }
    }
    fun loadRadio(seed: Music, open: (Music) -> Unit) {
        val source = radio ?: return
        viewModelScope.launch {
            try {
                val result = source.station(seed)
                mutableDetails.update { it + (result.item.id to DetailUi(result)) }
                open(result.item)
                play(result.tracks, 0)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableMessage.value = readable(failure) }
        }
    }
    /** Album and artists of a track, read through the catalogue contract used by detail pages. */
    suspend fun trackRelations(track: Music): List<Music> =
        if (track.isLocalTrack) emptyList() else detailForActions(track)?.related.orEmpty()
    /** Menu actions read the stored page first, so they work the same without network. */
    private suspend fun detailForActions(item: Music): CatalogDetail? {
        mutableDetails.value[item.id]?.content?.let { return it }
        withContext(Dispatchers.IO) { store.loadDetail(item.id) }?.let { return it }
        val fetched = catalog?.detail(item) ?: return null
        withContext(Dispatchers.IO) { store.saveDetail(item.id, fetched) }
        return fetched
    }
    internal val spotifyPlaylists = SpotifyPlaylists(app)
    internal suspend fun createSpotifyPlaylist(name: String, tracks: List<Music>, published: Boolean): Music {
        val created = spotifyPlaylists.create(name, tracks, published)
        ui.register(created)
        val current = mutableLibrary.value.content
        if (current != null) installLibrary(current.copy(playlists = listOf(created) + current.playlists.filterNot { it.id == created.id }), false)
        mutableDetails.update { it + (created.id to DetailUi(CatalogDetail(created, tracks, total = tracks.size))) }
        withContext(Dispatchers.IO) { store.saveDetail(created.id, CatalogDetail(created, tracks, total = tracks.size)) }
        return created
    }
    internal fun spotifyPlaylistChanged(playlist: Music) {
        ui.register(playlist)
        mutableLibrary.value.content?.let { content ->
            installLibrary(content.copy(playlists = content.playlists.map { if (it.id == playlist.id) playlist else it }), false)
        }
        loadDetail(playlist, refresh = true)
    }
    internal suspend fun playlistSuggestions(playlist: Music, tracks: List<Music>): List<Music> =
        spotifyPlaylists.recommendations(playlist.id, tracks, mutableLibrary.value.content?.savedTracks.orEmpty())

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    internal suspend fun addSpotifyPlaylistTracks(playlist: Music, tracks: List<Music>) {
        val before = tracksOf(playlist)
        spotifyPlaylists.add(playlist.id, tracks) {
            detailJobs.remove(playlist.id)?.cancel()
            val content = mutableDetails.value[playlist.id]?.content ?: CatalogDetail(playlist, before)
            val updated = content.copy(tracks = before + tracks, total = content.total + tracks.size, nextPage = null)
            mutableDetails.update { it + (playlist.id to DetailUi(updated)) }
            withContext(Dispatchers.IO) { store.saveDetail(playlist.id, updated) }
            if (fr.spotifeur.playback.DownloadCenter.collections.value.containsKey(playlist.id)) {
                fr.spotifeur.playback.DownloadCenter.enqueue(app, updated.item, updated.tracks)
            }
        }
    }

    /** Downloads follow the account's own right to keep music offline. */
    suspend fun offlineAllowed(): Boolean {
        val session = SpotifyAccount.require(getApplication())
        val offline = spotifyAttribute(transport, session, "offline")
        return offline == null && spotifyAttribute(transport, session, "type") == "premium" || offline == "true" || offline == "1"
    }
    private var lyrics: SpotifyLyricsSource? = null
    fun lyricsSource(): SpotifyLyricsSource? = lyrics
    fun showMessage(text: String) { mutableMessage.value = text }
    suspend fun tracksOf(item: Music): List<Music> { return when {
        item.id == LIKED_SONGS -> {
            if (online.value) collections?.let { completeSavedTracks(it) }
            mutableLibrary.value.content?.savedTracks.orEmpty()
        }
        item.isLocalPlaylist -> localPlaylistStore.get(item.id)?.tracks.orEmpty()
        item.isLocalTrack || item.kind == MusicKind.Track -> listOf(item)
        else -> {
            val known = detailForActions(item) ?: return emptyList()
            if (known.nextPage == null || !online.value) known.tracks
            else {
                val source = catalog ?: return known.tracks
                val complete = fullDetail(source, item, pageLimit = Int.MAX_VALUE)
                mutableDetails.update { it + (item.id to DetailUi(complete)) }
                withContext(Dispatchers.IO) { store.saveDetail(item.id, complete) }
                complete.tracks
            }
        }
    }
    }
    fun play(queue: List<Music>, index: Int = 0) {
        if (remote == null) { mutableMessage.value = "Sign in to your Spotify account to listen to music."; return }
        player.play(queue, index)
    }
    fun consumeMessage() { mutableMessage.value = null }

    fun search(query: String, debounce: Boolean = true) {
        searchJob?.cancel()
        val trimmed = query.trim()
        val source = remote
        mutableDiscovery.update {
            it.copy(query = query, results = emptyList(), nextPage = null, error = null, loading = source != null && trimmed.length >= 2)
        }
        if (trimmed.length < 2) return
        if (source == null) {
            return
        }
        if (!online.value) { showLocalResults(trimmed, null); return }
        loadPage(source, trimmed, null, debounce)
    }

    fun searchNext() {
        val source = remote ?: return
        val state = discovery.value
        val token = state.nextPage ?: return
        if (!state.loading) loadPage(source, state.query.trim(), token, debounce = false)
    }

    private fun loadPage(source: SpotifySearch, query: String, token: String?, debounce: Boolean) {
        mutableDiscovery.update { it.copy(loading = true, error = null) }
        searchJob = viewModelScope.launch {
            try {
                if (debounce) delay(400)
                val locale = app.resources.configuration.locales.get(0) ?: Locale.getDefault()
                val page = source.search(query, locale, token)
                mutableDiscovery.update {
                    it.copy(
                        loading = false,
                        results = (it.results + page.items).distinctBy(Music::id),
                        nextPage = page.nextToken?.takeUnless { next -> next == token },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                val message = when (failure) {
                    is SpotifyFailure -> failure.message
                    is InvalidProtocolBufferException -> "Unexpected Spotify response format."
                    else -> "The Spotify search was interrupted."
                }
                if (token == null) showLocalResults(query, message) else mutableDiscovery.update { it.copy(loading = false, error = message) }
            }
        }
    }

    /** Without the service, search what the device holds: the library, its pins and listened tracks. */
    private fun showLocalResults(query: String, failure: String?) {
        searchJob = viewModelScope.launch {
            val found = withContext(Dispatchers.Default) {
                val content = mutableLibrary.value.content
                val listened = fr.spotifeur.data.AudioIndex.get(app).all().map { it.track }.filter { it.title.isNotEmpty() }
                val candidates = pins.value + content?.playlists.orEmpty() + content?.albums.orEmpty() +
                    content?.artists.orEmpty() + content?.savedTracks.orEmpty() + listened
                val wanted = query.lowercase()
                candidates.filter { it.title.lowercase().contains(wanted) || it.subtitle.lowercase().contains(wanted) }
                    .distinctBy(Music::id).take(MAX_LOCAL_RESULTS)
            }
            mutableDiscovery.update {
                it.copy(loading = false, results = found, nextPage = null, error = failure.takeIf { found.isEmpty() })
            }
        }
    }

    fun connectSpotify(username: String, password: String) {
        if (username.isBlank() || password.isEmpty()) return
        connect { session -> session.connect(username.trim(), password, ::requestCode) }
    }

    fun connectSpotifyEmail(address: String) {
        val email = address.trim()
        if (email.isBlank()) return
        connect { session -> session.connectEmail(email, loginFlowId) { requestCode(it, email) } }
    }

    private fun connect(authenticate: suspend (SpotifySession) -> Unit) {
        if (mutableAccount.value.working || remote != null) return
        accountJob = viewModelScope.launch {
            mutableAccount.value = AccountUi(working = true)
            try {
                val session = SpotifySession(app, transport, vault, deviceId)
                authenticate(session)
                finishAccount(session)
                mutableAccount.value = AccountUi()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                showAccountError(failure)
            }
        }
    }

    private suspend fun requestCode(length: Int, email: String? = null): String {
        val deferred = CompletableDeferred<String>()
        pendingCode = deferred
        mutableAccount.value = AccountUi(working = true, codeLength = length, codeEmail = email)
        return try {
            deferred.await().also { mutableAccount.value = AccountUi(working = true) }
        } finally {
            if (pendingCode === deferred) pendingCode = null
        }
    }

    fun submitCode(code: String) {
        if (code.length == mutableAccount.value.codeLength && code.all { it in '0'..'9' }) {
            pendingCode?.complete(code)
        }
    }

    private fun finishAccount(session: SpotifySession) {
        SpotifyAccount.attach(session)
        catalog = SpotifyCatalog(transport, session)
        collections = SpotifyLibrary(transport, session, requireNotNull(catalog))
        radio = SpotifyRadio(transport, session)
        remote = SpotifySearch(transport, session)
        lyrics = SpotifyLyricsSource(transport, session)
        mutableDiscovery.update { it.copy(spotifyEnabled = true) }
        search(discovery.value.query)
        viewModelScope.launch { openCatalog() }
    }

    /** The library on disk comes first. The service is asked afterwards, and only when it can add something. */
    private suspend fun openCatalog() {
        val stored = withContext(Dispatchers.IO) { store.loadLibrary() }
        loadPendingFavorites()
        // Covers for playlists without a picture, from pages already on the device.
        loadCovers()
        stored?.playlists?.let { playlists ->
            val covers = withContext(Dispatchers.IO) {
                playlists.mapNotNull { playlist ->
                    val detail = store.loadDetail(playlist.id) ?: return@mapNotNull null
                    // Earlier versions filled a playlist without picture with its first track's cover.
                    val firstCover = detail.tracks.firstNotNullOfOrNull(Music::imageUrl)
                    val bare = playlist.imageUrl == null || playlist.id in playlistCovers ||
                        (playlist.imageUrl == detail.item.imageUrl && playlist.imageUrl == firstCover)
                    if (bare) playlist.id to (playlistMosaic(detail.tracks) ?: firstCover) else null
                }
            }
            covers.forEach { (id, url) -> if (url != null) playlistCovers[id] = url }
            saveCovers()
        }
        if (stored != null) installLibrary(stored.copy(playlists = stored.playlists.map {
            // A stored cover filled in by an earlier start yields to the mosaic computed now.
            if (it.id in playlistCovers) it.copy(imageUrl = null) else it
        }), false)
        else if (favoriteChanges.isNotEmpty()) savedIds.update { ids ->
            favoriteChanges.values.fold(ids) { all, change -> if (change.saved) all + change.item.id else all - change.item.id }
        }
        sendPendingFavorites()
        val age = withContext(Dispatchers.IO) { store.libraryAgeMs() }
        when {
            stored == null -> refreshLibrary()
            online.value && (age > LIBRARY_TTL_MS || stored.playlists.any { it.ownerUsername == null }) -> refreshLibrary()
            online.value -> collections?.let { source ->
                libraryJob = viewModelScope.launch {
                    try { completeSavedTracks(source); keepCatalogAvailable() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (auth: SpotifyAuthRequired) { accountLost(auth) }
                    catch (_: IOException) { /* The next connection completes it. */ }
                }
            }
        }
    }

    /** Only a refusal of the saved account leads back to the sign-in screen. */
    private fun accountLost(failure: SpotifyAuthRequired) {
        disconnectSpotify()
        mutableAccount.value = AccountUi(error = failure.message)
    }

    private fun showAccountError(failure: Exception) {
        if (failure is SpotifyAuthRequired) vault.clear()
        mutableAccount.value = AccountUi(
            error = when (failure) {
                is SpotifyFailure -> failure.message
                is InvalidProtocolBufferException -> "Unexpected Android login response."
                is IOException -> "The connection to Spotify was interrupted."
                else -> "Could not open the Spotify session on this device."
            },
        )
    }

    fun disconnectSpotify() {
        accountJob?.cancel()
        pendingCode?.cancel()
        pendingCode = null
        searchJob?.cancel()
        libraryJob?.cancel()
        detailJobs.values.forEach { it.cancel() }; detailJobs.clear()
        offlineSyncJob?.cancel()
        favoriteChanges.clear(); savePendingFavorites()
        player.stop(); SpotifyAccount.clear()
        vault.clear()
        viewModelScope.launch(Dispatchers.IO) { store.clear() }
        remote = null
        catalog = null; collections = null; radio = null; lyrics = null
        mutableLibrary.value = LibraryUi(); mutableDetails.value = emptyMap()
        savedIds.value = emptySet(); mutableRecent.value = emptyList()
        mutableAccount.value = AccountUi()
        mutableDiscovery.update { it.copy(spotifyEnabled = false) }
        search(discovery.value.query)
    }

    override fun onCleared() {
        spotifyPlaylists.close()
        player.close()
    }
    private fun readable(failure: Exception): String = when (failure) {
        is SpotifyFailure -> failure.message ?: "Spotify is unavailable. Try again."
        is InvalidProtocolBufferException -> "The Spotify response could not be read."
        is IOException -> "The connection to Spotify was interrupted. Try again."
        else -> "This operation could not be completed. Try again."
    }
    companion object {
        const val LIKED_SONGS = "spotify:collection:tracks"
        private const val KEY_PENDING_LIKES = "pending_likes"
        private const val KEY_PLAYLIST_COVERS = "playlist_covers"
        private const val KEY_PENDING_UNLIKES = "pending_unlikes"
        private const val FAVORITE_LAG_MS = 2 * 60 * 1000L
        private const val LIBRARY_TTL_MS = 10 * 60 * 1000L
        private const val PLAYLIST_TTL_MS = 12 * 60 * 60 * 1000L
        private const val ARTIST_TTL_MS = 7 * 24 * 60 * 60 * 1000L
        private const val ALBUM_TTL_MS = 30 * 24 * 60 * 60 * 1000L
        private const val BACKGROUND_REQUEST_GAP_MS = 250L
        private const val SAVE_EVERY_PAGES = 5
        private const val MAX_BACKGROUND_PAGES = 400
        private const val MAX_DETAIL_PAGES = 30
        private const val MAX_LOCAL_RESULTS = 60
        private const val COVERS_PER_PAGE = 60
        private const val MAX_BACKGROUND_COVERS = 1500
    }
}
