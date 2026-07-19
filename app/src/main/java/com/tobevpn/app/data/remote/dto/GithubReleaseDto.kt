package com.tobevpn.app.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * Subset of the release API response we care about. Unauthenticated requests
 * are enough for the app's normal update-check cadence.
 */
data class GithubReleaseDto(
    @SerializedName("tag_name") val tagName: String,
    val name: String?,
    val body: String?,
    @SerializedName("html_url") val htmlUrl: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    // Gson does not apply Kotlin default values (no no-arg constructor here),
    // so an omitted "assets" key yields null despite the non-null type.
    val assets: List<GithubAssetDto>? = null,
)

data class GithubAssetDto(
    val name: String,
    @SerializedName("browser_download_url") val downloadUrl: String,
    val size: Long = 0L,
    @SerializedName("content_type") val contentType: String? = null,
)
