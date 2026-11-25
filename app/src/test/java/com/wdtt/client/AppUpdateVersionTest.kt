package com.wdtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateVersionTest {

    @Test
    fun testIsNewerVersion_simpleBumps() {
        // v1.5.9 -> v1.6.0 is newer
        assertTrue(isNewerVersion("v1.5.9", "v1.6.0"))
        // v1.5.9 -> v1.5.10 is newer
        assertTrue(isNewerVersion("v1.5.9", "v1.5.10"))
        // v1.5.9 -> v2.0.0 is newer
        assertTrue(isNewerVersion("v1.5.9", "v2.0.0"))
    }

    @Test
    fun testIsNewerVersion_equalOrOlder() {
        // Equal versions
        assertFalse(isNewerVersion("v1.6.0", "v1.6.0"))
        assertFalse(isNewerVersion("1.6.0", "v1.6.0"))
        // Older versions
        assertFalse(isNewerVersion("v1.6.0", "v1.5.9"))
        assertFalse(isNewerVersion("v2.0.0", "v1.9.9"))
    }

    @Test
    fun testIsNewerVersion_gitHashOrPrerelease() {
        // Stable release is newer than a dev/git build with the same version core
        assertTrue(isNewerVersion("v1.6.0-git-d4c6ad9", "v1.6.0"))
        // But dev build of a strictly higher version is newer than an older stable
        assertTrue(isNewerVersion("v1.5.9", "v1.6.0-git-d4c6ad9"))
        // Prerelease handling on same core version
        assertFalse(isNewerVersion("v1.6.0", "v1.6.0-alpha", includePrerelease = false))
        assertTrue(isNewerVersion("v1.6.0", "v1.6.0-alpha", includePrerelease = true))
    }

    @Test
    fun testParseApkUrlFromExpandedAssetsHtml_prefersUniversalThenArm64() {
        val html = """
            <div>
              <a href="/rasincaredy-cmd/moschata-vpn-android/releases/download/v1.6.8/moschata-vpn-1.6.8-armeabi-v7a.apk">armeabi</a>
              <a href="/rasincaredy-cmd/moschata-vpn-android/releases/download/v1.6.8/moschata-vpn-1.6.8-arm64-v8a.apk">arm64</a>
              <a href="/rasincaredy-cmd/moschata-vpn-android/releases/download/v1.6.8/moschata-vpn-1.6.8-universal.apk">universal</a>
            </div>
        """.trimIndent()
        assertEquals(
            "https://github.com/rasincaredy-cmd/moschata-vpn-android/releases/download/v1.6.8/moschata-vpn-1.6.8-universal.apk",
            parseApkUrlFromExpandedAssetsHtml(html)
        )

        val arm64OnlyHtml = """
            <a href="/rasincaredy-cmd/moschata-vpn-android/releases/download/v1.6.8/moschata-vpn-1.6.8-arm64-v8a.apk">arm64</a>
        """.trimIndent()
        assertEquals(
            "https://github.com/rasincaredy-cmd/moschata-vpn-android/releases/download/v1.6.8/moschata-vpn-1.6.8-arm64-v8a.apk",
            parseApkUrlFromExpandedAssetsHtml(arm64OnlyHtml)
        )
        assertNull(parseApkUrlFromExpandedAssetsHtml("<div>no apk links here</div>"))
    }
}
