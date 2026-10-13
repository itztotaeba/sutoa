package com.kinyo.sutoa.data.source

import android.util.Log
import com.kinyo.sutoa.BuildConfig
import com.kinyo.sutoa.domain.model.AppItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plugin source backed by GitHub Releases of the repos listed in [GitHubCatalog].
 * Only assets whose name ends with ".apk" are considered installable.
 */
@Singleton
class GitHubSource @Inject constructor(
    private val api: GitHubApi,
    private val catalog: GitHubCatalog,
) : AppSource {

    override val id: String = SOURCE_ID
    override val priority: Int = 1 // fdroid wins dedup

    private val authHeader: String? =
        BuildConfig.GITHUB_TOKEN.takeIf { it.isNotBlank() }?.let { "Bearer $it" }

    override suspend fun search(query: String, page: Int): List<AppItem> =
        resolveAll(catalog.search(query))

    override suspend fun featured(): List<AppItem> =
        resolveAll(catalog.featured())

    override suspend fun get(packageName: String): AppItem? {
        val entry = catalog.find(packageName) ?: return null
        return resolve(entry).first
    }

    /** Resolve several repos concurrently; failed repos are skipped silently. */
    private suspend fun resolveAll(entries: List<GitHubCatalogEntry>): List<AppItem> =
        coroutineScope {
            entries.map { async { resolve(it).first } }
                .awaitAll()
                .filterNotNull()
        }

    /** Returns (detail item, release changelog) or (null, null) on failure. */
    private suspend fun resolve(entry: GitHubCatalogEntry): Pair<AppItem?, String?> {
        val release = runCatching { api.latestRelease(entry.owner, entry.repo, authHeader) }
            .onFailure { Log.w(TAG, "release ${entry.owner}/${entry.repo} failed", it) }
            .getOrNull() ?: return null to null

        // Prefer universal/unsigned APK assets; filter strictly to *.apk.
        val apk = release.assets
            .filter { it.name?.endsWith(".apk", ignoreCase = true) == true && it.browserDownloadUrl != null }
            .sortedByDescending { it.size ?: 0L } // biggest = usually universal build
            .firstOrNull()
            ?: return null to null

        val repo = runCatching { api.getRepo(entry.owner, entry.repo, authHeader) }.getOrNull()
        val versionName = (release.tagName ?: release.name ?: "").removePrefix("v").trim()

        val item = AppItem(
            packageName = entry.packageName,
            name = entry.repo.replace('-', ' ').replace('_', ' '),
            summary = repo?.description.orEmpty(),
            description = release.body.orEmpty().ifBlank { repo?.description.orEmpty() },
            iconUrl = null, // GitHub releases don't ship icons; UI falls back to placeholder
            screenshots = emptyList(),
            versionName = versionName,
            versionCode = parseVersionCode(versionName),
            apkUrl = apk.browserDownloadUrl!!,
            sha256 = extractSha256(release.body, apk.name), // optional checksum in notes
            sizeBytes = apk.size ?: 0L,
            minSdk = null,
            source = SOURCE_ID,
            sourceUrl = release.htmlUrl ?: entry.repoUrl,
            lastUpdated = parseTime(release.publishedAt),
        )
        return item to release.body
    }

    /**
     * GitHub has no versionCode, so derive a stable comparable Long from the tag:
     * "1.2.3-build4" -> 1_002_003_004 style packing (each component < 1000).
     */
    private fun parseVersionCode(versionName: String): Long {
        val parts = versionName.split('.', '-', '_').mapNotNull { it.filter(Char::isDigit).toIntOrNull() }
        if (parts.isEmpty()) return 0L
        var code = 0L
        for (p in parts.take(4)) code = code * 1000 + p.coerceIn(0, 999)
        return code
    }

    /** Look for "<sha256>  app.apk" / "app.apk: <sha256>" style lines in the changelog. */
    private fun extractSha256(body: String?, assetName: String?): String? {
        if (body.isNullOrBlank() || assetName.isNullOrBlank()) return null
        Regex("[0-9a-fA-F]{64}").findAll(body).forEach { m ->
            val idx = m.range.first
            val ctx = body.substring(maxOf(0, idx - 80), minOf(body.length, m.range.last + 80))
            if (ctx.contains(assetName)) return m.value.lowercase()
        }
        return null
    }

    private fun parseTime(iso: String?): Long = runCatching {
        DateTimeFormatter.ISO_DATE_TIME.parse(iso!!, Instant::from).toEpochMilli()
    }.getOrDefault(0L)

    companion object {
        const val SOURCE_ID = "github"
        const val BASE_URL = "https://api.github.com/"
        private const val TAG = "GitHubSource"
    }
}
