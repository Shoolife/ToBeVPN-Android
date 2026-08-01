package com.tobevpn.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.tobevpn.app.BuildConfig
import com.tobevpn.app.data.remote.GithubReleasesApi
import com.tobevpn.app.data.remote.dto.GithubAssetDto
import com.tobevpn.app.data.remote.dto.GithubReleaseDto
import com.tobevpn.app.util.SafeDiagnostics
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates the "is there a newer build?" check against the release API.
 *
 * The check is best-effort: any network/API error returns
 * [UpdateCheckResult.UpToDate] so the UI never blocks startup because of an
 * update probe. Failed probes are not cached.
 *
 * Caching: unauthenticated release API calls are rate-limited per IP. With
 * many users behind one carrier-grade NAT we'd burn that budget on cold-launch
 * spam. We cache only the "up to date" result for [CACHE_TTL_MS] (7 days).
 * Available updates are always revalidated so a user never downloads
 * v1.0.45 first when v1.0.47 is already published.
 */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val api: GithubReleasesApi,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Returns the last-known result if it was fetched within the cache TTL.
     * Pass `force = true` to skip the cache (the "Check now" button in
     * Settings).
     */
    suspend fun checkForUpdate(force: Boolean = false): UpdateCheckResult {
        if (!force) {
            val cached = readCached()
            if (cached != null) return cached
        }
        return runCatching {
            val fresh = fetchFromNetwork()
            writeCached(fresh)
            fresh
        }.getOrElse { e ->
            SafeDiagnostics.warn(
                TAG,
                "Update check failed: ${SafeDiagnostics.failureCategory(e)}",
            )
            UpdateCheckResult.UpToDate
        }
    }

    private suspend fun fetchFromNetwork(): UpdateCheckResult {
        val current = parseSemver(BuildConfig.VERSION_NAME) ?: return UpdateCheckResult.UpToDate
        val releases = fetchReleasePages()
        val update = selectNewestUpdate(
            releases = releases,
            current = current,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
        ) ?: return UpdateCheckResult.UpToDate
        val release = update.release
        val apk = update.apk

        return UpdateCheckResult.Available(
            versionName = release.tagName.removePrefix("v"),
            releaseNotes = release.body.orEmpty(),
            releasePageUrl = release.htmlUrl,
            apkUrl = apk.downloadUrl,
            apkSizeBytes = apk.size,
            apkFileName = apk.name,
        )
    }

    private suspend fun fetchReleasePages(): List<GithubReleaseDto> {
        val releases = mutableListOf<GithubReleaseDto>()
        for (page in 1..MAX_RELEASE_PAGES) {
            val pageItems = api.releases(
                owner = GITHUB_OWNER,
                repo = GITHUB_REPO,
                perPage = RELEASES_PER_PAGE,
                page = page,
            )
            if (pageItems.isEmpty()) break
            releases += pageItems
            if (pageItems.size < RELEASES_PER_PAGE) break
        }
        return releases
    }

    // ── Cache ───────────────────────────────────────────────────────────

    private fun readCached(): UpdateCheckResult? {
        val ts = prefs.getLong(KEY_CACHED_AT, 0L)
        if (ts == 0L) return null
        if (System.currentTimeMillis() - ts > CACHE_TTL_MS) return null
        return when (prefs.getString(KEY_CACHED_KIND, null)) {
            "uptodate" -> UpdateCheckResult.UpToDate
            else -> null
        }
    }

    private fun writeCached(result: UpdateCheckResult) {
        val editor = prefs.edit()
            .putLong(KEY_CACHED_AT, System.currentTimeMillis())
        when (result) {
            UpdateCheckResult.UpToDate -> {
                editor.putString(KEY_CACHED_KIND, "uptodate")
                    .remove(KEY_VERSION)
                    .remove(KEY_NOTES)
                    .remove(KEY_PAGE_URL)
                    .remove(KEY_APK_URL)
                    .remove(KEY_APK_SIZE)
                    .remove(KEY_APK_NAME)
            }
            is UpdateCheckResult.Available -> {
                editor.remove(KEY_CACHED_KIND)
                    .remove(KEY_VERSION)
                    .remove(KEY_NOTES)
                    .remove(KEY_PAGE_URL)
                    .remove(KEY_APK_URL)
                    .remove(KEY_APK_SIZE)
                    .remove(KEY_APK_NAME)
            }
        }
        editor.apply()
    }

    private companion object {
        const val TAG = "UpdateRepository"
        const val GITHUB_OWNER = "Shoolife"
        const val GITHUB_REPO = "ToBeVPN-Android"

        const val PREFS_NAME = "tobevpn_update_cache"
        const val KEY_CACHED_AT = "cached_at"
        const val KEY_CACHED_KIND = "cached_kind"
        const val KEY_VERSION = "version"
        const val KEY_NOTES = "notes"
        const val KEY_PAGE_URL = "page_url"
        const val KEY_APK_URL = "apk_url"
        const val KEY_APK_SIZE = "apk_size"
        const val KEY_APK_NAME = "apk_name"

        const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000  // 7 days
        const val RELEASES_PER_PAGE = 100
        const val MAX_RELEASE_PAGES = 5
    }
}

