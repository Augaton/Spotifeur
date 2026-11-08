package fr.spotifeur.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyLibraryTest {
    @Test
    fun savedTracksPaginationAdvancesOverUnavailableMetadataWithoutRepeatingThePage() {
        val uris = (1..225).map { "spotify:track:" + it.toString().padStart(22, '0') }
        fun track(index: Int) = Music(uris[index], "Track $index", "Artist", MusicKind.Track)
        val original = HomeLibrary(
            emptyList(), emptyList(), emptyList(), listOf(track(0), track(119)), uris.size,
            savedTrackUris = uris, savedTracksLoaded = 120,
        )
        // Spotify supplied 100 URIs, but only two have available metadata. One cached duplicate is included.
        val next = mergeSavedTracksPage(original, 120, 100, listOf(track(119), track(120), track(219)))
        assertEquals(220, next.savedTracksLoaded)
        assertEquals(listOf(uris[0], uris[119], uris[120], uris[219]), next.savedTracks.map(Music::id))
        assertEquals(5, next.savedTrackUris.drop(next.savedTracksLoaded).size)
        val final = mergeSavedTracksPage(next, 220, 100, listOf(track(224)))
        assertEquals(225, final.savedTracksLoaded)
        assertEquals(5, final.savedTracks.size)
        assertEquals(225, final.savedTrackCount)
    }

    @Test
    fun collectionPageRemovesTombstonesAndKeepsThePagingCursorSeparateFromSync() {
        val kept = "spotify:track:0000000000000000000001"
        val response = proto {
            writeByteArray(1, proto { writeString(1, kept); writeInt64(2, 1_794_000_000) })
            writeByteArray(1, proto { writeString(1, "spotify:track:0000000000000000000002"); writeInt64(2, 1_793_000_000) })
            writeByteArray(1, proto { writeString(1, "spotify:track:0000000000000000000002"); writeBool(3, true) })
            writeByteArray(1, proto { writeString(1, "spotify:episode:0000000000000000000003") })
            writeString(2, "next-page")
            writeString(3, "sync-cursor")
        }
        val page = decodeCollectionPage(response)
        assertEquals(listOf(kept to 1_794_000_000), page.items)
        assertEquals("next-page", page.nextPage)
        assertEquals(listOf("spotify:track:0000000000000000000002"), page.removedUris)
    }

    @Test
    fun rootlistDecorationsRemainAlignedAcrossFoldersAndUnavailableItems() {
        val playlist = "spotify:playlist:0000000000000000000001"
        val response = proto {
            writeInt32(2, 5)
            writeByteArray(5, proto {
                writeBool(2, true)
                listOf("spotify:start-group:folder:Music", playlist, "spotify:playlist:0000000000000000000002").forEach {
                    writeByteArray(3, proto { writeString(1, it) })
                }
                writeByteArray(4, byteArrayOf())
                writeByteArray(4, proto {
                    writeByteArray(2, proto {
                        writeString(1, "En boucle")
                        writeByteArray(13, proto { writeString(2, "https://i.scdn.co/image/cover") })
                    })
                    writeString(5, "owner")
                    writeSInt32(9, 200)
                })
                writeByteArray(4, proto { writeSInt32(9, 403) })
            })
        }
        val page = decodeRootlistPage(response)
        assertEquals(listOf(playlist), page.items.map(Music::id))
        assertEquals("En boucle", page.items.single().title)
        assertEquals("https://i.scdn.co/image/cover", page.items.single().imageUrl)
        assertEquals(3, page.rawCount)
        assertTrue(page.hasMore)
    }
}
