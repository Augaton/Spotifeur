package fr.spotifeur.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifySearchTest {
    @Test
    fun keepsMusicAndReadsNestedTrackMetadata() {
        val track = entity("track", "Signal", "spotify:image:" + "a".repeat(40), 5, proto {
            writeByteArray(4, proto { writeString(2, "Nacre") })
            writeEnum(14, 2)
            writeByteArray(18, proto { writeInt64(1, 132) })
        })
        val album = entity("album", "Chambres", "https://example.com/image", 6, proto {
            writeString(1, "Mina Vale")
            writeString(1, "Nacre")
        })
        val episode = entity("episode", "Podcast", "", 10, byteArrayOf())
        val video = entity("track", "Clip", "", 5, proto { writeEnum(14, 3) })
        val response = proto {
            listOf(video, track, episode, album, track).forEach { writeByteArray(1, it) }
            writeString(4, "next-page")
            writeString(99, "unrecognized extension")
        }

        val page = decodeSearch(response)
        val results = page.items

        assertEquals(listOf(MusicKind.Track, MusicKind.Album), results.map(Music::kind))
        assertEquals("Signal", results[0].title)
        assertEquals("Nacre", results[0].subtitle)
        assertEquals(132_000, results[0].durationMs)
        assertEquals("https://i.scdn.co/image/" + "a".repeat(40), results[0].imageUrl)
        assertEquals("Mina Vale, Nacre", results[1].subtitle)
        assertNull(results[1].imageUrl)
        assertEquals("next-page", page.nextToken)
    }

    private fun entity(kind: String, name: String, image: String, field: Int, metadata: ByteArray) = proto {
        writeString(1, "spotify:$kind:example")
        writeString(2, name)
        writeString(3, image)
        writeByteArray(field, metadata)
    }
}
