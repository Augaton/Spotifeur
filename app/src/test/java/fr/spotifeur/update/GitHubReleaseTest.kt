package fr.spotifeur.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GitHubReleaseTest {
    @Test fun comparesVersionsNumericallyAndPadsMissingComponents() {
        assertTrue(Version.parse("v1.10.0")!! > Version.parse("1.9.9")!!)
        assertTrue(Version.parse("v2")!! > Version.parse("1.99.99")!!)
        assertEquals(0, Version.parse("1.2.0")!!.compareTo(Version.parse("1.2")!!))
        assertTrue(Version.parse("1.2.2")!! < Version.parse("1.2.3")!!)
        listOf("", "v", "1.2-beta", "1..2", "-1.2", "1.2.999999999999999999999999").forEach {
            assertNull(it, Version.parse(it))
        }
    }

    @Test fun selectsReleaseApkInsteadOfChecksumsAndRendersNotes() {
        val apk = asset("Spotifeur-v1.3.0.apk").put("digest", "sha256:" + "ab".repeat(32))
        val release = GitHubRelease.parse(release(apk, asset("Spotifeur-v1.3.0.apk.sha256")).toString())!!
        assertEquals("Spotifeur-v1.3.0.apk", release.apk!!.name)
        assertEquals("ab".repeat(32), release.apk!!.sha256)
        assertEquals("## Added\n- Updates", release.notes)
    }

    @Test fun excludesDraftsPrereleasesAndInvalidVersions() {
        assertNull(GitHubRelease.parse(release().put("draft", true).toString()))
        assertNull(GitHubRelease.parse(release().put("prerelease", true).toString()))
        assertNull(GitHubRelease.parse(release().put("tag_name", "v1.3.0-rc1").toString()))
    }

    @Test fun neverDownloadsFromAnotherRepositoryOrUntrustedUrl() {
        listOf(
            "http://github.com/jean-voila/Spotifeur/releases/download/v1.3.0/app.apk",
            "https://github.com.evil.test/jean-voila/Spotifeur/releases/download/v1.3.0/app.apk",
            "https://github.com/attacker/Spotifeur/releases/download/v1.3.0/app.apk",
            "https://github.com/jean-voila/Spotifeur-other/releases/download/v1.3.0/app.apk",
            "https://github.com:444/jean-voila/Spotifeur/releases/download/v1.3.0/app.apk",
        ).forEach { url ->
            assertNull(url, GitHubRelease.parse(release(asset("app.apk").put("browser_download_url", url)).toString())!!.apk)
        }
    }

    @Test fun rejectsBadDigestsAndDoesNotGuessBetweenAlternativeBuilds() {
        assertNull(GitHubRelease.parse(release(asset("app.apk").put("digest", "sha256:abc")).toString())!!.apk)
        assertNull(GitHubRelease.parse(release(asset("arm.apk"), asset("x86.apk")).toString())!!.apk)
        assertEquals("Spotifeur-v1.3.0.apk", GitHubRelease.parse(release(asset("arm.apk"), asset("Spotifeur-v1.3.0.apk")).toString())!!.apk!!.name)
        assertNull(GitHubRelease.parse(release(asset("app.apk").put("state", "new")).toString())!!.apk)
    }

    @Test fun missingApkKeepsReleasePageAvailable() {
        val release = GitHubRelease.parse(release().put("html_url", "https://evil.test/").toString())!!
        assertNull(release.apk)
        assertEquals(GitHubRelease.LATEST_PAGE, release.page)
    }

    private fun release(vararg assets: JSONObject) = JSONObject().put("tag_name", "v1.3.0")
        .put("body", "## Added\n- Updates").put("draft", false).put("prerelease", false)
        .put("html_url", "https://github.com/jean-voila/Spotifeur/releases/tag/v1.3.0")
        .put("assets", JSONArray(assets.toList()))

    private fun asset(name: String) = JSONObject().put("name", name).put("size", 12345)
        .put("browser_download_url", "https://github.com/jean-voila/Spotifeur/releases/download/v1.3.0/$name")
}
