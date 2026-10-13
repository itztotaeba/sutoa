package com.kinyo.sutoa.domain.repository

import android.util.Log
import com.kinyo.sutoa.data.cache.AppCacheDao
import com.kinyo.sutoa.data.cache.toAppItem
import com.kinyo.sutoa.data.cache.toEntity
import com.kinyo.sutoa.data.source.AppSource
import com.kinyo.sutoa.di.IoDispatcher
import com.kinyo.sutoa.domain.model.AppItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Result wrapper so the UI can distinguish success / partial / network failure. */
sealed interface RepoResult<out T> {
    data class Success<T>(val data: T, val fromCache: Boolean = false) : RepoResult<T>
    /** Remote failed but we could serve stale cache instead. */
    data class Stale<T>(val data: T) : RepoResult<T>
    data object NetworkError : RepoResult<Nothing>
}

/**
 * Aggregates every registered [AppSource] plugin into one catalog:
 *  - runs all sources in parallel
 *  - dedups by packageName (fdroid > github via [AppSource.priority])
 *  - cache-first via Room (fresh window = 24h)
 */
@Singleton
class AppRepository @Inject constructor(
    private val sources: Set<@JvmSuppressWildcards AppSource>,
    private val dao: AppCacheDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val orderedSources: List<AppSource> = sources.sortedBy { it.priority }

    /** Featured apps for Home. Cache-first with 24h TTL. */
    suspend fun featured(): RepoResult<List<AppItem>> = withContext(io) {
        val freshCutoff = System.currentTimeMillis() - TTL_MS
        if (dao.hasFreshData(freshCutoff)) {
            val cached = dao.getAll().map { it.toAppItem() }
            if (cached.isNotEmpty()) return@withContext RepoResult.Success(cached, fromCache = true)
        }

        val remote = fetchFromSources { it.featured() }
        when {
            remote.anySucceeded && remote.items.isNotEmpty() -> {
                dao.insertAll(remote.items.map { it.toEntity() })
                RepoResult.Success(remote.items)
            }
            remote.items.isNotEmpty() -> RepoResult.Stale(remote.items) // old cache rows
            remote.anySucceeded -> RepoResult.Success(emptyList())
            else -> RepoResult.NetworkError
        }
    }

    /** Search across all sources + local cache; merged & deduped. */
    suspend fun search(query: String, page: Int = 0): RepoResult<List<AppItem>> = withContext(io) {
        if (query.isBlank()) return@withContext RepoResult.Success(emptyList())

        val local = runCatching { dao.search(query).map { it.toAppItem() } }.getOrDefault(emptyList())
        val remote = fetchFromSources { it.search(query, page) }

        val merged = dedupe(local + remote.items)
        if (remote.anySucceeded && remote.items.isNotEmpty()) {
            dao.insertAll(remote.items.map { it.toEntity() })
        }

        when {
            merged.isNotEmpty() -> RepoResult.Success(merged)
            remote.anySucceeded -> RepoResult.Success(emptyList())
            else -> RepoResult.NetworkError
        }
    }

    /** Detail lookup: cache first, then ask each source in priority order. */
    suspend fun get(packageName: String): RepoResult<AppItem> = withContext(io) {
        val cached = dao.getByPkg(packageName)?.toAppItem()
        val isFresh = cached != null &&
            (System.currentTimeMillis() - (dao.getByPkg(packageName)?.cachedAt ?: 0L)) < TTL_MS

        if (isFresh && cached != null) return@withContext RepoResult.Success(cached, fromCache = true)

        var anySucceeded = false
        for (source in orderedSources) {
            val item = try {
                source.get(packageName)
            } catch (t: Throwable) {
                Log.w(TAG, "source ${source.id} get($packageName) threw", t)
                null
            }
            if (item != null) {
                anySucceeded = true
                dao.insert(item.toEntity())
                return@withContext RepoResult.Success(item)
            }
        }

        // No source returned data. Fall back to cache even if stale.
        return@withContext when {
            cached != null -> RepoResult.Stale(cached)
            anySucceeded -> RepoResult.NetworkError // shouldn't happen, defensive
            else -> RepoResult.NetworkError
        }
    }

    /** All cached apps — used by Updates screen as the known-catalog set. */
    suspend fun cachedApps(): List<AppItem> = withContext(io) {
        dao.getAll().map { it.toAppItem() }
    }

    /** Force refresh of a single package (e.g. user pull-to-refresh on detail). */
    suspend fun refresh(packageName: String): RepoResult<AppItem> = withContext(io) {
        var anySucceeded = false
        for (source in orderedSources) {
            val item = runCatching { source.get(packageName) }.getOrNull()
            if (item != null) {
                anySucceeded = true
                dao.insert(item.toEntity())
                return@withContext RepoResult.Success(item)
            }
        }
        val cached = dao.getByPkg(packageName)?.toAppItem()
        when {
            cached != null -> RepoResult.Stale(cached)
            anySucceeded -> RepoResult.NetworkError
            else -> RepoResult.NetworkError
        }
    }

    // ─── internals ────────────────────────────────────────────────────────────

    private class RemoteOutcome(val items: List<AppItem>, val anySucceeded: Boolean)

    /** Run [block] on every source in parallel; failures are swallowed per-source. */
    private suspend fun fetchFromSources(block: suspend (AppSource) -> List<AppItem>): RemoteOutcome =
        coroutineScopeSafe {
            val results = orderedSources.map { source ->
                async {
                    runCatching { block(source) }
                        .onFailure { Log.w(TAG, "source ${source.id} failed", it) }
                        .getOrDefault(null) to (runCatching { true }.isSuccess)
                }
            }.awaitAll()

            val flat = results.flatMap { it.first.orEmpty() }
            val succeeded = results.any { it.first != null }
            RemoteOutcome(dedupe(flat), succeeded)
        }

    private suspend fun coroutineScopeSafe(
        block: suspend kotlinx.coroutines.CoroutineScope.() -> RemoteOutcome,
    ): RemoteOutcome = kotlinx.coroutines.coroutineScope { block() }

    /** Dedup by packageName keeping the entry from the lowest-priority-number source. */
    private fun dedupe(items: List<AppItem>): List<AppItem> {
        val best = LinkedHashMap<String, AppItem>()
        val prio = orderedSources.associate { it.id to it.priority }
        for (item in items) {
            val existing = best[item.packageName]
            if (existing == null ||
                (prio[item.source] ?: Int.MAX_VALUE) < (prio[existing.source] ?: Int.MAX_VALUE)
            ) {
                best[item.packageName] = item
            }
        }
        return best.values.toList()
    }

    companion object {
        const val TTL_MS = 24L * 60L * 60L * 1000L // 24 hours
        private const val TAG = "AppRepository"
    }
}
