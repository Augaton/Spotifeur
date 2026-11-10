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

    @Test fun officialQualityValuesMapToVariants() {
        // p.qzy0 in 9.2.0.26: "0" STANDARD, "1" VERY_HIGH, "2" LOSSLESS.
        assertEquals(AudioQuality.NORMAL_BANDWIDTH, accountAudioBandwidth("0", "premium"))
        assertEquals(Long.MAX_VALUE, accountAudioBandwidth("1", "free"))
        assertEquals(Long.MAX_VALUE, accountAudioBandwidth("2", "premium"))
    }

    @Test fun unknownGrantKeepsHighQualityForPremiumOnly() {
        assertEquals(Long.MAX_VALUE, accountAudioBandwidth(null, "premium"))
        assertEquals(Long.MAX_VALUE, accountAudioBandwidth("future", "premium"))
        assertEquals(AudioQuality.NORMAL_BANDWIDTH, accountAudioBandwidth(null, "free"))
        assertEquals(AudioQuality.NORMAL_BANDWIDTH, accountAudioBandwidth(null, null))
    }

    @Test fun summaryReportsEntitlementsWithoutOtherValues() {
        val summary = attributeSummary(mapOf("type" to "free", "audio-quality" to "0", "offline" to "false",
            "player-license" to "opaque-license-value", "country" to "FR"))
        assertTrue(summary.contains("type=free"))
        assertTrue(summary.contains("audio-quality=0"))
        assertTrue(summary.contains("offline=false"))
        assertTrue(summary.contains("player-license=present"))
        assertTrue(summary.contains("ads=absent"))
        assertFalse(summary.contains("opaque"))
        assertFalse(summary.contains("FR"))
    }
}
