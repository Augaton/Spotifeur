package fr.spotifeur.update

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

internal data class GitHubRelease(
    val tag: String,
    val notes: String,
    val page: String,
    val apk: ReleaseApk?,
) {
    companion object {
        const val REPOSITORY = "jean-voila/Spotifeur"
        const val LATEST_PAGE = "https://github.com/$REPOSITORY/releases/latest"
        const val API = "https://api.github.com/repos/$REPOSITORY/releases"

        fun parse(json: String): GitHubRelease? {
            val root = JSONObject(json)
            if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
            val tag = root.optString("tag_name")
            if (Version.parse(tag) == null) return null
            val page = root.optString("html_url").takeIf { isRepositoryUrl(it, "/releases/tag/") } ?: LATEST_PAGE
            val assets = root.optJSONArray("assets")
            val apks = (0 until (assets?.length() ?: 0)).mapNotNull { index ->
                val asset = assets!!.getJSONObject(index)
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (!name.endsWith(".apk", ignoreCase = true) || !isRepositoryUrl(url, "/releases/download/")) return@mapNotNull null
                if (asset.optString("state", "uploaded") != "uploaded") return@mapNotNull null
                val digest = asset.optString("digest").takeUnless { it.isBlank() || it == "null" }
                if (digest != null && !digest.matches(Regex("sha256:[0-9a-fA-F]{64}"))) return@mapNotNull null
                ReleaseApk(name, url, asset.optLong("size", -1), digest?.substringAfter(':')?.lowercase())
            }
            // Never guess between split builds, alternative signing keys or architectures.
            val expected = "Spotifeur-v${Version.parse(tag)}.apk"
            val apk = apks.singleOrNull { it.name.equals(expected, ignoreCase = true) }
                ?: apks.singleOrNull()
            return GitHubRelease(tag, root.optString("body").takeUnless { it == "null" }.orEmpty(), page, apk)
        }

        private fun isRepositoryUrl(value: String, suffix: String): Boolean {
            val url = value.toHttpUrlOrNull() ?: return false
            return url.scheme == "https" && url.host == "github.com" && url.port == 443 &&
                url.username.isEmpty() && url.password.isEmpty() &&
                url.encodedPath.startsWith("/$REPOSITORY$suffix", ignoreCase = true)
        }
    }
}

internal data class ReleaseApk(val name: String, val url: String, val bytes: Long, val sha256: String?)

/** Numeric release tags only; prereleases are deliberately outside the stable channel. */
internal class Version private constructor(private val parts: List<Long>) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        for (index in 0 until maxOf(parts.size, other.parts.size)) {
            val comparison = (parts.getOrNull(index) ?: 0).compareTo(other.parts.getOrNull(index) ?: 0)
            if (comparison != 0) return comparison
        }
        return 0
    }

    override fun toString() = parts.joinToString(".")

    companion object {
        fun parse(value: String): Version? {
            val normalized = value.trim().removePrefix("v").removePrefix("V")
            if (!normalized.matches(Regex("[0-9]+(\\.[0-9]+)*"))) return null
            val parts = normalized.split('.').map { it.toLongOrNull() ?: return null }
            return Version(parts)
        }
    }
}
