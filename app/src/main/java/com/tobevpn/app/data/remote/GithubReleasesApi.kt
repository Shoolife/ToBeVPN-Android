package com.tobevpn.app.data.remote

import com.tobevpn.app.data.remote.dto.GithubReleaseDto
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Read-only release API client used by the in-app updater to discover the
 * newest published APK without a custom backend endpoint.
 */
interface GithubReleasesApi {

    @Headers("Accept: application/vnd.github+json")
    @GET("repos/{owner}/{repo}/releases")
    suspend fun releases(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int = 100,
        @Query("page") page: Int = 1,
    ): List<GithubReleaseDto>
}
