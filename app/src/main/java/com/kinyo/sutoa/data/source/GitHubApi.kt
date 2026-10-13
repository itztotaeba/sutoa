package com.kinyo.sutoa.data.source

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path

/**
 * GitHub REST API v3 (unauthenticated works with a 60 req/h rate limit;
 * pass an optional token header to raise it).
 */
interface GitHubApi {

    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun latestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Header("Authorization") authorization: String? = null,
    ): GitHubReleaseDto

    @GET("repos/{owner}/{repo}")
    suspend fun getRepo(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Header("Authorization") authorization: String? = null,
    ): GitHubRepoDto
}

// ─── DTOs ────────────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class GitHubReleaseDto(
    @Json(name = "tag_name") val tagName: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "body") val body: String? = null,
    @Json(name = "html_url") val htmlUrl: String? = null,
    @Json(name = "published_at") val publishedAt: String? = null,
    @Json(name = "assets") val assets: List<GitHubAssetDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class GitHubAssetDto(
    @Json(name = "name") val name: String? = null,
    @Json(name = "browser_download_url") val browserDownloadUrl: String? = null,
    @Json(name = "size") val size: Long? = null,
    @Json(name = "content_type") val contentType: String? = null,
)

@JsonClass(generateAdapter = true)
data class GitHubRepoDto(
    @Json(name = "description") val description: String? = null,
    @Json(name = "html_url") val htmlUrl: String? = null,
    @Json(name = "full_name") val fullName: String? = null,
)
