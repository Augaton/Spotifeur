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
}
