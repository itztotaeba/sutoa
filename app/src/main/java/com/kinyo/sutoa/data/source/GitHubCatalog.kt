package com.kinyo.sutoa.data.source

import android.content.Context
import android.util.Log
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** One tracked GitHub repository that publishes APKs via Releases. */
@JsonClass(generateAdapter = true)
data class GitHubCatalogEntry(
    @Json(name = "owner") val owner: String,
    @Json(name = "repo") val repo: String,
    @Json(name = "packageName") val packageName: String,
    @Json(name = "featured") val featured: Boolean = false,
) {
    val repoUrl: String get() = "https://github.com/$owner/$repo"
}

/**
 * Loads the list of tracked repos from the JSON asset `github_catalog.json`.
 * Adding a repo to the catalog requires no code change.
 */
@Singleton
class GitHubCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    moshi: Moshi,
) {

    private val _entries: List<GitHubCatalogEntry> by lazy { load() }

    val entries: List<GitHubCatalogEntry> get() = _entries

    fun find(packageName: String): GitHubCatalogEntry? =
        _entries.firstOrNull { it.packageName == packageName }

    fun featured(): List<GitHubCatalogEntry> = _entries.filter { it.featured }

    fun search(query: String): List<GitHubCatalogEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return _entries.filter {
            it.repo.lowercase().contains(q) ||
                it.owner.lowercase().contains(q) ||
                it.packageName.lowercase().contains(q)
        }
    }

    private fun load(): List<GitHubCatalogEntry> = runCatching {
        val json = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        val type = Types.newParameterizedType(List::class.java, GitHubCatalogEntry::class.java)
        moshi.adapter<List<GitHubCatalogEntry>>(type).fromJson(json).orEmpty()
    }.onFailure { Log.w(TAG, "failed to load $ASSET_NAME", it) }
        .getOrDefault(emptyList())

    companion object {
        const val ASSET_NAME = "github_catalog.json"
        private const val TAG = "GitHubCatalog"
    }
}
