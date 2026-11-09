package fr.spotifeur.data

import okhttp3.Request

/** Live: quality 0 can prepare AAC 128; AAC 256 returns 403. Quality 2 permits both. */
internal fun accountAudioBandwidth(audioQuality: String?): Long =
    if (audioQuality == "0") AudioQuality.NORMAL_BANDWIDTH else Long.MAX_VALUE

/** Reads one attribute of the account, as the native session does when it starts. */
internal suspend fun spotifyAttribute(transport: SpotifyTransport, session: SpotifySession, name: String): String? {
    val url = spotifyUrl("nebula/v1/configuration/attributes")
    val headers = session.headers(url.host)
    val bytes = transport.send(Request.Builder().url(url)
        .header("Authorization", "Bearer ${headers.user}").header("client-token", headers.client)
        .header("Accept", "application/x-protobuf").header("Spotify-App-Version", SpotifyAndroid.VERSION)
        .header("X-Client-Id", SpotifyAndroid.CLIENT_ID).header("App-Platform", "Android").build(), "Account attributes")
    val entry = Proto(bytes).messages(1).firstOrNull { it.text(1) == name } ?: return null
    val value = entry.message(2) ?: return null
    return value.text(4) ?: value.number(2)?.let { (it != 0).toString() } ?: value.number(3)?.toString()
}
