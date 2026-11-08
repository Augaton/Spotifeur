package fr.spotifeur.data

import org.junit.Assert.*
import org.junit.Test

class TrackRelinkingTest {
    private val original = "spotify:track:0000000000000000000001"
    private val replacement = ByteArray(16).apply { this[15] = 2 }
    @Test fun oldReleaseUsesOnlyTheReplacementProvidedBySpotify() {
        val metadata = Proto(proto { writeByteArray(13, proto {
            writeByteArray(1, replacement); writeByteArray(12, proto { writeEnum(2, 1) })
        }) })
        assertEquals(gidUri("track", replacement), playableTrackUri(original, metadata))
    }
    @Test fun availableOriginalKeepsItsIdentityEvenWhenAnAlternativeExists() {
        val metadata = Proto(proto {
            writeByteArray(12, proto { writeEnum(2, 1) })
            writeByteArray(13, proto { writeByteArray(1, replacement) })
        })
        assertEquals(original, playableTrackUri(original, metadata))
    }
    @Test fun absentOrMalformedAlternativesDoNotCreateAnInventedReplacement() {
        assertEquals(original, playableTrackUri(original, Proto(byteArrayOf())))
        assertEquals(original, playableTrackUri(original, Proto(proto { writeByteArray(13, proto { writeByteArray(1, ByteArray(3)) }) })))
    }
}
