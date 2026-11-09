package fr.spotifeur.data

import fr.spotifeur.data.preferences.AppSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpotifyPlaylistProtocolTest {
    @Test fun createMatchesAndroidRootlistContractAndKeepsDuplicates() {
        val track = "spotify:track:0123456789abcdef012345"
        val request = Proto(createPlaylistRequest("My playlist", listOf(track, track)))
        assertEquals("", request.text(1))
        val operation = checkNotNull(request.message(2))
        assertEquals("create", operation.text(1))
        assertEquals("start", operation.text(2))
        assertEquals("My playlist", operation.text(4))
        assertEquals(1, operation.number(5))
        assertEquals(listOf(track, track), operation.texts(7))
    }
    @Test fun accountPlaylistsAndLocalPlaylistsCannotBeConfused() {
        assertEquals("spotify:playlist:0123456789abcdef012345", playlistUri("spotify:playlist:0123456789abcdef012345"))
        assertThrows(SpotifyFailure::class.java) { playlistUri("local:playlist:mine") }
        assertThrows(SpotifyFailure::class.java) { playlistUri("spotify:track:0123456789abcdef012345") }
    }
    @Test fun oldBackupsKeepBoundedQueueAndSettingsRoundTrip() {
        val migrated = AppSettings.fromJson(JSONObject("{\"preloadTracks\":2}"))
        assertFalse(migrated.preloadEntireQueue)
        assertEquals(10, AppSettings.fromJson(JSONObject("{\"preloadEntireQueue\":true,\"preloadTracks\":100}")).preloadTracks)
        assertTrue(migrated.preloadOnMobile)
        assertFalse(migrated.offlineMode)
        val chosen = migrated.copy(preloadEntireQueue = false, preloadTracks = 20, preloadOnMobile = false, offlineMode = true)
        assertEquals(chosen, AppSettings.fromJson(chosen.toJson()))
        val invalid = AppSettings.fromJson(JSONObject("{\"audioCacheLimitMb\":-1,\"preloadTracks\":1000000}"))
        assertEquals(512, invalid.audioCacheLimitMb)
        assertEquals(20, invalid.preloadTracks)
    }
    @Test fun ownershipSurvivesCatalogueStorage() {
        val playlist = Music("spotify:playlist:0123456789abcdef012345", "P", "0 tracks", MusicKind.Playlist, ownerUsername = "owner")
        assertEquals(listOf(playlist), decodePins(encodePins(listOf(playlist))))
    }
    @Test fun rowsRequestAsksForUrisAndRowIdsOfEveryRow() {
        val request = Proto(playlistRowsRequest("spotify:playlist:0123456789abcdef012345", 500, 500))
        assertEquals("spotify:playlist:0123456789abcdef012345", request.text(1))
        val query = checkNotNull(request.message(2))
        assertEquals(500, query.message(4)?.number(1))
        assertEquals(500, query.message(4)?.number(2))
        assertEquals(1, query.number(8))
        val item = checkNotNull(request.message(3)?.message(4))
        assertEquals(1, item.number(1))
        assertEquals(1, item.number(9))
    }
    @Test fun removalTargetsRowsAndKeepsLocalReferencesAsWritten() {
        val row = "0123456789abcdef0123456789abcdef"
        val request = Proto(playlistModification("spotify:playlist:0123456789abcdef012345", proto { writeString(1, "remove"); writeString(8, row) }))
        assertEquals("remove", request.message(2)?.text(1))
        assertEquals(listOf(row), request.message(2)?.texts(8))
        assertEquals("spotify:local:A:B:C+d:12", playlistEntryUri("spotify:local:A:B:C+d:12"))
        assertEquals("spotify:track:0123456789abcdef012345", playlistEntryUri("spotify:track:0123456789abcdef012345"))
        assertNull(playlistEntryUri("spotify:episode:0123456789abcdef012345"))
    }
    @Test fun swipeSettingsDefaultToQueueOnTheRightAndRoundTrip() {
        val defaults = AppSettings.fromJson(JSONObject("{}"))
        assertEquals("queue", defaults.swipeRight)
        assertEquals("none", defaults.swipeLeft)
        val chosen = defaults.copy(swipeRight = "next", swipeLeft = "remove")
        assertEquals(chosen, AppSettings.fromJson(chosen.toJson()))
        assertEquals(fr.spotifeur.presentation.components.TrackSwipeAction.NONE, fr.spotifeur.presentation.components.TrackSwipeAction.of("unknown"))
    }
}
