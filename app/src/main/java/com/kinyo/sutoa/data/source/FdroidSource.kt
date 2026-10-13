package com.kinyo.sutoa.data.source

import android.util.Log
import com.kinyo.sutoa.domain.model.AppItem
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plugin source for the official F-Droid repository.
 * All errors are swallowed (logged) and turned into empty results per [AppSource] contract.
 */
@Singleton
class FdroidSource @Inject constructor(
    private val api: FdroidApi,
) : AppSource {

    override val id: String = SOURCE_ID
    override suspend fun search(query: String, page: Int): List<AppItem> {
        if (query.isBlank()) return emptyList()
        return runCatching { api.search(query.trim(), page).packages.map(::toAppItem) }
            .onFailure { Log.w(TAG, "search failed", it) }
            .getOrDefault(emptyList())
    }

    /**
     * Featured = most recently updated packages from the full index.
     * NOTE: /api/v1/index is large; the Room cache (24h TTL) keeps this cheap.
     */
    override suspend fun featured(): List<AppItem> {
        return runCatching {
            api.getIndex().packages.values.flatten()
                .filter { it.apkName != null && !it.packageName.isNullOrBlank() }
                .sortedByDescending { parseTime(it.updated) }
                .take(FEATURED_LIMIT)
                .map(::toAppItem)
        }.onFailure { Log.w(TAG, "featured failed", it) }
            .getOrDefault(emptyList())
    }

    override suspend fun get(packageName: String): AppItem? {
        val pkg = runCatching { api.search(packageName).packages.firstOrNull { it.packageName == packageName } }
            .onFailure { Log.w(TAG, "get($packageName) failed", it) }
            .getOrNull() ?: return null

        // Enrich with exact APK metadata (sha256/size) from /packages/{pkg} when available.
        val apk = runCatching {
            api.getPackages(packageName).apklist.values
                .filter { it.apkName?.endsWith(".apk") == true }
                .maxByOrNull { it.versionCode ?: 0L }
        }.getOrNull()

        return toAppItem(pkg).copy(
            versionCode = apk?.versionCode ?: pkg.versionCode ?: 0L,
            versionName = apk?.versionName ?: pkg.versionName.orEmpty(),
            sha256 = apk?.hash ?: pkg.hash,
            sizeBytes = apk?.size ?: pkg.size ?: 0L,
            minSdk = (apk?.minSdkVersion ?: pkg.minSdkVersion)?.toIntOrNull(),
            apkUrl = apk?.apkName?.let { REPO_BASE + it } ?: pkg.apkName?.let { REPO_BASE + it }.orEmpty(),
        )
    }

    private fun toAppItem(p: FdroidPackageDto): AppItem = AppItem(
        packageName = p.packageName,
        name = p.name ?: p.packageName.substringAfterLast('.'),
        summary = p.summary.orEmpty(),
        description = cleanHtml(p.description),
        iconUrl = p.icon?.let { ICON_BASE + it },
        screenshots = p.screenshots.mapNotNull { s ->
            val hash = s.base64Sha256 ?: s.smallBase64Sha256 ?: return@mapNotNull null
            "$SCREENSHOT_BASE${p.packageName}/$hash.png"
        },
        versionName = p.versionName.orEmpty(),
        versionCode = p.versionCode ?: 0L,
        apkUrl = p.apkName?.let { REPO_BASE + it }.orEmpty(),
        sha256 = p.hash,
        sizeBytes = p.size ?: 0L,
        minSdk = p.minSdkVersion?.toIntOrNull(),
        source = SOURCE_ID,
        sourceUrl = p.webUrl ?: "https://f-droid.org/packages/${p.packageName}/",
        lastUpdated = parseTime(p.updated),
    )

    /** F-Droid descriptions are HTML-ish markdown; strip tags for a readable MVP. */
    private fun cleanHtml(raw: String?): String = raw
        ?.replace(Regex("<[^>]*>"), "")
        ?.replace("&amp;", "&")
        ?.replace("&lt;", "<")
        ?.replace("&gt;", ">")
        ?.replace("&quot;", "\"")
        ?.trim()
        .orEmpty()

    private fun parseTime(iso: String?): Long = runCatching {
        DateTimeFormatter.ISO_DATE_TIME.parse(iso!!, Instant::from).toEpochMilli()
    }.getOrDefault(0L)

    companion object {
        const val SOURCE_ID = "fdroid"
        const val BASE_URL = "https://f-droid.org/api/v1/"
        private const val TAG = "FdroidSource"
        private const val REPO_BASE = "https://f-droid.org/repo/"
        private const val ICON_BASE = "https://f-droid.org/icons/high320/"
        private const val SCREENSHOT_BASE = "https://f-droid.org/screenshot/240dpi/"
        private const val FEATURED_LIMIT = 30
    }
}
