package fr.spotifeur.data.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import fr.spotifeur.BuildConfig
import fr.spotifeur.data.NetworkMonitor
import fr.spotifeur.data.SpotifyAccount
import fr.spotifeur.data.SpotifyAuthRequired
import fr.spotifeur.data.attributeSummary
import fr.spotifeur.data.describeBandwidth
import fr.spotifeur.data.spotifyAttributes
import fr.spotifeur.data.preferences.SettingsStore

/**
 * Text a user can paste into a bug report. It holds the app and device versions, the account's
 * entitlements (plan type, catalogue, audio quality and similar flags), library sizes and the recent
 * entries of [DiagnosticLog]. No username, e-mail, token, track or playlist identifier is included.
 */
internal suspend fun buildDiagnosticReport(context: Context, librarySizes: String): String {
    val app = context.applicationContext
    val session = SpotifyAccount.current() ?: runCatching { SpotifyAccount.restore(app) }.getOrNull()
    val attributes = session?.let {
        if (!NetworkMonitor.online.value) it.lastAttributes
        else try { spotifyAttributes(SpotifyAccount.transport, it, "Diagnostic report") }
        catch (auth: SpotifyAuthRequired) { null }
        catch (_: java.io.IOException) { it.lastAttributes }
    }
    val limit = session?.let { runCatching { it.maximumAudioBandwidth() }.getOrNull() }
    val settings = SettingsStore.get(app).settings.value
    val network = app.getSystemService(ConnectivityManager::class.java)
    val capabilities = network?.getNetworkCapabilities(network.activeNetwork)
    val transport = when {
        capabilities == null -> "none"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "other"
    }
    val vpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    return buildString {
        appendLine("Spotifeur diagnostic report")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}, native engine=${BuildConfig.NATIVE_RUNTIME}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Locale: ${java.util.Locale.getDefault().toLanguageTag()}, network: $transport, vpn=$vpn, metered=${network?.isActiveNetworkMetered}, online=${NetworkMonitor.online.value}")
        appendLine("Spotify account: ${if (session == null) "not signed in" else "signed in"}")
        if (session != null) {
            appendLine("Entitlements: ${attributes?.let(::attributeSummary) ?: "unavailable"}")
            appendLine("Audio limit: ${limit?.let(::describeBandwidth) ?: "unknown"}")
        }
        appendLine("Quality: wifi=${settings.streamingQualityWifi} mobile=${settings.streamingQualityMobile} download=${settings.downloadQuality}")
        appendLine("Library: $librarySizes")
        appendLine()
        appendLine("Recent events (UTC, oldest first):")
        val lines = DiagnosticLog.lines()
        if (lines.isEmpty()) appendLine("none") else lines.forEach(::appendLine)
    }
}
