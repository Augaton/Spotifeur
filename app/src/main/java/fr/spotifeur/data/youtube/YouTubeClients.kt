// Ported from Resona (https://github.com/code-saksham-hash/Resona, MIT, Copyright (c) 2026 Code.Saksham),
// whose client list comes from yt-dlp-android (MIT, Copyright (c) 2026 Hamzeh Lahwani).
package fr.spotifeur.data.youtube

import org.json.JSONObject

private const val WEB_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"

/**
 * InnerTube identities tried in order to resolve a playable audio stream without a Google account.
 * Which ones work anonymously changes over time; the order follows Resona's measurements.
 */
internal enum class YouTubeClient(
    val clientName: String,
    val clientNumber: String,
    val clientVersion: String,
    val userAgent: String,
    val androidSdkVersion: Int? = null,
    val extraFields: Map<String, Any> = emptyMap(),
    val apiKey: String? = null,
    val usePlaybackContext: Boolean = false,
    /** The signature timestamp comes from the web player script and only suits browser clients. */
    val sendsSignatureTimestamp: Boolean = false,
    val embedded: Boolean = false,
) {
    VISIONOS(
        "VISIONOS", "101", "0.1",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
        extraFields = mapOf("osName" to "visionOS", "osVersion" to "1.3.21O771", "deviceMake" to "Apple", "deviceModel" to "RealityDevice14,1"),
        usePlaybackContext = true,
    ),
    ANDROID_VR(
        "ANDROID_VR", "28", "1.65.10",
        "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        androidSdkVersion = 32,
        extraFields = mapOf("osName" to "Android", "osVersion" to "12L", "deviceMake" to "Oculus", "deviceModel" to "Quest 3"),
        usePlaybackContext = true,
    ),
    ANDROID(
        "ANDROID", "3", "21.02.35", "com.google.android.youtube/21.02.35 (Linux; U; Android 11) gzip",
        androidSdkVersion = 30, extraFields = mapOf("osName" to "Android", "osVersion" to "11"), usePlaybackContext = true,
    ),
    TVHTML5_SIMPLY_EMBEDDED(
        "TVHTML5_SIMPLY_EMBEDDED_PLAYER", "85", "2.0",
        "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/6.0 TV Safari/538.1",
        usePlaybackContext = true, embedded = true,
    ),
    IOS(
        "IOS", "5", "21.02.3", "com.google.ios.youtube/21.02.3 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
        extraFields = mapOf("osName" to "iPhone", "osVersion" to "18.3.2.22D82", "deviceMake" to "Apple", "deviceModel" to "iPhone16,2"),
        usePlaybackContext = true,
    ),
    MWEB(
        "MWEB", "2", "2.20260115.01.00",
        "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)",
        apiKey = WEB_API_KEY,
    ),
    WEB(
        "WEB", "1", "2.20260114.08.00",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        extraFields = mapOf("originalUrl" to "https://www.youtube.com", "platform" to "DESKTOP", "utcOffsetMinutes" to 0),
        apiKey = WEB_API_KEY, usePlaybackContext = true, sendsSignatureTimestamp = true,
    );

    val playerUrl: String
        get() = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false" + (apiKey?.let { "&key=$it" } ?: "")

    fun playerBody(videoId: String, signatureTimestamp: Int?, visitorData: String?): String {
        val client = JSONObject()
            .put("clientName", clientName).put("clientVersion", clientVersion).put("userAgent", userAgent)
            .put("hl", "en").put("gl", "US")
        androidSdkVersion?.let { client.put("androidSdkVersion", it) }
        visitorData?.let { client.put("visitorData", it) }
        extraFields.forEach { (key, value) -> client.put(key, value) }
        val body = JSONObject().put("context", JSONObject().put("client", client)).put("videoId", videoId)
            .put("racyCheckOk", true).put("contentCheckOk", true)
        if (embedded) body.put("thirdParty", JSONObject().put("embedUrl", "https://www.youtube.com/watch?v=$videoId"))
        if (usePlaybackContext) {
            val playback = JSONObject().put("html5Preference", "HTML5_PREF_WANTS")
            if (sendsSignatureTimestamp && signatureTimestamp != null) playback.put("signatureTimestamp", signatureTimestamp)
            body.put("playbackContext", JSONObject().put("contentPlaybackContext", playback))
        }
        return body.toString()
    }
}
