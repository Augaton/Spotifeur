package fr.spotifeur.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import fr.spotifeur.BuildConfig
import fr.spotifeur.data.NetworkMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

internal sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Current : UpdateState
    data class Available(val release: GitHubRelease) : UpdateState
    data class Downloading(val release: GitHubRelease, val done: Long, val total: Long) : UpdateState
    data class Ready(val release: GitHubRelease, val file: File) : UpdateState
    data class Failed(val release: GitHubRelease? = null) : UpdateState
    data class Notes(val release: GitHubRelease) : UpdateState
}

/** Independent of the player and Spotify credentials; one operation per application process. */
internal class AppUpdater private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followSslRedirects(false).build()
    private val mutable = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private val activeCall = AtomicReference<Call?>()
    private val directory get() = File(app.cacheDir, "updates").apply { mkdirs() }
    private val preferences = app.getSharedPreferences("app_updates", Context.MODE_PRIVATE)

    fun check(manual: Boolean = false) {
        if (job?.isActive == true || (!manual && mutable.value != UpdateState.Idle)) return
        if (!NetworkMonitor.online.value) {
            if (manual) mutable.value = UpdateState.Failed()
            return
        }
        if (manual) mutable.value = UpdateState.Checking
        job = scope.launch {
            try {
                val release = withContext(Dispatchers.IO) { fetch("${GitHubRelease.API}/latest") }
                val installed = Version.parse(BuildConfig.VERSION_NAME)
                if (release != null && installed != null && Version.parse(release.tag)!! > installed) {
                    val due = UpdateReminder.isDue(release.tag, preferences.getString("dismissed_version", null),
                        preferences.getLong("dismissed_at", 0), System.currentTimeMillis())
                    if (manual || due) mutable.value = UpdateState.Available(release)
                    else showNotesAfterUpgrade()
                } else if (manual) {
                    mutable.value = UpdateState.Current
                } else {
                    showNotesAfterUpgrade()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (manual) mutable.value = UpdateState.Failed() }
        }
    }

    private suspend fun showNotesAfterUpgrade() {
        val previous = preferences.getString("seen_version", null)
        if (previous == null || previous == BuildConfig.VERSION_NAME) {
            preferences.edit().putString("seen_version", BuildConfig.VERSION_NAME).apply()
            return
        }
        val installed = withContext(Dispatchers.IO) { fetch("${GitHubRelease.API}/tags/v${BuildConfig.VERSION_NAME}") }
        preferences.edit().putString("seen_version", BuildConfig.VERSION_NAME).apply()
        if (installed != null && installed.notes.isNotBlank()) mutable.value = UpdateState.Notes(installed)
    }

    private fun fetch(url: String): GitHubRelease? {
        val call = client.newCall(request(url).header("Accept", "application/vnd.github+json").build())
        activeCall.set(call)
        try { call.execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("Release request failed")
            val body = response.body ?: throw IOException("Missing release")
            if (body.contentLength() > 2 * 1024 * 1024) throw IOException("Release response too large")
            val bytes = body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 2 * 1024 * 1024) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.size > 2 * 1024 * 1024) throw IOException("Release response too large")
            return GitHubRelease.parse(bytes.toString(Charsets.UTF_8)) ?: throw IOException("Unsupported release")
        } } finally { activeCall.compareAndSet(call, null) }
    }

    fun download(release: GitHubRelease) {
        val asset = release.apk ?: return
        if (job?.isActive == true) return
        mutable.value = UpdateState.Downloading(release, 0, asset.bytes)
        job = scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    directory.listFiles()?.forEach { it.delete() }
                    val target = File.createTempFile("spotifeur-", ".apk", directory)
                    val call = client.newCall(request(asset.url).build())
                    try {
                        activeCall.set(call)
                        call.execute().use { response ->
                            if (!response.isSuccessful) throw IOException("APK request failed")
                            val body = response.body ?: throw IOException("Missing APK")
                            val total = body.contentLength().takeIf { it > 0 } ?: asset.bytes
                            if (total > MAX_APK_BYTES || asset.bytes > MAX_APK_BYTES) throw IOException("APK too large")
                            var done = 0L
                            var lastProgress = 0L
                            val digest = MessageDigest.getInstance("SHA-256")
                            body.byteStream().use { input -> target.outputStream().buffered().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    done += count
                                    if (done > MAX_APK_BYTES) throw IOException("APK too large")
                                    output.write(buffer, 0, count)
                                    digest.update(buffer, 0, count)
                                    val now = android.os.SystemClock.elapsedRealtime()
                                    if (now - lastProgress >= 150) {
                                        mutable.value = UpdateState.Downloading(release, done, total)
                                        lastProgress = now
                                    }
                                }
                            } }
                            if (done == 0L || (asset.bytes > 0 && done != asset.bytes) || (total > 0 && done != total))
                                throw IOException("Incomplete APK")
                            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                            if (asset.sha256 != null && actual != asset.sha256) throw IOException("APK digest differs")
                        }
                        coroutineContext.ensureActive()
                        verifyArchive(target, release)
                        target
                    } catch (failure: Throwable) {
                        target.delete()
                        throw failure
                    } finally { activeCall.compareAndSet(call, null) }
                }
                mutable.value = UpdateState.Ready(release, file)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                coroutineContext.ensureActive()
                mutable.value = UpdateState.Failed(release)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyArchive(file: File, release: GitHubRelease) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = app.packageManager.getPackageArchiveInfo(file.path, flags) ?: throw IOException("Invalid APK")
        val installed = app.packageManager.getPackageInfo(app.packageName, flags)
        if (archive.packageName != app.packageName || PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed) ||
            Version.parse(archive.versionName.orEmpty())?.compareTo(Version.parse(release.tag)!!) != 0)
            throw IOException("APK does not match this update")
        fun signatures(info: PackageInfo): Set<String> {
            val certificates = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return certificates.orEmpty().map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
            }.toSet()
        }
        val current = signatures(installed)
        if (current.isEmpty() || current != signatures(archive)) throw IOException("APK signing certificate differs")
    }

    fun installerFailed() {
        val current = mutable.value as? UpdateState.Ready ?: return
        mutable.value = UpdateState.Failed(current.release)
    }

    fun dismiss() {
        val release = when (val current = mutable.value) {
            is UpdateState.Available -> current.release
            is UpdateState.Downloading -> current.release
            is UpdateState.Ready -> current.release
            is UpdateState.Failed -> current.release
            else -> null
        }
        if (release != null) preferences.edit().putString("dismissed_version", release.tag)
            .putLong("dismissed_at", System.currentTimeMillis()).apply()
        job?.cancel()
        activeCall.getAndSet(null)?.cancel()
        mutable.value = UpdateState.Idle
    }

    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", "Spotifeur/${BuildConfig.VERSION_NAME}")

    companion object {
        private const val MAX_APK_BYTES = 512L * 1024 * 1024
        @Volatile private var instance: AppUpdater? = null
        fun get(context: Context): AppUpdater = instance ?: synchronized(this) {
            instance ?: AppUpdater(context).also { instance = it }
        }
    }
}
