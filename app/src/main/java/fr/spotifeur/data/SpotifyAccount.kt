package fr.spotifeur.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

internal object SpotifyAccount {
    val transport = SpotifyTransport()
    private val lock = Mutex()
    @Volatile private var active: SpotifySession? = null
    /** Without a session, Spotify tracks play through their YouTube Music match. */
    val signedIn: Boolean get() = active != null
    /** The open session, without restoring one. */
    fun current(): SpotifySession? = active
    private val sessionListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    fun onSessionReplaced(listener: () -> Unit): AutoCloseable {
        sessionListeners.add(listener)
        return AutoCloseable { sessionListeners.remove(listener) }
    }
    fun create(context: Context): SpotifySession {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("spotify_device", 0)
        val id = prefs.getString("id", null) ?: UUID.randomUUID().toString().also { value ->
            prefs.edit { putString("id", value) }
        }
        return SpotifySession(app, transport, SessionVault(app), id)
    }
    suspend fun restore(context: Context): SpotifySession? = lock.withLock {
        active?.let { return@withLock it }
        val saved = withContext(Dispatchers.IO) { SessionVault(context).load() } ?: return@withLock null
        create(context).also { it.restore(saved); active = it }
    }
    suspend fun require(context: Context): SpotifySession = restore(context)
        ?: throw SpotifyFailure("Sign in to your Spotify account to listen to music.", 401)
    fun attach(session: SpotifySession) {
        val changed = active !== session
        active = session
        if (changed) sessionListeners.forEach { it() }
    }
    fun clear() { active = null; sessionListeners.forEach { it() } }
}
