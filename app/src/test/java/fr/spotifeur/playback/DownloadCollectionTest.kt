package fr.spotifeur.playback

import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import org.junit.Assert.*
import org.junit.Test

class DownloadCollectionTest {
    private val first = Music("spotify:track:first", "First", "Artist", MusicKind.Track, artwork = 3)
    private val second = first.copy(id = "spotify:track:second", title = "Second")
    private val playlist = Music("spotify:playlist:list", "My playlist", "Creator", MusicKind.Playlist, ownerUsername = "owner", imageUrl = "https://i.scdn.co/image/cover")
    @Test fun enormousQueueKeepsOnlyTheConfiguredWindow() {
        assertEquals((500..510).toList(), preloadRange(500, 1635, 10).toList())
        assertEquals(21, preloadRange(0, 1635, Int.MAX_VALUE).count())
        assertEquals(listOf(1634), preloadRange(1634, 1635, 20).toList())
        assertTrue(preloadRange(-1, 1635, 10).isEmpty())
    }
    @Test fun requestIdentityAndDuplicateOrderSurviveRestart() {
        val groups = listOf(DownloadCollection(playlist, listOf(first, second, first)), DownloadCollection(first, listOf(first)))
        assertEquals(groups, decodeDownloadCollections(encodeDownloadCollections(groups)))
    }
    @Test fun sharedTrackCountsForEachOccurrenceWithoutCreatingLooseSingles() {
        val group = DownloadCollection(playlist, listOf(first, second, first))
        val entries = mapOf(first.id to DownloadedTrack(first, true, 100f, 1000, 1000), second.id to DownloadedTrack(second, false, 40f, 400, 1000))
        val progress = collectionProgress(group, entries, bytesPerSecond = 100.0)
        assertEquals(2, progress.completed)
        assertEquals(3, progress.total)
        assertEquals(80, progress.percent)
        assertEquals(6L, progress.remainingSeconds)
        assertFalse(progress.isComplete)
    }
    @Test fun unknownManifestDoesNotLookCompleteAndOfflineHasNoEta() {
        val group = DownloadCollection(playlist, listOf(first, second))
        val missing = collectionProgress(group, emptyMap(), paused = true, bytesPerSecond = 100.0)
        assertEquals(0, missing.percent)
        assertNull(missing.remainingSeconds)
        assertTrue(missing.paused)
        assertFalse(missing.isComplete)
        assertTrue(collectionProgress(group, emptyMap(), setOf(first.id)).failed)
    }
    @Test fun truncatedTransferCannotFillTheDownloadButton() {
        val progress = collectionProgress(DownloadCollection(playlist, listOf(first)), mapOf(first.id to DownloadedTrack(first, false, 100f)))
        assertFalse(progress.isComplete)
        assertEquals(99, progress.percent)
    }
    @Test fun cancellationKeepsCompletedSongsAsSinglesAndDropsTheRemainder() {
        val requests = mapOf(playlist.id to DownloadCollection(playlist, listOf(first, second, first)))
        val cancelled = cancelDownloadCollection(requests, playlist.id, setOf(first.id))
        assertEquals(mapOf(first.id to DownloadCollection(first, listOf(first))), cancelled)
        assertEquals(cancelled.values.toList(), decodeDownloadCollections(encodeDownloadCollections(cancelled.values)))
    }
    @Test fun cancellationPreservesSharedRequestsWithoutInventingExtraSingles() {
        val album = playlist.copy(id = "spotify:album:album", kind = MusicKind.Album)
        val requests = mapOf(playlist.id to DownloadCollection(playlist, listOf(first, second)),
            album.id to DownloadCollection(album, listOf(first)))
        assertEquals(mapOf(album.id to requests.getValue(album.id)), cancelDownloadCollection(requests, playlist.id, setOf(first.id)))
    }
    @Test fun cancellationBeforeAnyCompletionLeavesNoDownloadRequest() {
        val requests = mapOf(playlist.id to DownloadCollection(playlist, listOf(first, second)))
        assertTrue(cancelDownloadCollection(requests, playlist.id, emptySet()).isEmpty())
        assertEquals(requests, cancelDownloadCollection(requests, "absent", emptySet()))
    }
}
