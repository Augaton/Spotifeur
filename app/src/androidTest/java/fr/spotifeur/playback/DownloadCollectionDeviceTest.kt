package fr.spotifeur.playback

import android.app.NotificationManager
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import fr.spotifeur.SpotifeurModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.spotifeur.MainActivity
import fr.spotifeur.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class DownloadCollectionDeviceTest {
    @Test fun cancellationKeepsBadgesAndCannotRestartTheCancelledRemainder(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyDownloadCollections") == "true")
        val ins = InstrumentationRegistry.getInstrumentation()
        val app = ins.targetContext
        val activity = ins.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val model = withContext(Dispatchers.Main) { ViewModelProvider(activity)[SpotifeurModel::class.java] }
        val session = SpotifyAccount.require(app)
        val library = SpotifyLibrary(SpotifyAccount.transport, session, SpotifyCatalog(SpotifyAccount.transport, session)).load()
        withContext(Dispatchers.Main) { DownloadCenter.start(app) }
        val tracks = library.savedTracks.filter { it.id !in DownloadCenter.downloads.value }.take(2)
        assertEquals(2, tracks.size)
        val group = Music("local:playlist:cancel-regression", "Cancellation test", "Spotifeur", MusicKind.Playlist)
        try {
            withContext(Dispatchers.Main) {
                model.ui.downloadItem(tracks[1])
                assertTrue(tracks[1].id in model.ui.downloadPreparations.value)
                model.ui.toggleDownloadItem(tracks[1])
                assertFalse(tracks[1].id in model.ui.downloadPreparations.value)
            }
            delay(1_000)
            assertFalse(tracks[1].id in DownloadCenter.collections.value)
            withContext(Dispatchers.Main) { DownloadCenter.add(app, tracks[0]) {} }
            withTimeout(90_000) { while (DownloadCenter.downloads.value[tracks[0].id]?.isComplete != true) delay(100) }
            NetworkMonitor.setOfflineForTest(true)
            withContext(Dispatchers.Main) {
                DownloadCenter.enqueue(app, group, tracks)
                // The first track is now retained only by this partly downloaded group.
                DownloadCenter.remove(app, tracks[0].id)
            }
            val before = checkNotNull(DownloadCenter.collections.value[group.id])
            assertEquals(1, before.completed); assertEquals(2, before.total)
            assertTrue(DownloadCenter.downloads.value[tracks[0].id]?.isComplete == true)
            assertTrue(DownloadCenter.downloads.value[tracks[1].id]?.queued == true)
            withContext(Dispatchers.Main) { model.ui.toggleDownloadItem(group) }
            assertNull(DownloadCenter.collections.value[group.id])
            assertNull(DownloadCenter.downloads.value[tracks[1].id])
            assertTrue(DownloadCenter.downloads.value[tracks[0].id]?.isComplete == true)
            assertTrue(DownloadCenter.collections.value[tracks[0].id]?.isComplete == true)
            val persisted = decodeDownloadCollections(File(app.noBackupFilesDir, "download-collections.json").readText())
            assertFalse(persisted.any { it.item.id == group.id })
            assertTrue(persisted.any { it.item.id == tracks[0].id })
            NetworkMonitor.setOfflineForTest(false)
            delay(2_000)
            assertNull(DownloadCenter.downloads.value[tracks[1].id])
            Log.i("SpotifeurDownloadsTest", "Cancel before enqueue + partial group: complete badge retained as single, queued title removed, no restart after reconnection")
        } finally {
            NetworkMonitor.setOfflineForTest(false)
            withContext(Dispatchers.Main) {
                DownloadCenter.remove(app, group.id); tracks.forEach { DownloadCenter.remove(app, it.id) }; activity.finish()
            }
        }
    }

    @Test fun backgroundPreparationProgressAndSharedFiles(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifyDownloadCollections") == "true")
        val ins = InstrumentationRegistry.getInstrumentation()
        val app = ins.targetContext
        val activity = ins.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val session = SpotifyAccount.require(app)
        val library = SpotifyLibrary(SpotifyAccount.transport, session, SpotifyCatalog(SpotifyAccount.transport, session)).load()
        withContext(Dispatchers.Main) { DownloadCenter.start(app) }
        val tracks = library.savedTracks.filter { it.id !in DownloadCenter.downloads.value }.take(2)
        assertEquals(2, tracks.size)
        // Local fixture identities never reach a Spotify mutation endpoint.
        val playlist = Music("local:playlist:download-regression", "Download test", "Spotifeur", MusicKind.Playlist)
        val album = playlist.copy(id = "download-test:album", kind = MusicKind.Album)
        val failures = java.util.concurrent.atomic.AtomicInteger()
        try {
            NetworkMonitor.setOfflineForTest(true)
            delay(300)
            withContext(Dispatchers.Main) {
                DownloadCenter.enqueue(app, playlist, tracks) { failures.incrementAndGet() }
                DownloadCenter.enqueue(app, album, listOf(tracks[0])) { failures.incrementAndGet() }
                activity.moveTaskToBack(true)
            }
            delay(2_000)
            val paused = checkNotNull(DownloadCenter.collections.value[playlist.id])
            assertTrue(paused.paused); assertEquals(2, paused.total); assertEquals(0, paused.completed)
            assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 7301 })
            val persisted = decodeDownloadCollections(File(app.noBackupFilesDir, "download-collections.json").readText())
            assertTrue(persisted.any { it.item == playlist && it.tracks == tracks })
            Log.i("SpotifeurDownloadsTest", "Background: request persisted, notification present, 0/2 paused")
            NetworkMonitor.setOfflineForTest(false)
            var previousPercent = -1
            withTimeout(180_000) { while (DownloadCenter.collections.value[playlist.id]?.isComplete != true) {
                DownloadCenter.collections.value[playlist.id]?.let { status -> if (status.percent != previousPercent) {
                    previousPercent = status.percent
                    Log.i("SpotifeurDownloadsTest", "Progress=${status.percent}%, tracks=${status.completed}/${status.total}, eta=${status.remainingSeconds}")
                } }
                assertEquals("Preparation failed", 0, failures.get()); delay(250)
            } }
            assertTrue(DownloadCenter.collections.value[album.id]?.isComplete == true)
            assertEquals(100, DownloadCenter.collections.value[playlist.id]?.percent)
            withContext(Dispatchers.Main) { DownloadCenter.add(app, tracks[0]) { failures.incrementAndGet() } }
            withTimeout(5_000) { while (DownloadCenter.collections.value[tracks[0].id]?.isComplete != true) delay(100) }
            withContext(Dispatchers.Main) { DownloadCenter.remove(app, playlist.id); DownloadCenter.remove(app, album.id) }
            withTimeout(10_000) { while (DownloadCenter.downloads.value[tracks[1].id] != null) delay(100) }
            assertTrue("Removing groups must preserve an explicitly retained single", DownloadCenter.downloads.value[tracks[0].id]?.isComplete == true)
            // The state alone is not enough: the removed file's audio must leave the disk.
            val store = DownloadCenter.cache(app)
            val removedKey = AudioCache.key(checkNotNull(AudioIndex.get(app).record(tracks[1].id)?.file).hex)
            val keptKey = AudioCache.key(checkNotNull(AudioIndex.get(app).record(tracks[0].id)?.file).hex)
            withTimeout(10_000) { while (store.getCachedBytes(removedKey, 0, Long.MAX_VALUE) > 0) delay(100) }
            assertTrue("The retained single keeps its audio", store.getCachedBytes(keptKey, 0, Long.MAX_VALUE) > 0)
            Log.i("SpotifeurDownloadsTest", "Removal reclaimed audio bytes; retained single still stored")
            assertNull(DownloadCenter.collections.value[playlist.id])
            assertNull(DownloadCenter.collections.value[album.id])
            Log.i("SpotifeurDownloadsTest", "Background: 2/2 downloaded, shared album + single retained, groups removed safely")
        } finally {
            NetworkMonitor.setOfflineForTest(false)
            withContext(Dispatchers.Main) {
                DownloadCenter.remove(app, playlist.id); DownloadCenter.remove(app, album.id)
                tracks.forEach { DownloadCenter.remove(app, it.id) }
                activity.finish()
            }
        }
    }
}
