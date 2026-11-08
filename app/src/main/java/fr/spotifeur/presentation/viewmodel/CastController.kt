package fr.spotifeur.presentation.viewmodel

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import androidx.mediarouter.media.MediaRouterParams
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import fr.spotifeur.R
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.Music
import fr.spotifeur.data.isLocalTrack
import fr.spotifeur.playback.LocalCastServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/** What the Cast device reports while it plays the queue. */
data class RemotePlayback(val isPlaying: Boolean, val positionMs: Long, val durationMs: Long, val index: Int)

/**
 * Output devices for PixelPlayer's panel. Bluetooth and system routes carry every track. Google Cast
 * receives local files only: a Spotify track stays on the phone, since sending it would mean taking
 * its audio out of the protected playback path.
 */
class CastController(
    private val model: SpotifeurModel,
    private val toast: (String) -> Unit,
) {
    private val context: Context get() = model.getApplication()
    private val router: MediaRouter by lazy {
        MediaRouter.getInstance(context).also {
            it.routerParams = MediaRouterParams.Builder().setMediaTransferReceiverEnabled(true).build()
        }
    }
    private val selector by lazy {
        MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .addControlCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO)
            .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
            .build()
    }
    private val mutableRoutes = MutableStateFlow<List<MediaRouter.RouteInfo>>(emptyList())
    val castRoutes: StateFlow<List<MediaRouter.RouteInfo>> = mutableRoutes.asStateFlow()
    private val mutableSelected = MutableStateFlow<MediaRouter.RouteInfo?>(null)
    val selectedRoute: StateFlow<MediaRouter.RouteInfo?> = mutableSelected.asStateFlow()
    private val mutableRefreshing = MutableStateFlow(false)
    val isRefreshingRoutes: StateFlow<Boolean> = mutableRefreshing.asStateFlow()
    private val mutableConnecting = MutableStateFlow(false)
    val isCastConnecting: StateFlow<Boolean> = mutableConnecting.asStateFlow()
    private val mutableRemoteActive = MutableStateFlow(false)
    val isRemotePlaybackActive: StateFlow<Boolean> = mutableRemoteActive.asStateFlow()
    private val mutableRemote = MutableStateFlow<RemotePlayback?>(null)
    val remotePlayback: StateFlow<RemotePlayback?> = mutableRemote.asStateFlow()
    private val mutableRouteVolume = MutableStateFlow(0)
    val routeVolume: StateFlow<Int> = mutableRouteVolume.asStateFlow()
    private val mutableWifiConnected = MutableStateFlow(false)
    val isWifiEnabled: StateFlow<Boolean> = mutableWifiConnected.asStateFlow()
    private val mutableWifiRadio = MutableStateFlow(false)
    val isWifiRadioOn: StateFlow<Boolean> = mutableWifiRadio.asStateFlow()
    private val mutableWifiName = MutableStateFlow<String?>(null)
    val wifiName: StateFlow<String?> = mutableWifiName.asStateFlow()
    private val mutableBluetoothOn = MutableStateFlow(false)
    val isBluetoothEnabled: StateFlow<Boolean> = mutableBluetoothOn.asStateFlow()
    private val mutableBluetoothName = MutableStateFlow<String?>(null)
    val bluetoothName: StateFlow<String?> = mutableBluetoothName.asStateFlow()
    private val mutableBluetoothDevices = MutableStateFlow<List<BluetoothAudioDeviceState>>(emptyList())
    val bluetoothAudioDeviceStates: StateFlow<List<BluetoothAudioDeviceState>> = mutableBluetoothDevices.asStateFlow()

    private var server: LocalCastServer? = null
    private var session: CastSession? = null
    private var castContext: CastContext? = null

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
        override fun onRouteVolumeChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = publishRoutes()
        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = publishRoutes()
    }

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(castSession: CastSession) { mutableConnecting.value = true }
        override fun onSessionStarted(castSession: CastSession, sessionId: String) = begin(castSession)
        override fun onSessionStartFailed(castSession: CastSession, error: Int) { mutableConnecting.value = false }
        override fun onSessionResuming(castSession: CastSession, sessionId: String) { mutableConnecting.value = true }
        override fun onSessionResumed(castSession: CastSession, wasSuspended: Boolean) = begin(castSession)
        override fun onSessionResumeFailed(castSession: CastSession, error: Int) { mutableConnecting.value = false }
        override fun onSessionSuspended(castSession: CastSession, reason: Int) = Unit
        override fun onSessionEnding(castSession: CastSession) = end()
        override fun onSessionEnded(castSession: CastSession, error: Int) = end()
    }

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = publishRemote()
        override fun onQueueStatusUpdated() = publishRemote()
    }
    private val progress = RemoteMediaClient.ProgressListener { _, _ -> publishRemote() }

    private fun ensureCastContext(): CastContext? = castContext ?: runCatching {
        CastContext.getSharedInstance(context).also {
            it.sessionManager.addSessionManagerListener(sessions, CastSession::class.java)
            castContext = it
        }
    }.getOrNull()

    fun refreshCastRoutes() {
        ensureCastContext()
        mutableRefreshing.value = true
        router.addCallback(selector, routerCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN or MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        publishRoutes()
        refreshConnectivity()
        mutableRefreshing.value = false
    }

    fun stopDiscovery() = router.removeCallback(routerCallback)

    private fun publishRoutes() {
        mutableRoutes.value = router.routes.filter { it.matchesSelector(selector) && it.isEnabled }
        val selected = router.selectedRoute
        mutableSelected.value = selected
        mutableRouteVolume.value = selected.volume
        mutableBluetoothName.value = selected.takeIf { it.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH_A2DP }?.name
    }

    @Suppress("DEPRECATION", "MissingPermission")
    fun refreshConnectivity() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        mutableWifiConnected.value = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        mutableWifiRadio.value = context.applicationContext.getSystemService(WifiManager::class.java).isWifiEnabled
        mutableWifiName.value = null
        mutableBluetoothOn.value = runCatching { context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true }.getOrDefault(false)
    }

    fun selectRoute(route: MediaRouter.RouteInfo) { route.select(); publishRoutes() }
    fun disconnect() { router.unselect(MediaRouter.UNSELECT_REASON_STOPPED); publishRoutes() }
    fun setRouteVolume(volume: Int) {
        router.selectedRoute.requestSetVolume(volume)
        mutableRouteVolume.value = volume
    }

    private val client: RemoteMediaClient? get() = session?.remoteMediaClient

    private fun begin(castSession: CastSession) {
        session = castSession
        mutableConnecting.value = false
        val state = model.player.state.value
        val track = state.track
        val queue = state.queue.filter(Music::isLocalTrack)
        if (track == null || !track.isLocalTrack || queue.isEmpty()) {
            toast(context.getString(R.string.cast_local_only))
            return
        }
        val device = castSession.castDevice ?: return
        val address = localAddress() ?: return
        server?.close()
        val files = LocalCastServer(context, device.inetAddress, address).also { server = it }
        val items = queue.map { music ->
            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                putString(MediaMetadata.KEY_TITLE, music.title)
                putString(MediaMetadata.KEY_ARTIST, music.subtitle)
            }
            val info = MediaInfo.Builder(files.publish(Uri.parse(music.mediaUri)))
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(context.contentResolver.getType(Uri.parse(music.mediaUri)) ?: "audio/mpeg")
                .setMetadata(metadata).build()
            MediaQueueItem.Builder(info).setAutoplay(true).build()
        }.toTypedArray()
        val startIndex = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        val remote = castSession.remoteMediaClient ?: return
        remote.registerCallback(mediaCallback)
        remote.addProgressListener(progress, 1000L)
        remote.queueLoad(items, startIndex, MediaStatus.REPEAT_MODE_REPEAT_OFF, state.positionMs.toLong(), null)
        model.player.pause()
        mutableRemoteActive.value = true
    }

    private fun end() {
        val last = mutableRemote.value
        session?.remoteMediaClient?.run {
            removeProgressListener(progress)
            unregisterCallback(mediaCallback)
        }
        session = null
        server?.close(); server = null
        mutableConnecting.value = false
        if (mutableRemoteActive.value) {
            mutableRemoteActive.value = false
            mutableRemote.value = null
            // Playback comes back to the phone where the receiver stopped, paused.
            last?.let { model.player.seek(it.positionMs.toInt()) }
        }
    }

    private fun publishRemote() {
        val remote = client ?: return
        val status = remote.mediaStatus ?: return
        val index = status.getIndexById(status.currentItemId) ?: 0
        mutableRemote.value = RemotePlayback(remote.isPlaying, remote.approximateStreamPosition, remote.streamDuration, index)
    }

    fun togglePlayback() { client?.togglePlayback() }
    fun next() { client?.queueNext(null) }
    fun previous() { client?.queuePrev(null) }
    fun seek(positionMs: Long) { client?.seek(MediaSeekOptions.Builder().setPosition(positionMs).build()) }

    private fun localAddress(): java.net.InetAddress? = NetworkInterface.getNetworkInterfaces()?.toList()
        ?.filter { it.isUp && !it.isLoopback && it.name.startsWith("wlan") }
        ?.flatMap { it.inetAddresses.toList() }
        ?.firstOrNull { it is Inet4Address }
}
