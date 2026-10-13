package com.kinyo.sutoa.data.source

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * F-Droid open REST API (https://f-droid.org/docs/The_API/).
 *  - /api/v1/index          -> full package index (featured = recently updated)
 *  - /api/v1/search         -> text search, paginated with ?page=N
 *  - /api/v1/packages/{pkg} -> all APKs of one package
 */
interface FdroidApi {

    @GET("index")
    suspend fun getIndex(): FdroidIndexDto

    @GET("search")
    suspend fun search(
        @Query("query") query: String,
        @Query("page") page: Int = 0,
    ): FdroidSearchDto

    @GET("packages/{packageName}")
    suspend fun getPackages(@Path("packageName") packageName: String): FdroidPackagesDto
}

// ─── DTOs ────────────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class FdroidIndexDto(
    @Json(name = "repo") val repo: FdroidRepoDto? = null,
    @Json(name = "packages") val packages: Map<String, List<FdroidPackageDto>> = emptyMap(),
)

@JsonClass(generateAdapter = true)
data class FdroidRepoDto(
    @Json(name = "name") val name: String? = null,
    @Json(name = "address") val address: String? = null,
)

@JsonClass(generateAdapter = true)
data class FdroidSearchDto(
    @Json(name = "packages") val packages: List<FdroidPackageDto> = emptyList(),
    @Json(name = "paging") val paging: FdroidPagingDto? = null,
)

@JsonClass(generateAdapter = true)
data class FdroidPagingDto(
    @Json(name = "count") val count: Int? = null,
    @Json(name = "maxPage") val maxPage: Int? = null,
)

@JsonClass(generateAdapter = true)
data class FdroidPackagesDto(
    @Json(name = "apklist") val apklist: Map<String, FdroidApkDto> = emptyMap(),
)

@JsonClass(generateAdapter = true)
data class FdroidPackageDto(
    @Json(name = "packageName") val packageName: String,
    @Json(name = "name") val name: String? = null,
    @Json(name = "summary") val summary: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "icon") val icon: String? = null,
    @Json(name = "screenshots") val screenshots: List<FdroidScreenshotDto> = emptyList(),
    @Json(name = "versionName") val versionName: String? = null,
    @Json(name = "versionCode") val versionCode: Long? = null,
    @Json(name = "apkName") val apkName: String? = null,
    @Json(name = "hash") val hash: String? = null,
    @Json(name = "size") val size: Long? = null,
    @Json(name = "minSdkVersion") val minSdkVersion: String? = null,
    @Json(name = "webUrl") val webUrl: String? = null,
    @Json(name = "sourceRepoName") val sourceRepoName: String? = null,
    @Json(name = "updated") val updated: String? = null,
)

@JsonClass(generateAdapter = true)
data class FdroidScreenshotDto(
    @Json(name = "base64Sha256") val base64Sha256: String? = null,
    @Json(name = "smallBase64Sha256") val smallBase64Sha256: String? = null,
    @Json(name = "fullName") val fullName: String? = null,
)

@JsonClass(generateAdapter = true)
data class FdroidApkDto(
    @Json(name = "versionName") val versionName: String? = null,
    @Json(name = "versionCode") val versionCode: Long? = null,
    @Json(name = "apkName") val apkName: String? = null,
    @Json(name = "hash") val hash: String? = null,
    @Json(name = "size") val size: Long? = null,
    @Json(name = "minSdkVersion") val minSdkVersion: String? = null,
)
