package fr.spotifeur.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineCacheTest {
    private fun track(id: String) = Music("spotify:track:$id", "Track $id", "Artist", MusicKind.Track, imageUrl = "https://i.scdn.co/image/$id")
    private val library = HomeLibrary(
        playlists = listOf(Music("spotify:playlist:p", "Mix", "Moi", MusicKind.Playlist, note = "3 titres")),
        albums = listOf(Music("spotify:album:a", "Album", "Artist", MusicKind.Album)),
        artists = emptyList(),
        savedTracks = listOf(track("1"), track("2")),
        savedTrackCount = 3,
        savedTrackUris = listOf("spotify:track:1", "spotify:track:2", "spotify:track:3"),
        savedTracksLoaded = 2,
    )

    @Test fun libraryAndPagesSurviveTheDisk() {
        assertEquals(library, decodeLibrary(encodeLibrary(library)))
        val detail = CatalogDetail(library.playlists[0], library.savedTracks, related = library.albums, nextPage = "100", total = 250)
        assertEquals(detail, decodeDetail(encodeDetail(detail)))
        assertNull(decodeLibrary("{}"))
        assertNull(decodeDetail("not json"))
    }

    @Test fun aLikeStaysVisibleWhateverAnOlderReadSays() {
        val liked = applyFavoriteChanges(library, listOf(FavoriteChange(track("9"), saved = true)))
        assertEquals("spotify:track:9", liked.savedTrackUris.first())
        assertEquals(track("9"), liked.savedTracks.first())
        assertEquals(4, liked.savedTrackCount)
        assertEquals(3, liked.savedTracksLoaded)
        // Applying it again to a read that already contains it changes nothing.
        assertEquals(liked, applyFavoriteChanges(liked, listOf(FavoriteChange(track("9"), saved = true))))
    }

    @Test fun anUnlikeStaysVisibleToo() {
        val unliked = applyFavoriteChanges(library, listOf(FavoriteChange(track("1"), saved = false)))
        assertFalse("spotify:track:1" in unliked.savedTrackUris)
        assertEquals(listOf(track("2")), unliked.savedTracks)
        assertEquals(2, unliked.savedTrackCount)
        assertEquals(1, unliked.savedTracksLoaded)
        val album = Music("spotify:album:a", "Album", "Artist", MusicKind.Album)
        assertTrue(applyFavoriteChanges(library, listOf(FavoriteChange(album, saved = false))).albums.isEmpty())
    }

    @Test fun aMediaPlaylistNamesItsFileAndLength() {
        val text = """
            #EXTM3U
            #EXT-X-MAP:URI="https://audio4-fa.scdn.co/audio/ABCDEF0123456789abcdef0123456789abcdef01?1_sig",BYTERANGE="1483@0"
            #EXTINF:10.0,
            #EXT-X-BYTERANGE:160000@1483
            https://audio4-fa.scdn.co/audio/ABCDEF0123456789abcdef0123456789abcdef01?1_sig
            #EXTINF:4.0,
            #EXT-X-BYTERANGE:64000
            https://audio4-fa.scdn.co/audio/ABCDEF0123456789abcdef0123456789abcdef01?1_sig
            #EXT-X-ENDLIST
        """.trimIndent()
        val (file, address) = checkNotNull(parseMediaPlaylist(text, "https://host/audio.m3u8"))
        assertEquals("abcdef0123456789abcdef0123456789abcdef01", file.hex)
        assertEquals(1483L + 160000 + 64000, file.expectedBytes)
        assertTrue(address.startsWith("https://audio4-fa.scdn.co/audio/"))
        assertEquals(file.hex, audioFileHex("/audio/ABCDEF0123456789abcdef0123456789abcdef01"))
        assertNull(audioFileHex("/image/abc"))
    }

    @Test fun oneVariantIsKeptPreferringWhatIsCached() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS="mp4a.40.2",AVERAGE-BANDWIDTH=128003
            low/audio.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=256000,CODECS="mp4a.40.2",AVERAGE-BANDWIDTH=256007
            high/audio.m3u8
        """.trimIndent()
        assertEquals(listOf("high/audio.m3u8"), variantUris(selectVariant(master) { _, _ -> 0 }))
        assertEquals(listOf("low/audio.m3u8"), variantUris(selectVariant(master) { uri, _ -> if (uri.startsWith("low")) 5_000 else 0 }))
    }

    @Test fun thumbnailsBecomeTheLargestCover() {
        val hash = "0123456789abcdef01234567"
        assertEquals("https://i.scdn.co/image/ab67616d0000b273$hash", largestSpotifyImage("https://i.scdn.co/image/ab67616d00004851$hash"))
        assertEquals("https://i.scdn.co/image/ab67616d0000b273$hash", largestSpotifyImage("https://i.scdn.co/image/ab67616d00001e02$hash"))
        assertEquals("https://i.scdn.co/image/ab6761610000e5eb$hash", largestSpotifyImage("https://i.scdn.co/image/ab67616100005174$hash"))
        val other = "https://mosaic.scdn.co/640/abc"
        assertEquals(other, largestSpotifyImage(other))
        assertEquals("https://i.scdn.co/image/ab67706c0000da84$hash", largestSpotifyImage("https://i.scdn.co/image/ab67706c0000da84$hash"))
    }

    @Test fun playlistsWithoutPictureShowTheirFirstFourCovers() {
        fun cover(n: Int) = Music("spotify:track:$n", "T", "A", MusicKind.Track,
            imageUrl = "https://i.scdn.co/image/ab67616d00001e02" + n.toString().padStart(24, '0'))
        val ids = (1..4).joinToString("") { "ab67616d0000b273" + it.toString().padStart(24, '0') }
        assertEquals("https://mosaic.scdn.co/640/$ids", playlistMosaic(listOf(cover(1), cover(1), cover(2), cover(3), cover(4), cover(5))))
        assertEquals(largestSpotifyImage(cover(1).imageUrl), playlistMosaic(listOf(cover(1), cover(2))))
        assertNull(playlistMosaic(emptyList()))
    }
}
