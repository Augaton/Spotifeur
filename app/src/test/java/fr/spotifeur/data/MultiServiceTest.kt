package fr.spotifeur.data

import fr.spotifeur.data.youtube.MatchQuery
import fr.spotifeur.data.youtube.TrackMatcher
import fr.spotifeur.data.youtube.YTM_TRACK_PREFIX
import fr.spotifeur.data.youtube.audioFormats
import fr.spotifeur.data.youtube.findContinuation
import fr.spotifeur.data.youtube.mergeVideoUploads
import fr.spotifeur.data.youtube.parseTrackRows
import fr.spotifeur.data.youtube.playlistTitle
import fr.spotifeur.data.youtube.preferredOrder
import fr.spotifeur.data.youtube.youTubePlaylistId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiServiceTest {
    private fun ytm(id: String, title: String, artist: String, seconds: Int) =
        Music("$YTM_TRACK_PREFIX$id", title, artist, MusicKind.Track, durationMs = seconds * 1000)

    // Best match ---------------------------------------------------------------------------------

    @Test fun bestMatchPrefersTheOriginalRecording() {
        val query = MatchQuery("spotify:track:x", "One More Time", "Daft Punk", 320_357, "Discovery")
        val candidates = listOf(
            ytm("cover", "One More Time (Piano Cover)", "Some Pianist", 300),
            ytm("live", "One More Time (Live)", "Daft Punk", 372),
            ytm("other", "One more time", "C-YU", 172),
            ytm("orig", "One More Time", "Daft Punk", 320),
        )
        assertEquals("${YTM_TRACK_PREFIX}orig", TrackMatcher.bestMatch(query, candidates, emptyList())?.first?.id)
    }

    @Test fun bestMatchReadsVideoTitlesAndChannels() {
        val query = MatchQuery("spotify:track:y", "Blinding Lights", "The Weeknd", 200_040)
        val videos = listOf(
            ytm("lyrics", "Blinding Lights (Lyrics)", "Lyric Channel", 201),
            ytm("official", "The Weeknd - Blinding Lights (Official Video)", "TheWeekndVEVO", 263),
            ytm("audio", "The Weeknd - Blinding Lights (Official Audio)", "TheWeekndVEVO", 201),
        )
        assertEquals("${YTM_TRACK_PREFIX}audio", TrackMatcher.bestMatch(query, emptyList(), videos)?.first?.id)
    }

    @Test fun bestMatchRefusesUnrelatedTracks() {
        val query = MatchQuery("spotify:track:z", "Clair de lune", "Claude Debussy", 300_000)
        val candidates = listOf(ytm("a", "Moonlight Sonata", "Beethoven", 900), ytm("b", "Lune", "Someone", 180))
        assertNull(TrackMatcher.bestMatch(query, candidates, emptyList()))
    }

    @Test fun titlesCompareWithoutVersionsAndFeaturings() {
        assertEquals(1.0, TrackMatcher.titleSimilarity("Song - Remastered 2011", "Song (Remastered)"), 0.0)
        assertEquals(1.0, TrackMatcher.titleSimilarity("Song (feat. Someone)", "Song"), 0.0)
        assertEquals(1.0, TrackMatcher.titleSimilarity("Café", "Cafe"), 0.0)
        assertTrue(TrackMatcher.durationSimilarity(200_000, 201_500) == 1.0)
        assertTrue(TrackMatcher.durationSimilarity(200_000, 260_000) == 0.0)
    }

    // CSV ----------------------------------------------------------------------------------------

    @Test fun csvKeepsSourcesAndReferences() {
        val tracks = listOf(
            Music("spotify:track:4uLU6hMCjMI75M1A2tKUQC", "Never, \"Gonna\"", "Rick Astley", MusicKind.Track, durationMs = 213_000,
                album = "Whenever", imageUrl = "https://i.scdn.co/image/ab67616d0000b273abc"),
            ytm("dQw4w9WgXcQ", "Line\nbreak", "Artist", 212),
            Music("${LOCAL_TRACK_PREFIX}12", "Local", "Me", MusicKind.Track, mediaUri = "content://media/external/audio/media/12"),
            Music("spotify:local:Artist:Album:Title:180", "Title", "Artist", MusicKind.Track, durationMs = 180_000),
        )
        val csv = PlaylistCsv.export("Mix, été", tracks)
        assertTrue(csv.startsWith("playlist,source,id,title,artists,album,duration_ms,image_url,url\r\n"))
        assertTrue(csv.contains(",youtube_music,"))
        assertTrue(csv.contains("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        val imported = PlaylistCsv.import(csv, "fallback")
        assertEquals("Mix, été", imported.name)
        assertEquals(tracks.map(Music::id), imported.tracks.map(Music::id))
        assertEquals(tracks.map(Music::title), imported.tracks.map(Music::title))
        assertEquals(213_000, imported.tracks.first().durationMs)
        assertEquals(listOf(MusicSource.Spotify, MusicSource.YouTubeMusic, MusicSource.Local, MusicSource.Local), imported.tracks.map { it.source })
    }

    @Test fun csvRejectsForeignFilesAndSkipsUnknownRows() {
        val result = runCatching { PlaylistCsv.import("name,artist\nA,B\n", "x") }
        assertTrue(result.isFailure)
        val partial = PlaylistCsv.import("id,title\nspotify:album:x,Album\nytm:track:abc,Song\n", "Fallback")
        assertEquals("Fallback", partial.name)
        assertEquals(listOf("ytm:track:abc"), partial.tracks.map(Music::id))
    }

    // Spotify local files -------------------------------------------------------------------------

    @Test fun spotifyLocalReferencesAreDecoded() {
        val track = checkNotNull(spotifyLocalTrack("spotify:local:Daft+Punk:Discovery:One+More+Time%3A+Edit:320"))
        assertEquals("One More Time: Edit", track.title)
        assertEquals("Daft Punk", track.subtitle)
        assertEquals("Discovery", track.album)
        assertEquals(320_000, track.durationMs)
        assertTrue(track.isLocalTrack)
        assertNull(spotifyLocalTrack("spotify:track:abc"))
    }

    @Test fun localReferencesFindTheirFile() {
        val files = listOf(
            Music("${LOCAL_TRACK_PREFIX}1", "One More Time", "Daft Punk", MusicKind.Track, durationMs = 320_500, mediaUri = "content://1"),
            Music("${LOCAL_TRACK_PREFIX}2", "One More Time", "Other", MusicKind.Track, durationMs = 100_000, mediaUri = "content://2"),
        )
        val reference = checkNotNull(spotifyLocalTrack("spotify:local:Daft+Punk:Discovery:One+More+Time:320"))
        assertEquals("content://1", withLocalFile(reference, files).mediaUri)
        val missing = checkNotNull(spotifyLocalTrack("spotify:local:Nobody::Unknown:100"))
        assertNull(withLocalFile(missing, files).mediaUri)
    }

    @Test fun playlistPagesKeepLocalReferencesInOrder() {
        val page = proto {
            writeByteArray(5, proto {
                writeByteArray(3, proto { writeString(1, "spotify:track:4uLU6hMCjMI75M1A2tKUQC") })
                writeByteArray(3, proto { writeString(1, "spotify:local:A:B:C:10") })
                writeByteArray(3, proto { writeString(1, "spotify:episode:4uLU6hMCjMI75M1A2tKUQC") })
            })
        }
        val decoded = decodePlaylistPage(page, Music("spotify:playlist:x", "", "", MusicKind.Playlist))
        assertEquals(listOf("spotify:track:4uLU6hMCjMI75M1A2tKUQC", "spotify:local:A:B:C:10"), decoded.entries)
        assertEquals(listOf("spotify:track:4uLU6hMCjMI75M1A2tKUQC"), decoded.uris)
    }

    // Links --------------------------------------------------------------------------------------

    @Test fun playlistLinksAreRecognized() {
        assertEquals("PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI", youTubePlaylistId("https://music.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI&si=x"))
        assertEquals("PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI", youTubePlaylistId("https://www.youtube.com/watch?v=abc&list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"))
        assertEquals("PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI", youTubePlaylistId("https://music.youtube.com/browse/VLPLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"))
        assertNull(youTubePlaylistId("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", spotifyPlaylistId("https://open.spotify.com/intl-fr/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc"))
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", spotifyPlaylistId("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
        assertNull(spotifyPlaylistId("https://open.spotify.com/album/37i9dQZF1DXcBWIGoYBM5M"))
    }

    @Test fun spotifyEmbedPagesListTracks() {
        val data = JSONObject().put("props", JSONObject().put("pageProps", JSONObject().put("state", JSONObject().put("data",
            JSONObject().put("entity", JSONObject().put("name", "Mix").put("trackList", org.json.JSONArray()
                .put(JSONObject().put("uri", "spotify:track:4uLU6hMCjMI75M1A2tKUQC").put("title", "Song").put("subtitle", "Artist").put("duration", 1000))
                .put(JSONObject().put("uri", "spotify:local:A:B:Local:5").put("title", "Local").put("subtitle", "A"))))))))
        val content = checkNotNull(SpotifyPublicPlaylist.parse("""<html><script id="__NEXT_DATA__" type="application/json">$data</script></html>"""))
        assertEquals("Mix", content.name)
        assertEquals(listOf("spotify:track:4uLU6hMCjMI75M1A2tKUQC", "spotify:local:A:B:Local:5"), content.tracks.map(Music::id))
    }

    // InnerTube parsing --------------------------------------------------------------------------

    private fun run(text: String, pageType: String? = null) = JSONObject().put("text", text).apply {
        if (pageType != null) put("navigationEndpoint", JSONObject().put("browseEndpoint", JSONObject()
            .put("browseEndpointContextSupportedConfigs", JSONObject().put("browseEndpointContextMusicConfig", JSONObject().put("pageType", pageType)))))
    }

    private fun row(videoId: String, title: String, vararg subtitle: JSONObject, fixed: String? = null) = JSONObject().put("musicResponsiveListItemRenderer",
        JSONObject().put("flexColumns", org.json.JSONArray()
            .put(JSONObject().put("musicResponsiveListItemFlexColumnRenderer", JSONObject().put("text", JSONObject().put("runs", org.json.JSONArray()
                .put(JSONObject().put("text", title).put("navigationEndpoint", JSONObject().put("watchEndpoint", JSONObject().put("videoId", videoId))))))))
            .put(JSONObject().put("musicResponsiveListItemFlexColumnRenderer", JSONObject().put("text", JSONObject().put("runs", org.json.JSONArray(subtitle.toList()))))))
            .apply {
                if (fixed != null) put("fixedColumns", org.json.JSONArray().put(JSONObject().put("musicResponsiveListItemFixedColumnRenderer",
                    JSONObject().put("text", JSONObject().put("runs", org.json.JSONArray().put(JSONObject().put("text", fixed)))))))
                put("thumbnail", JSONObject().put("musicThumbnailRenderer", JSONObject().put("thumbnail", JSONObject().put("thumbnails",
                    org.json.JSONArray().put(JSONObject().put("url", "https://lh3.googleusercontent.com/abc=w60-h60-l90-rj"))))))
            })

    @Test fun searchAndPlaylistRowsBecomeTracks() {
        val bullet = JSONObject().put("text", " • ")
        val response = JSONObject().put("contents", org.json.JSONArray()
            .put(row("fa5IWHDbftI", "One More Time", run("Daft Punk", "MUSIC_PAGE_TYPE_ARTIST"), bullet, run("Discovery", "MUSIC_PAGE_TYPE_ALBUM"), bullet, run("5:21")))
            .put(row("fOT0BUpITw8", "BELLAKEO", run("Peso Pluma"), run(" & "), run("Anitta"), fixed = "3:55"))
            .put(row("vid", "Live", run("Song"), bullet, run("Someone"), bullet, run("1.2M views"), bullet, run("1:02:03")))
            .put(JSONObject().put("continuationItemRenderer", JSONObject().put("continuationEndpoint", JSONObject()
                .put("continuationCommand", JSONObject().put("token", "NEXT"))))))
        val tracks = parseTrackRows(response)
        assertEquals(listOf("ytm:track:fa5IWHDbftI", "ytm:track:fOT0BUpITw8", "ytm:track:vid"), tracks.map(Music::id))
        assertEquals("Daft Punk", tracks[0].subtitle)
        assertEquals("Discovery", tracks[0].album)
        assertEquals(321_000, tracks[0].durationMs)
        assertEquals("Peso Pluma & Anitta", tracks[1].subtitle)
        assertEquals(235_000, tracks[1].durationMs)
        assertEquals("Someone", tracks[2].subtitle)
        assertEquals(3_723_000, tracks[2].durationMs)
        assertEquals("https://lh3.googleusercontent.com/abc=w544-h544-l90-rj", tracks[0].imageUrl)
        assertEquals(MusicSource.YouTubeMusic, tracks[0].source)
        assertEquals("NEXT", findContinuation(response, preferShelf = false))
    }

    @Test fun playlistHeaderGivesTheTitle() {
        val response = JSONObject().put("contents", JSONObject().put("musicResponsiveHeaderRenderer", JSONObject()
            .put("title", JSONObject().put("runs", org.json.JSONArray().put(JSONObject().put("text", "Popular Music Videos"))))))
        assertEquals("Popular Music Videos", playlistTitle(response))
    }

    @Test fun audioFormatsPreferKnownLengthsAndBitrate() {
        val response = JSONObject().put("streamingData", JSONObject().put("adaptiveFormats", org.json.JSONArray()
            .put(JSONObject().put("itag", 137).put("mimeType", "video/mp4").put("url", "v"))
            .put(JSONObject().put("itag", 140).put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"").put("bitrate", 131080).put("contentLength", "5186561").put("url", "a"))
            .put(JSONObject().put("itag", 251).put("mimeType", "audio/webm; codecs=\"opus\"").put("bitrate", 157657).put("contentLength", "5316281").put("url", "b"))
            .put(JSONObject().put("itag", 999).put("mimeType", "audio/webm").put("bitrate", 999999).put("signatureCipher", "s=x&url=y"))))
        val formats = audioFormats(response)
        assertEquals(listOf(140, 251, 999), formats.map { it.itag })
        assertEquals(listOf(251, 140, 999), preferredOrder(formats).map { it.itag })
        assertNotNull(formats.last().signatureCipher)
    }

    @Test fun videoUploadsJoinOnlyFromKnownArtists() {
        val songs = listOf(ytm("s1", "Song", "Artist", 100), ytm("s2", "Other", "Artist", 100))
        val videos = listOf(ytm("v1", "Rare Track", "Artist", 100), ytm("v2", "Song (Karaoke)", "Karaoke Channel", 100), ytm("v3", "Song", "Artist", 100))
        assertEquals(listOf("s1", "v1", "s2"), mergeVideoUploads(songs, videos).map { it.id.removePrefix(YTM_TRACK_PREFIX) })
    }

    @Test fun localFilesBecomeSpotifyLocalReferences() {
        val file = Music("${LOCAL_TRACK_PREFIX}9", "Title: part 2", "A & B", MusicKind.Track, album = "Été", durationMs = 61_900)
        val uri = checkNotNull(spotifyLocalUri(file))
        assertEquals("spotify:local:A+%26+B:%C3%89t%C3%A9:Title%3A+part+2:61", uri)
        val back = checkNotNull(spotifyLocalTrack(uri))
        assertEquals(listOf("Title: part 2", "A & B", "Été", "61000"), listOf(back.title, back.subtitle, back.album, back.durationMs.toString()))
        assertNull(spotifyLocalUri(ytm("x", "t", "a", 1)))
    }

    @Test fun homeLayoutKeepsChoicesAndFillsNewShelves() {
        val chosen = listOf(
            fr.spotifeur.data.preferences.ShelfConfig(fr.spotifeur.data.preferences.HomeShelf.ALBUMS, true, fr.spotifeur.data.preferences.ShelfStyle.GRID, 6, showTitle = false),
            fr.spotifeur.data.preferences.ShelfConfig(fr.spotifeur.data.preferences.HomeShelf.PINS, false, fr.spotifeur.data.preferences.ShelfStyle.PILLS, 10),
        )
        val decoded = fr.spotifeur.data.preferences.HomeLayout.decode(fr.spotifeur.data.preferences.HomeLayout.encode(chosen))
        assertEquals(chosen, decoded.take(2))
        assertEquals(fr.spotifeur.data.preferences.HomeShelf.entries.toSet(), decoded.map { it.shelf }.toSet())
        // A style a shelf does not support falls back to its default.
        val wrong = fr.spotifeur.data.preferences.HomeLayout.decode("""[{"shelf":"liked","style":"grid","visible":true,"count":4}]""")
        assertEquals(fr.spotifeur.data.preferences.ShelfStyle.CARD, wrong.first().style)
        assertEquals(fr.spotifeur.data.preferences.HomeLayout.DEFAULT, fr.spotifeur.data.preferences.HomeLayout.decode("not json"))
    }

    @Test fun pageActionsKeepOrderAndIgnoreForeignButtons() {
        val page = fr.spotifeur.data.preferences.DetailPage.ALBUM
        val slots = fr.spotifeur.data.preferences.DetailActions.decode(page,
            """[{"action":"share","shown":true},{"action":"delete","shown":true},{"action":"download","shown":false}]""")
        assertEquals(listOf("share", "download", "save", "pin", "radio"), slots.map { it.action.key })
        assertEquals(listOf(true, false, true, true, false), slots.map { it.shown })
        val round = fr.spotifeur.data.preferences.DetailActions.decode(page, fr.spotifeur.data.preferences.DetailActions.encode(slots))
        assertEquals(slots, round)
    }
}
