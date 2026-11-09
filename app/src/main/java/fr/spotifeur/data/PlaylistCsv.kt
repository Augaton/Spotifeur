package fr.spotifeur.data

import fr.spotifeur.data.youtube.YTM_TRACK_PREFIX

/**
 * Playlists as CSV: one row per track, with its source and its reference in that source, so that
 * Spotifeur can rebuild the playlist on another device or after a reinstall.
 */
object PlaylistCsv {
    val HEADER = listOf("playlist", "source", "id", "title", "artists", "album", "duration_ms", "image_url", "url")

    data class Imported(val name: String, val tracks: List<Music>)

    fun export(name: String, tracks: List<Music>): String = buildString {
        appendRow(HEADER)
        tracks.forEach { track ->
            appendRow(listOf(
                name, sourceName(track.source), track.id, track.title, track.subtitle, track.album,
                track.durationMs.takeIf { it > 0 }?.toString().orEmpty(), track.imageUrl.orEmpty(), link(track),
            ))
        }
    }

    /** Reads a file written by [export]. Rows with an unknown source or no id are skipped. */
    fun import(text: String, fallbackName: String): Imported {
        val rows = parse(text.removePrefix("\uFEFF")).filter { row -> row.any(String::isNotBlank) }
        if (rows.isEmpty()) throw IllegalArgumentException("The file is empty.")
        val header = rows.first().map { it.trim().lowercase() }
        val columns = HEADER.associateWith(header::indexOf)
        if (columns.getValue("id") < 0) throw IllegalArgumentException("This file was not exported by Spotifeur.")
        fun List<String>.at(column: String) = columns.getValue(column).takeIf { it >= 0 }?.let(::getOrNull)?.trim().orEmpty()
        val tracks = rows.drop(1).mapNotNull { row ->
            val id = row.at("id").ifBlank { return@mapNotNull null }
            val valid = id.startsWith("spotify:track:") || id.startsWith(YTM_TRACK_PREFIX) || isLocalTrackId(id)
            if (!valid) return@mapNotNull null
            Music(
                id = id, title = row.at("title").ifBlank { id.substringAfterLast(':') }, subtitle = row.at("artists"),
                kind = MusicKind.Track, album = row.at("album"), durationMs = row.at("duration_ms").toIntOrNull() ?: 0,
                imageUrl = row.at("image_url").takeIf { it.startsWith("https://") },
            )
        }
        val name = rows.drop(1).firstNotNullOfOrNull { it.at("playlist").takeIf(String::isNotBlank) } ?: fallbackName
        return Imported(name, tracks)
    }

    fun sourceName(source: MusicSource) = when (source) {
        MusicSource.Spotify -> "spotify"
        MusicSource.YouTubeMusic -> "youtube_music"
        MusicSource.Local -> "local"
    }

    private fun link(track: Music): String = when {
        track.id.startsWith("spotify:track:") -> "https://open.spotify.com/track/" + track.id.substringAfterLast(':')
        track.id.startsWith(YTM_TRACK_PREFIX) -> "https://music.youtube.com/watch?v=" + track.id.removePrefix(YTM_TRACK_PREFIX)
        else -> track.mediaUri.orEmpty()
    }

    private fun StringBuilder.appendRow(values: List<String>) {
        values.forEachIndexed { index, value ->
            if (index > 0) append(',')
            if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || value != value.trim()) {
                append('"').append(value.replace("\"", "\"\"")).append('"')
            } else append(value)
        }
        append("\r\n")
    }

    /** RFC 4180: quoted fields may hold commas, quotes and line breaks. */
    fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (quoted) {
                if (character == '"') {
                    if (index + 1 < text.length && text[index + 1] == '"') { field.append('"'); index++ } else quoted = false
                } else field.append(character)
            } else when (character) {
                '"' -> quoted = true
                ',' -> { row += field.toString(); field.clear() }
                '\r' -> Unit
                '\n' -> { row += field.toString(); field.clear(); rows += row; row = ArrayList() }
                else -> field.append(character)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }
        return rows
    }
}
