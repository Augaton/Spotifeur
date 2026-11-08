package fr.spotifeur.presentation.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import fr.spotifeur.R
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.Music
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Creation and editing use the same account form; device-only lists remain available explicitly. */
@Composable
internal fun SpotifyPlaylistEditor(
    model: SpotifeurModel,
    playlist: Music? = null,
    initialItem: Music? = null,
    onDismiss: () -> Unit,
    onSaved: (Music?) -> Unit = {},
) {
    val context = LocalContext.current
    val coverFailure = stringResource(R.string.spotify_playlist_cover_retry)
    val saveFailure = stringResource(R.string.spotify_playlist_failed)
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(playlist?.title.orEmpty()) }
    var local by remember { mutableStateOf(false) }
    var published by remember { mutableStateOf(false) }
    var knownVisibility by remember { mutableStateOf(playlist == null) }
    var image by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) image = uri }
    LaunchedEffect(playlist?.id) {
        if (playlist != null) try { published = model.spotifyPlaylists.published(playlist.id); knownVisibility = true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (playlist == null) R.string.spotify_playlist_new else R.string.spotify_playlist_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(100) }, label = { Text(stringResource(R.string.playlist_name)) }, singleLine = true, enabled = !busy)
                if (playlist == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(!local, { if (!busy) local = false }, enabled = !busy)
                        Text(stringResource(R.string.spotify_playlist_account), Modifier.clickable(enabled = !busy) { local = false })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(local, { if (!busy) local = true }, enabled = !busy)
                        Text(stringResource(R.string.spotify_playlist_device), Modifier.clickable(enabled = !busy) { local = true })
                    }
                }
                if (!local) {
                    if (image != null || playlist?.imageUrl != null) AsyncImage(image ?: playlist?.imageUrl, null, Modifier.size(100.dp))
                    OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !busy) { Text(stringResource(R.string.spotify_playlist_cover)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.spotify_playlist_profile), Modifier.weight(1f))
                        Switch(published, { published = it }, enabled = !busy && knownVisibility)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank() && (local || knownVisibility), onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        // Read and normalize the selected image before creating anything remotely.
                        val jpeg = if (!local && image != null) withContext(Dispatchers.IO) { playlistJpeg(context, checkNotNull(image)) } else null
                        val tracks = initialItem?.let { model.tracksOf(it) }.orEmpty()
                        if (local) {
                            val created = model.localPlaylistStore.create(name.trim())
                            if (tracks.isNotEmpty()) model.localPlaylistStore.add(created.id, tracks)
                            onSaved(created.asMusic()); onDismiss()
                        } else {
                            val created = if (playlist == null) model.createSpotifyPlaylist(name, tracks, published) else playlist.also {
                                if (name.trim() != it.title) model.spotifyPlaylists.rename(it.id, name)
                                model.spotifyPlaylists.setPublished(it.id, published)
                            }
                            // Record creation immediately: an upload failure must not create a second playlist on retry.
                            try {
                                if (jpeg != null) model.spotifyPlaylists.setCover(created.id, jpeg)
                            } catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                model.showMessage(coverFailure)
                            }
                            model.spotifyPlaylistChanged(created.copy(title = name.trim()))
                            onSaved(created)
                            onDismiss()
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: saveFailure }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun playlistJpeg(context: android.content.Context, uri: Uri): ByteArray {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri).use { requireNotNull(it); BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Image illisible." }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1200) sample *= 2
    val bitmap = resolver.openInputStream(uri).use { input ->
        BitmapFactory.decodeStream(requireNotNull(input), null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: error("Image illisible.")
    val orientation = runCatching {
        resolver.openInputStream(uri).use { ExifInterface(requireNotNull(it)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
    val oriented = if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    try {
        val side = minOf(oriented.width, oriented.height)
        val crop = Bitmap.createBitmap(oriented, (oriented.width - side) / 2, (oriented.height - side) / 2, side, side)
        val square = Bitmap.createScaledBitmap(crop, 640, 640, true)
        return try { ByteArrayOutputStream().also { square.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray() }
        finally { if (square !== crop) square.recycle(); if (crop !== oriented) crop.recycle() }
    } finally { if (oriented !== bitmap) oriented.recycle(); bitmap.recycle() }
}
