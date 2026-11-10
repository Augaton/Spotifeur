package fr.spotifeur.data

import okhttp3.Request

/**
 * Static (9.2.0.26, enum `p.qzy0`): the official app reads `audio-quality` as "0" STANDARD,
 * "1" VERY_HIGH, "2" LOSSLESS. Live: quality 0 can prepare AAC 128 and gets 403 for AAC 256;
 * quality 2 permits both. Without a known value, only a Premium account keeps 256 kbit/s; a refused
 * key lowers the limit at run time either way (see [AudioGrant]).
 */
internal fun accountAudioBandwidth(audioQuality: String?, type: String? = null): Long = when (audioQuality?.trim()) {
    "0" -> AudioQuality.NORMAL_BANDWIDTH
    "1", "2" -> Long.MAX_VALUE
    else -> if (type == "premium") Long.MAX_VALUE else AudioQuality.NORMAL_BANDWIDTH
}

/** The account's attributes, as the native session reads them when it starts. Booleans read "true"/"false". */
internal suspend fun spotifyAttributes(transport: SpotifyTransport, session: SpotifySession, operation: String = "Account attributes"): Map<String, String> {
    val url = spotifyUrl("nebula/v1/configuration/attributes")
    val headers = session.headers(url.host)
    val bytes = transport.send(Request.Builder().url(url)
        .header("Authorization", "Bearer ${headers.user}").header("client-token", headers.client)
        .header("Accept", "application/x-protobuf").header("Spotify-App-Version", SpotifyAndroid.VERSION)
        .header("X-Client-Id", SpotifyAndroid.CLIENT_ID).header("App-Platform", "Android").build(), operation)
    return decodeAttributes(bytes)
}

internal fun decodeAttributes(bytes: ByteArray): Map<String, String> = Proto(bytes).messages(1).mapNotNull { entry ->
    val name = entry.text(1) ?: return@mapNotNull null
    val value = entry.message(2) ?: return@mapNotNull null
    val text = value.text(4) ?: value.number(2)?.let { (it != 0).toString() } ?: value.number(3)?.toString()
        ?: return@mapNotNull null
    name to text
}.toMap()

/** Reads one attribute of the account. */
internal suspend fun spotifyAttribute(transport: SpotifyTransport, session: SpotifySession, name: String): String? =
    spotifyAttributes(transport, session)[name]

private val REPORTED_ATTRIBUTES = listOf("type", "catalogue", "audio-quality", "ads", "on-demand", "offline",
    "pick-and-shuffle", "streaming", "streaming-rules", "player-license")
private val PLAIN_VALUES = setOf("true", "false", "0", "1", "2", "3", "free", "premium", "open", "unlimited",
    "family", "duo", "student", "basic", "standard")

/** Entitlements only: values outside a small known vocabulary are reported as present or absent. */
internal fun attributeSummary(attributes: Map<String, String>): String = REPORTED_ATTRIBUTES.joinToString(" ") { name ->
    val value = attributes[name]
    "$name=" + when {
        value == null -> "absent"
        value.lowercase() in PLAIN_VALUES -> value.lowercase()
        else -> "present"
    }
}
