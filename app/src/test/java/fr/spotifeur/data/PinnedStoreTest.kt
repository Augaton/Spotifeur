package fr.spotifeur.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedStoreTest {
    @Test fun pinsSurviveEncodingInOrder() {
        val pins = listOf(
            Music("spotify:playlist:a", "Playlist", "353 tracks", MusicKind.Playlist, imageUrl = "https://i.scdn.co/image/a"),
            Music("spotify:album:b", "Album", "Artist", MusicKind.Album),
            Music("spotify:track:c", "Track", "Artist", MusicKind.Track, durationMs = 1234),
        )
        assertEquals(pins, decodePins(encodePins(pins)))
    }

    @Test fun nothingOrGarbageGivesNoPins() {
        assertEquals(emptyList<Music>(), decodePins(null))
        assertEquals(emptyList<Music>(), decodePins("not json"))
    }

    @Test fun unknownKindsAreDropped() {
        val encoded = """[{"id":"x","title":"t","subtitle":"s","kind":"Podcast","imageUrl":null,"durationMs":0}]"""
        assertEquals(emptyList<Music>(), decodePins(encoded))
    }
}
