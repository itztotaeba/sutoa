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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result wrapper so the UI can distinguish:
 *  - fresh remote data,
 *  - served-from-cache data (fresh or stale fallback),
 *  - a full network failure with no data at all.
 */
sealed interface RepoResult<out T> {
    /** Success; [fromCache] = true when the data came from Room instead of the network. */
    data class Success<T>(val data: T, val fromCache: Boolean = false) : RepoResult<T>

    /** Remote failed but we could serve stale cache instead — UI may show an offline hint. */
    data class Stale<T>(val data: T) : RepoResult<T>

    /** Every source failed AND there is nothing in cache. */
    data object NetworkError : RepoResult<Nothing>
}

/**
 * The single entry point the UI talks to. Aggregates every registered [AppSource]
 * plugin into one unified catalog:
 *
 *  - all sources are queried **in parallel** via coroutineScope + async;
 *  - each source call is wrapped in runCatching so one broken plugin can never
 *    sink the others (it contributes an empty list and the error is logged);
 *  - results are merged and deduped by packageName with priority fdroid > github
 *    (the injected Set<AppSource> is sorted by [AppSource.priority]);
 *  - cache-first strategy backed by Room: fresh (< 24 h) cache short-circuits the
 *    network; stale/missing cache triggers a remote fetch that refreshes the cache;
 *    on network failure the app still serves whatever cache exists.
 *
 * Adding a new source (GitLab, Codeberg, custom repo) only requires implementing
 * [AppSource] and registering it with @Provides @IntoSet in di/AppModule.kt —
 * this repository, the ViewModels and the UI stay untouched.
 */