internal data class UpdateReleaseCandidate(
    val release: GithubReleaseDto,
    val apk: GithubAssetDto,
    val version: IntArray,
)

sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class Available(
        val versionName: String,
        val releaseNotes: String,
        val releasePageUrl: String,
        val apkUrl: String,
        val apkSizeBytes: Long,
        val apkFileName: String,
    ) : UpdateCheckResult
}

/** Parses a release tag like `v1.2.3` / `1.2.3` / `v1.2.3-rc1` into [major, minor, patch]. */
internal fun parseSemver(raw: String): IntArray? {
    val cleaned = raw.removePrefix("v").substringBefore('-').substringBefore('+')
    val parts = cleaned.split('.')
    if (parts.size !in 1..3) return null
    val nums = IntArray(3)
    for (i in 0 until 3) {
        val piece = parts.getOrNull(i) ?: "0"
        nums[i] = piece.toIntOrNull() ?: return null
    }
    return nums
}

internal fun compareSemver(a: IntArray, b: IntArray): Int {
    for (i in 0 until 3) {
        val diff = a[i] - b[i]
        if (diff != 0) return diff
    }
    return 0
}

internal fun selectNewestUpdate(
    releases: List<GithubReleaseDto>,
    current: IntArray,
    supportedAbis: List<String>,
): UpdateReleaseCandidate? {
    return releases
        .asSequence()
        .filterNot { it.draft || it.prerelease }
        .mapNotNull { release ->
            val version = parseSemver(release.tagName) ?: return@mapNotNull null
            if (compareSemver(version, current) <= 0) return@mapNotNull null
            val apk = pickApkAsset(release, supportedAbis) ?: return@mapNotNull null
            UpdateReleaseCandidate(release = release, apk = apk, version = version)
        }
        .maxWithOrNull { left, right -> compareSemver(left.version, right.version) }
}

internal fun pickApkAsset(
    release: GithubReleaseDto,
    supportedAbis: List<String>,
): GithubAssetDto? {
    val apks = release.assets.orEmpty().filter { it.name.endsWith(".apk", ignoreCase = true) }
    if (apks.isEmpty()) return null

    // 1. Match by SUPPORTED_ABIS in declared preference order.
    for (abi in supportedAbis) {
        val token = "-$abi-"
        apks.firstOrNull { it.name.contains(token, ignoreCase = true) }?.let { return it }
    }
    // 2. Universal fallback for unusual devices.
    apks.firstOrNull { it.name.contains("-universal-", ignoreCase = true) }?.let { return it }
    // 3. Last resort: any APK in the release.
    return apks.first()
}
