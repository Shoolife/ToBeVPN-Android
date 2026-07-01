package com.tobevpn.app.data.repository

import com.tobevpn.app.data.remote.dto.GithubAssetDto
import com.tobevpn.app.data.remote.dto.GithubReleaseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateRepositoryTest {

    @Test
    fun selectNewestUpdateChoosesHighestSemverNotFirstRelease() {
        val update = selectNewestUpdate(
            releases = listOf(
                release("v1.0.45"),
                release("v1.0.47"),
                release("v1.0.46"),
            ),
            current = parseSemver("1.0.44")!!,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        )

        assertEquals("v1.0.47", update?.release?.tagName)
        assertEquals("ToBeVPN-1.0.47-arm64-v8a-test.apk", update?.apk?.name)
    }

    @Test
    fun selectNewestUpdateSkipsDraftPrereleaseAndReleaseWithoutApk() {
        val update = selectNewestUpdate(
            releases = listOf(
                release("v1.0.50", draft = true),
                release("v1.0.49", prerelease = true),
                release("v1.0.48", assets = listOf(asset("ToBeVPN-1.0.48-test.aab"))),
                release("v1.0.47"),
            ),
            current = parseSemver("1.0.46")!!,
            supportedAbis = listOf("arm64-v8a"),
        )

        assertEquals("v1.0.47", update?.release?.tagName)
    }

    @Test
    fun selectNewestUpdateReturnsNullWhenOnlyCurrentOrOlderVersionsExist() {
        val update = selectNewestUpdate(
            releases = listOf(
                release("v1.0.47"),
                release("v1.0.46"),
            ),
            current = parseSemver("1.0.47")!!,
            supportedAbis = listOf("arm64-v8a"),
        )

        assertNull(update)
    }

    @Test
    fun pickApkAssetUsesAbiPreferenceBeforeUniversalFallback() {
        val release = release(
            tagName = "v1.0.47",
            assets = listOf(
                asset("ToBeVPN-1.0.47-universal-test.apk"),
                asset("ToBeVPN-1.0.47-armeabi-v7a-test.apk"),
                asset("ToBeVPN-1.0.47-arm64-v8a-test.apk"),
            ),
        )

        val apk = pickApkAsset(
            release = release,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        )

        assertEquals("ToBeVPN-1.0.47-arm64-v8a-test.apk", apk?.name)
    }

    private fun release(
        tagName: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: List<GithubAssetDto> = listOf(asset("ToBeVPN-${tagName.removePrefix("v")}-arm64-v8a-test.apk")),
    ): GithubReleaseDto = GithubReleaseDto(
        tagName = tagName,
        name = tagName,
        body = "",
        htmlUrl = "https://github.com/Shoolife/ToBeVPN-Android/releases/tag/$tagName",
        draft = draft,
        prerelease = prerelease,
        assets = assets,
    )

    private fun asset(name: String): GithubAssetDto = GithubAssetDto(
        name = name,
        downloadUrl = "https://github.com/Shoolife/ToBeVPN-Android/releases/download/test/$name",
        size = 1L,
        contentType = "application/vnd.android.package-archive",
    )
}
