package fr.spotifeur.data

import fr.spotifeur.data.preferences.AppSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAndLyricsTest {
    private val spotify = Music("spotify:track:a", "A", "Artist", MusicKind.Track, imageUrl = "https://i.scdn.co/image/a")
    private val local = Music(
        "${LOCAL_TRACK_PREFIX}7", "B", "Artist", MusicKind.Track, durationMs = 1000,
        album = "Album", mediaUri = "content://media/external/audio/media/7", quality = "FLAC · 1012 kbps",
    )

    @Test fun localPlaylistsKeepSpotifyAndLocalTracksInOrder() {
        val playlist = LocalPlaylist("${LOCAL_PLAYLIST_PREFIX}1", "Mix", listOf(local, spotify))
        assertEquals(listOf(playlist), decodeLocalPlaylists(encodeLocalPlaylists(listOf(playlist))))
    }

    @Test fun foreignOrBrokenPlaylistsAreIgnored() {
        assertEquals(emptyList<LocalPlaylist>(), decodeLocalPlaylists("not json"))
        assertEquals(emptyList<LocalPlaylist>(), decodeLocalPlaylists("""[{"id":"spotify:playlist:x","name":"n","tracks":[]}]"""))
    }

    @Test fun qualityLabelLeavesOutWhatIsUnknown() {
        assertEquals("FLAC · 44.1 kHz · 1012 kbps", audioQualityLabel("audio/flac", 44100, 1012000))
        assertEquals("AAC", audioQualityLabel("audio/mp4a-latm", null, null))
        assertEquals("", audioQualityLabel(null, 0, 0))
    }

    @Test fun syncedLyricsKeepTheirTimes() {
        val body = """{"lyrics":{"syncType":"LINE_SYNCED","lines":[
            {"startTimeMs":"1000","words":"une"},{"startTimeMs":"2500","words":"♪"},{"startTimeMs":"4000","words":"deux"}]}}"""
        val lyrics = checkNotNull(decodeSpotifyLyrics(body))
        assertEquals(listOf(1000, 2500, 4000), lyrics.synced?.map { it.time })
        assertEquals(listOf("une", "", "deux"), lyrics.synced?.map { it.line })
        assertNull(lyrics.plain)
    }

    @Test fun unsyncedLyricsArePlainText() {
        val lyrics = checkNotNull(decodeSpotifyLyrics("""{"lyrics":{"syncType":"UNSYNCED","lines":[{"startTimeMs":"0","words":"une"}]}}"""))
        assertEquals(listOf("une"), lyrics.plain)
        assertNull(lyrics.synced)
    }

    @Test fun missingLyricsAreNotAnError() {
        assertNull(decodeSpotifyLyrics("""{"lyrics":{"syncType":"LINE_SYNCED","lines":[]}}"""))
        assertNull(decodeSpotifyLyrics("not json"))
    }

    @Test fun settingsSurviveARoundTripAndOldBackups() {
        val custom = AppSettings(useSmoothCorners = false, language = "en", navBarCornerRadius = 12, hapticsEnabled = false)
        assertEquals(custom, AppSettings.fromJson(custom.toJson()))
        val partial = AppSettings.fromJson(JSONObject().put("showScrollbar", false))
        assertFalse(partial.showScrollbar)
        assertTrue(partial.useSmoothCorners)
    }
}
