package com.tobevpn.app.data.remote

import com.tobevpn.app.data.remote.dto.GithubReleaseDto
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path

/**
 * Read-only client for release assets API. Used by the in-app updater to
 * discover the latest published APK without a custom backend endpoint.
 *
 * The Accept header is the GitHub-recommended pinning of API v3 — without it
 * GitHub may switch to a newer schema in the future and break our parser.
 */
interface GithubReleasesApi {

    @Headers("Accept: application/vnd.github+json")
    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun latestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
    ): GithubReleaseDto
}
