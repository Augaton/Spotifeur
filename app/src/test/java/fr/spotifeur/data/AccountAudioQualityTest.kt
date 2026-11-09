package fr.spotifeur.data

import org.junit.Assert.*
import org.junit.Test

class AccountAudioQualityTest {
    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS="mp4a.40.2"
        low/audio.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=256000,CODECS="mp4a.40.2"
        high/audio.m3u8
    """.trimIndent()

    @Test fun freeGrantOverridesHighPreferenceAndCachedHighFile() {
        val result = selectVariant(master, AudioQuality.VERY_HIGH_BANDWIDTH, accountAudioBandwidth("0")) { uri, _ ->
            if (uri.startsWith("high")) 1_000_000 else 0
        }
        assertEquals(listOf("low/audio.m3u8"), variantUris(result))
    }

    @Test fun premiumGrantPreservesBothQualityLevels() {
        for ((target, expected) in listOf(128_000L to "low/audio.m3u8", 256_000L to "high/audio.m3u8")) {
            assertEquals(listOf(expected), variantUris(selectVariant(master, target, accountAudioBandwidth("2")) { _, _ -> 0 }))
        }
    }

    @Test fun unsupportedOnlyVariantFailsBeforePlayingClearLead() {
        val highOnly = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=256000\nhigh/audio.m3u8"
        assertThrows(SpotifyFailure::class.java) {
            selectVariant(highOnly, 256_000, accountAudioBandwidth("0")) { _, _ -> 0 }
        }
    }

    @Test fun unknownGrantDoesNotInventAccountRestrictions() {
        assertEquals(Long.MAX_VALUE, accountAudioBandwidth(null))
        assertEquals(Long.MAX_VALUE, accountAudioBandwidth("future"))
    }
}
