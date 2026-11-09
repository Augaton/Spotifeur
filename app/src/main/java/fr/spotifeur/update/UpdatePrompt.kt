package fr.spotifeur.update

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.spotifeur.BuildConfig
import fr.spotifeur.R
import java.util.Locale

@Composable
internal fun UpdatePrompt() {
    val context = LocalContext.current
    val updater = remember { AppUpdater.get(context) }
    val state by updater.state.collectAsStateWithLifecycle()
    if (state == UpdateState.Idle) return
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    fun launchInstaller(current: UpdateState.Ready) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", current.file)
            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
            intent.clipData = ClipData.newRawUri("Spotifeur update", uri)
            installer.launch(intent)
        } catch (_: Exception) { updater.installerFailed() }
    }
    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) {
            (updater.state.value as? UpdateState.Ready)?.let(::launchInstaller)
        }
    }
    val release = when (val current = state) {
        is UpdateState.Available -> current.release
        is UpdateState.Downloading -> current.release
        is UpdateState.Ready -> current.release
        is UpdateState.Failed -> current.release
        is UpdateState.Notes -> current.release
        else -> null
    }
    fun openRelease() {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release?.page ?: GitHubRelease.LATEST_PAGE))) }
        catch (_: android.content.ActivityNotFoundException) { }
    }
    val title = when (state) {
        UpdateState.Checking -> R.string.update_checking
        UpdateState.Current -> R.string.update_current_title
        is UpdateState.Downloading -> R.string.update_downloading
        is UpdateState.Ready -> R.string.update_ready
        is UpdateState.Failed -> R.string.update_failed_title
        is UpdateState.Notes -> R.string.update_notes_title
        else -> R.string.update_available
    }
    AlertDialog(
        onDismissRequest = updater::dismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (release != null) Text("Spotifeur ${release.tag}", style = MaterialTheme.typography.titleMedium)
                when (val current = state) {
                    is UpdateState.Available, is UpdateState.Notes -> {
                        if (state is UpdateState.Available) Text(stringResource(R.string.update_installed, BuildConfig.VERSION_NAME))
                        if (release?.notes?.isNotBlank() == true) ReleaseNotes(release.notes)
                        if (state is UpdateState.Available && release?.apk == null) Text(stringResource(R.string.update_no_apk))
                    }
                    is UpdateState.Downloading -> {
                        if (current.total > 0) {
                            LinearProgressIndicator(progress = { (current.done.toFloat() / current.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.update_progress, megabytes(current.done), megabytes(current.total), (current.done * 100 / current.total).toInt()))
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(stringResource(R.string.update_downloaded, megabytes(current.done)))
                        }
                    }
                    is UpdateState.Ready -> Text(stringResource(R.string.update_install_description))
                    is UpdateState.Failed -> Text(stringResource(if (current.release == null) R.string.update_check_failed else R.string.update_download_failed))
                    UpdateState.Current -> Text(stringResource(R.string.update_current_description, BuildConfig.VERSION_NAME))
                    UpdateState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (val current = state) {
                is UpdateState.Available -> TextButton(onClick = { if (current.release.apk != null) updater.download(current.release) else openRelease() }) {
                    Text(stringResource(if (current.release.apk != null) R.string.update_action else R.string.update_release_page))
                }
                is UpdateState.Ready -> TextButton(onClick = {
                    try {
                        if (!context.packageManager.canRequestPackageInstalls()) {
                            installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                        } else {
                            launchInstaller(current)
                        }
                    } catch (_: Exception) { updater.installerFailed() }
                }) { Text(stringResource(R.string.update_install)) }
                is UpdateState.Failed -> TextButton(onClick = { current.release?.let(updater::download) ?: updater.check(manual = true) }) {
                    Text(stringResource(R.string.update_retry))
                }
                UpdateState.Current, is UpdateState.Notes -> TextButton(onClick = updater::dismiss) { Text(stringResource(R.string.update_close)) }
                else -> Unit
            }
        },
        dismissButton = {
            if (state !is UpdateState.Current && state !is UpdateState.Notes) TextButton(onClick = updater::dismiss) {
                Text(stringResource(if (state is UpdateState.Downloading || state == UpdateState.Checking) R.string.update_cancel else R.string.update_later))
            }
            if (state is UpdateState.Failed) TextButton(onClick = ::openRelease) { Text(stringResource(R.string.update_release_page)) }
        },
    )
}

@Composable
private fun ReleaseNotes(markdown: String) {
    markdown.lineSequence().take(160).map(String::trim).filter { it.isNotBlank() && it != "---" }.forEach { raw ->
        val heading = raw.startsWith('#')
        val text = raw.trimStart('#').trim().replace("**", "").replace("`", "")
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex("^[-*] "), "• ")
        Text(text, style = if (heading) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
    }
}

private fun megabytes(bytes: Long) = String.format(Locale.getDefault(), "%.1f", bytes / (1024.0 * 1024.0))
