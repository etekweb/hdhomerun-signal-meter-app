package net.etekweb.hdhrmeter.data

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatesTest {
    @Test
    fun releaseVersionsMatchAndroidVersionCodeOrdering() {
        assertEquals(1_010_004L, taggedVersionCode("v1.10.3"))
        assertEquals(taggedVersionCode("1.10"), taggedVersionCode("v1.10.0"))
        assertTrue(taggedVersionCode("1.10")!! > taggedVersionCode("1.9.999")!!)
        assertEquals(2_100_000_000L, taggedVersionCode("2099.999.999"))
        listOf("0.9", "1.01", "1.1000", "1.2-beta", "2100.0").forEach {
            assertNull(taggedVersionCode(it))
        }
    }

    @Test
    fun latestReleaseWithMatchingApkIsOfferedOnlyToOlderInstalls() {
        val release = releaseWithApk()
        val update = parseAppUpdate(release, 1)!!
        assertEquals("1.10.3", update.version)
        assertEquals(220_000_000L, update.size)
        assertTrue(update.downloadUrl.endsWith("/v1.10.3/app-release.apk"))
        assertNull(parseAppUpdate(release, 1_010_004))
        assertNull(parseAppUpdate(release, 1_010_005))
        assertNull(parseAppUpdate(release.put("prerelease", true), 1))
    }

    @Test(expected = IOException::class)
    fun anApkWithoutAGitHubDigestIsRejected() {
        parseAppUpdate(releaseWithApk(digest = ""), 1)
    }

    private fun releaseWithApk(digest: String = "sha256:" + "a".repeat(64)): JSONObject =
        JSONObject().put("tag_name", "v1.10.3").put(
            "assets",
            JSONArray().put(
                JSONObject()
                    .put("name", "app-release.apk")
                    .put("state", "uploaded")
                    .put("size", 220_000_000)
                    .put("digest", digest),
            ),
        )
}