@Singleton
class AppRepository @Inject constructor(
    private val sources: Set<@JvmSuppressWildcards AppSource>,
    private val dao: AppCacheDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Deterministic order for dedup priority: lower [AppSource.priority] wins. */
    private val orderedSources: List<AppSource> = sources.sortedBy { it.priority }

    // ─── public API ───────────────────────────────────────────────────────────

    /**
     * Featured apps for the Home screen.
     * Cache-first: if the catalog cache is younger than TTL_MS we return it without
     * touching the network. Otherwise every source is fetched in parallel, the
     * merged result is written to Room, and returned. If all sources fail we fall
     * back to stale cache (if any) instead of showing an error.
     */
    suspend fun featured(): RepoResult<List<AppItem>> = withContext(io) {
        val freshCutoff = System.currentTimeMillis() - TTL_MS
        val cached = runCatching { dao.getAll().map { it.toAppItem() } }.getOrDefault(emptyList())

        if (cached.isNotEmpty() && dao.hasFreshData(freshCutoff)) {
            Log.d(TAG, "featured: serving fresh cache (${cached.size} items)")
            return@withContext RepoResult.Success(cached, fromCache = true)
        }

        val outcome = fetchFromSources { source -> source.featured() }
        when {
            outcome.anySucceeded -> {
                val merged = dedupe(outcome.items)
                runCatching { dao.insertAll(merged.map { it.toEntity() }) }
                    .onFailure { Log.w(TAG, "failed to persist featured cache", it) }
                RepoResult.Success(merged)
            }
            // All remote sources failed -> serve stale cache if we have anything.
            cached.isNotEmpty() -> {
                Log.w(TAG, "featured: all sources failed, serving stale cache")
                RepoResult.Stale(cached)
            }
            else -> RepoResult.NetworkError
        }
    }

    /**
     * Search across all sources plus the local cache, merged & deduped.
     * Local matches are always included (offline-friendly); remote results refresh
     * the cache when at least one source succeeded. On total network failure the
     * cached results are returned instead of an error whenever possible.
     */
    suspend fun search(query: String, page: Int = 0): RepoResult<List<AppItem>> = withContext(io) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext RepoResult.Success(emptyList())

        val local = runCatching { dao.search(q).map { it.toAppItem() } }
            .onFailure { Log.w(TAG, "local cache search failed", it) }
            .getOrDefault(emptyList())

        val outcome = fetchFromSources { source -> source.search(q, page) }

        if (!outcome.anySucceeded) {
            // Network error -> return whatever cache gave us, otherwise report failure.
            return@withContext when {
                local.isNotEmpty() -> {
                    Log.w(TAG, "search(\"$q\"): all sources failed, serving cache only")
                    RepoResult.Success(local, fromCache = true)
                }
                else -> RepoResult.NetworkError
            }
        }

        val merged = dedupe(local + outcome.items)
        if (outcome.items.isNotEmpty()) {
            runCatching { dao.insertAll(outcome.items.map { it.toEntity() }) }
                .onFailure { Log.w(TAG, "failed to persist search cache", it) }
        }
        RepoResult.Success(merged)
    }

    /**
     * Single-app detail lookup. Cache-first with a 24 h freshness window keyed per
     * package; on a miss/stale entry every source is asked in parallel and the
     * highest-priority hit is cached and returned. Falls back to stale cache when
     * the whole network layer fails.
     */
    suspend fun get(packageName: String): RepoResult<AppItem?> = withContext(io) {
        val cachedEntity = runCatching { dao.getByPkg(packageName) }
            .onFailure { Log.w(TAG, "cache read failed for $packageName", it) }
            .getOrNull()
        val cached = cachedEntity?.toAppItem()
        val isFresh = cachedEntity != null &&
            (System.currentTimeMillis() - cachedEntity.cachedAt) < TTL_MS

        if (isFresh && cached != null) {
            return@withContext RepoResult.Success(cached, fromCache = true)
        }

        val outcome = fetchFromSources { source -> listOfNotNull(source.get(packageName)) }
        val hit = dedupe(outcome.items).firstOrNull()

        when {
            hit != null -> {
                runCatching { dao.insert(hit.toEntity()) }
                    .onFailure { Log.w(TAG, "failed to persist detail cache", it) }
                RepoResult.Success(hit)
            }
            // No source returned the package. Serve stale cache rather than an error.
            cached != null -> {
                Log.w(TAG, "get($packageName): sources returned nothing, serving stale cache")
                RepoResult.Stale(cached)
            }
            outcome.anySucceeded -> RepoResult.Success(null) // known-not-found
            else -> RepoResult.NetworkError
        }
    }

    /** All cached apps — used by the Updates screen / worker as the known-catalog set. */
    suspend fun cachedApps(): List<AppItem> = withContext(io) {
        dao.getAll().map { it.toAppItem() }
    }

    /** Force-refresh one package (e.g. user tapped "refresh" on Detail). */
    suspend fun refresh(packageName: String): RepoResult<AppItem?> = withContext(io) {
        val outcome = fetchFromSources { source -> listOfNotNull(source.get(packageName)) }
        val hit = dedupe(outcome.items).firstOrNull()
        when {
            hit != null -> {
                runCatching { dao.insert(hit.toEntity()) }
                    .onFailure { Log.w(TAG, "failed to persist refreshed item", it) }
                RepoResult.Success(hit)
            }
            else -> {
                val cached = dao.getByPkg(packageName)?.toAppItem()
                when {
                    cached != null -> RepoResult.Stale(cached)
                    outcome.anySucceeded -> RepoResult.Success(null)
                    else -> RepoResult.NetworkError
                }
            }
        }
    }

    // ─── internals ────────────────────────────────────────────────────────────

    private class RemoteOutcome(val items: List<AppItem>, val anySucceeded: Boolean)

    /**
     * Runs [block] against every registered source IN PARALLEL using
     * coroutineScope + async. Each call is isolated with runCatching: a failing
     * source logs its error and contributes an empty list while the remaining
     * sources keep working.
     */
    private suspend fun fetchFromSources(
        block: suspend (AppSource) -> List<AppItem>,
    ): RemoteOutcome = coroutineScope {
        val deferred = orderedSources.map { source ->
            async {
                val result = runCatching { block(source) }
                    .onFailure { e -> Log.e(TAG, "Source ${source.id} failed", e) }
                val succeeded = result.isSuccess
                result.getOrDefault(emptyList()) to succeeded
            }
        }
        val results = deferred.awaitAll()
        val flat = results.flatMap { it.first }
        val anySucceeded = results.any { it.second }
        RemoteOutcome(items = flat, anySucceeded = anySucceeded)
    }

    /**
     * Dedup by packageName keeping the entry from the highest-priority source
     * (fdroid=0 beats github=1). Priority is compared explicitly so the rule holds
     * even when local-cache entries are mixed into the list.
     */
    private fun dedupe(items: List<AppItem>): List<AppItem> {
        val prio = orderedSources.associate { it.id to it.priority }
        val best = LinkedHashMap<String, AppItem>()
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
        /** Cache freshness window: 24 hours. */
        const val TTL_MS = 24L * 60L * 60L * 1000L
        private const val TAG = "AppRepository"
    }
}
