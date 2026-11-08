package fr.spotifeur.data

import fr.spotifeur.presentation.utils.spotifyShareUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyRadioTest {
    private fun track(id: Int) = Music("spotify:track:$id", "t$id", "a", MusicKind.Track)

    @Test fun seedsAreSpreadAcrossTheAlbum() {
        val album = List(20) { track(it) }
        assertEquals(listOf(0, 4, 8, 12, 16), spreadAlbumSeeds(album).map { it.title.drop(1).toInt() })
        assertEquals(album.take(3), spreadAlbumSeeds(album.take(3)))
    }

    @Test fun stationsAreInterleavedWithoutDuplicates() {
        val merged = interleaveStations(listOf(listOf(track(1), track(2)), listOf(track(3), track(2), track(4))))
        assertEquals(listOf(1, 3, 2, 4), merged.map { it.title.drop(1).toInt() })
    }

    @Test fun interleavingStopsAtTheLimit() {
        assertEquals(2, interleaveStations(listOf(List(5) { track(it) }, List(5) { track(it + 10) }), limit = 2).size)
    }

    @Test fun shareLinksCoverSpotifyEntitiesOnly() {
        assertEquals("https://open.spotify.com/album/x", Music("spotify:album:x", "", "", MusicKind.Album).spotifyShareUrl())
        assertEquals("https://open.spotify.com/playlist/y", Music("spotify:playlist:y", "", "", MusicKind.Playlist).spotifyShareUrl())
        assertNull(Music("spotify:collection:tracks", "", "", MusicKind.Playlist).spotifyShareUrl())
    }
}
