package com.fife.sa05

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateRepositoryTest {
    @Test
    fun parsesLatestReleaseWithMetadataAndApkAsset() {
        val release = AppUpdateRepository.parseLatestRelease(
            """
                {
                  "tag_name": "v1.2.3",
                  "name": "SA05 1.2.3",
                  "body": "versionName: 1.2.3\nversionCode: 12\nasset: sa05-1.2.3-release.apk\n\n- Fixes\n- New features",
                  "html_url": "https://github.com/i3sey/sa05/releases/tag/v1.2.3",
                  "published_at": "2026-01-01T00:00:00Z",
                  "assets": [{
                    "name": "sa05-1.2.3-release.apk",
                    "browser_download_url": "https://github.com/i3sey/sa05/releases/download/v1.2.3/sa05-1.2.3-release.apk",
                    "content_type": "application/vnd.android.package-archive"
                  }]
                }
            """.trimIndent()
        )

        assertEquals("v1.2.3", release.tagName)
        assertEquals("1.2.3", release.versionName)
        assertEquals(12, release.versionCode)
        assertEquals("SA05 1.2.3", release.name)
        assertEquals("sa05-1.2.3-release.apk", release.assetName)
        assertTrue(release.notes.contains("Fixes"))
        assertEquals(
            "https://github.com/i3sey/sa05/releases/download/v1.2.3/sa05-1.2.3-release.apk",
            release.assetUrl
        )
    }

    @Test
    fun fallsBackToTagNameWhenVersionNameIsMissing() {
        val release = AppUpdateRepository.parseLatestRelease(
            """
                {
                  "tag_name": "v1.2.4",
                  "name": "",
                  "body": "versionCode: 13\n\nChangelog",
                  "html_url": "https://github.com/i3sey/sa05/releases/tag/v1.2.4",
                  "published_at": "2026-01-02T00:00:00Z",
                  "assets": [{
                    "name": "sa05-1.2.4-release.apk",
                    "browser_download_url": "https://example.com/sa05.apk",
                    "content_type": "application/vnd.android.package-archive"
                  }]
                }
            """.trimIndent()
        )

        assertEquals("1.2.4", release.versionName)
        assertTrue(release.notes.contains("Changelog"))
    }

    @Test
    fun acceptsReleaseWithoutVersionCodeAndUsesTagComparison() {
        val release = AppUpdateRepository.parseLatestRelease(
            """
                {
                  "tag_name": "v1.2.5",
                  "name": "SA05 1.2.5",
                  "body": "- Fix existing release notes without metadata",
                  "html_url": "https://github.com/i3sey/sa05/releases/tag/v1.2.5",
                  "published_at": "2026-01-03T00:00:00Z",
                  "assets": [{
                    "name": "sa05-1.2.5-release.apk",
                    "browser_download_url": "https://example.com/sa05.apk",
                    "content_type": "application/vnd.android.package-archive"
                  }]
                }
            """.trimIndent()
        )

        assertEquals("1.2.5", release.versionName)
        assertEquals(null, release.versionCode)
        assertTrue(AppUpdateRepository.isNewer(99, "1.2.4", release))
        assertFalse(AppUpdateRepository.isNewer(99, "1.2.5", release))
    }

    @Test
    fun parsesLooseMarkdownMetadata() {
        val release = AppUpdateRepository.parseLatestRelease(
            """
                {
                  "tag_name": "v1.2.6",
                  "name": "SA05 1.2.6",
                  "body": "- **versionName**: 1.2.6\n- **versionCode**: 16\n\nChanges",
                  "html_url": "https://github.com/i3sey/sa05/releases/tag/v1.2.6",
                  "published_at": "2026-01-04T00:00:00Z",
                  "assets": [{
                    "name": "sa05-1.2.6-release.apk",
                    "browser_download_url": "https://example.com/sa05.apk",
                    "content_type": "application/vnd.android.package-archive"
                  }]
                }
            """.trimIndent()
        )

        assertEquals("1.2.6", release.versionName)
        assertEquals(16, release.versionCode)
        assertEquals("Changes", release.notes)
    }

    @Test
    fun prefersReleaseApkOverDebugApk() {
        val release = AppUpdateRepository.parseLatestRelease(
            """
                {
                  "tag_name": "v1.2.7",
                  "name": "SA05 1.2.7",
                  "body": "versionCode: 17",
                  "html_url": "https://github.com/i3sey/sa05/releases/tag/v1.2.7",
                  "published_at": "2026-01-05T00:00:00Z",
                  "assets": [
                    {
                      "name": "sa05-1.2.7-debug.apk",
                      "browser_download_url": "https://example.com/debug.apk",
                      "content_type": "application/vnd.android.package-archive"
                    },
                    {
                      "name": "sa05-1.2.7-release.apk",
                      "browser_download_url": "https://example.com/release.apk",
                      "content_type": "application/vnd.android.package-archive"
                    }
                  ]
                }
            """.trimIndent()
        )

        assertEquals("sa05-1.2.7-release.apk", release.assetName)
        assertEquals("https://example.com/release.apk", release.assetUrl)
    }


    @Test
    fun versionComparisonUsesVersionCode() {
        val release = AppRelease(
            tagName = "v1.2.3",
            versionName = "1.2.3",
            versionCode = 42,
            name = "SA05 1.2.3",
            notes = "",
            assetName = "sa05.apk",
            assetUrl = "https://example.com/sa05.apk",
            htmlUrl = "https://example.com",
            publishedAt = "2026-01-01T00:00:00Z"
        )

        assertTrue(AppUpdateRepository.isNewer(41, "1.2.2", release))
        assertFalse(AppUpdateRepository.isNewer(42, "1.2.3", release))
    }
}
