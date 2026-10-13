package com.kinyo.sutoa.domain.repository

import android.util.Log
import com.kinyo.sutoa.data.cache.AppCacheDao
import com.kinyo.sutoa.data.cache.AppEntity
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
 * Unified entry point between the UI layer (ViewModels) and the plugin-based
 * data layer ([AppSource] implementations + Room cache).
 *
 * Contract:
 *  - every public function is `suspend`;
 *  - all registered sources are queried **in parallel** with
 *    `coroutineScope { sources.map { async { ... } }.awaitAll().flatten() }`;
 *  - every `async` block is wrapped in `runCatching`, so one broken plugin can
 *    never sink the others — it contributes an empty list and the error is
 *    logged via Log.e(TAG, "Source <id> failed", e);
 *  - merged results are deduped with `distinctBy { it.packageName }` AFTER
 *    `flatten()`. Priority fdroid > github is guaranteed because the injected
 *    Set<AppSource> is provided (di/AppModule.kt) in priority order and
 *    distinctBy keeps the FIRST occurrence per package;
 *  - cache-first strategy backed by Room: fresh (< 24 h) cache short-circuits
 *    the network; stale/missing cache triggers a remote fetch that refreshes
 *    the cache; on total network failure whatever is in cache is served
 *    instead (with a warning log) so the app degrades gracefully offline.
 *
 * Adding a new source (GitLab, Codeberg, custom repo) only requires a new
 * [AppSource] implementation and adding it to the ordered set provided in
 * di/AppModule.kt —
 * this repository, the ViewModels and the UI stay untouched.
 */
@Singleton
class AppRepository @Inject constructor(
    /** Injected ordered set of plugins: fdroid first, then github (see AppModule). */
    private val sources: Set<@JvmSuppressWildcards AppSource>,
    private val dao: AppCacheDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    // ─── public API ────────────────────────────────────────────────────────────

    /**
     * Featured apps for the Home screen.
     * Cache-first: when the catalog cache is younger than TTL_MS we return it
     * without touching the network. Otherwise every source is fetched in
     * parallel, merged, deduped, persisted to Room and returned. If ALL remote
     * sources fail we fall back to the (stale) cache rather than erroring out.
     */
    suspend fun featured(): List<AppItem> = withContext(io) {
        val cached = readCache { dao.getAll() }

        if (cached.isNotEmpty() && isCacheFresh()) {
            Log.d(TAG, "featured: serving fresh cache (${cached.size} items)")
            return@withContext cached
        }

        val remote = fetchParallel { source -> source.featured() }

        if (remote.isEmpty()) {
            // Network error / no source produced anything -> serve cache + warn.
            if (cached.isNotEmpty()) {
                Log.w(TAG, "featured: all sources failed, serving cached data")
                return@withContext cached
            }
            Log.e(TAG, "featured: no remote data and empty cache")
            return@withContext emptyList()
        }

        val merged = dedupe(remote)
        writeCache { dao.insertAll(merged.map { it.toEntity() }) }
        merged
    }

    /**
     * Search across the local cache AND every remote source, merged & deduped.
     * Local matches are always included (offline-friendly); successful remote
     * results refresh the cache. On total network failure the cached matches
     * are returned instead of throwing.
     */
    suspend fun search(query: String, page: Int = 0): List<AppItem> = withContext(io) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()

        val local = readCache { dao.search(q) }
        val remote = fetchParallel { source -> source.search(q, page) }

        if (remote.isEmpty() && local.isEmpty()) {
            Log.w(TAG, "search(\"$q\"): nothing from network or cache")
            return@withContext emptyList()
        }
        if (remote.isEmpty()) {
            Log.w(TAG, "search(\"$q\"): all sources failed, serving cache only")
            return@withContext dedupe(local)
        }

        val merged = dedupe(local + remote)
        writeCache { dao.insertAll(remote.map { it.toEntity() }) }
        merged
    }

    /**
     * Single-app detail lookup by package name. Cache-first with a 24 h
     * freshness window keyed per package; on miss/stale, every source is asked
     * in parallel and the highest-priority hit (fdroid > github) is cached and
     * returned. Falls back to the stale cache entry when the whole network
     * layer fails. Returns null only when genuinely unknown everywhere.
     */
    suspend fun get(packageName: String): AppItem? = withContext(io) {
        val cachedEntity = runCatching { dao.getByPkg(packageName) }
            .onFailure { Log.w(TAG, "cache read failed for $packageName", it) }
            .getOrNull()
        val cached = cachedEntity?.toAppItem()
        val isFresh = cachedEntity != null &&
            (System.currentTimeMillis() - cachedEntity.cachedAt) < TTL_MS

        if (isFresh && cached != null) {
            Log.d(TAG, "get($packageName): serving fresh cache")
            return@withContext cached
        }

        val remote = fetchParallel { source -> listOfNotNull(source.get(packageName)) }
        val hit = dedupe(remote).firstOrNull()

        when {
            hit != null -> {
                writeCache { dao.insert(hit.toEntity()) }
                hit
            }
            // No source returned the package -> serve stale cache rather than null.
            cached != null -> {
                Log.w(TAG, "get($packageName): sources returned nothing, serving stale cache")
                cached
            }
            else -> {
                Log.d(TAG, "get($packageName): not found in any source or cache")
                null
            }
        }
    }

    /**
     * Like [featured] but ignores cache freshness — always does a full parallel
     * remote pass. Falls back to stale cache when every source fails.
     */
    suspend fun refreshFeatured(): List<AppItem> = withContext(io) {
        val remote = fetchParallel { source -> source.featured() }
        if (remote.isEmpty()) {
            Log.w(TAG, "refreshFeatured: all sources failed, serving cached data")
            readCache { dao.getAll() }
        } else {
            val merged = dedupe(remote)
            writeCache { dao.insertAll(merged.map { it.toEntity() }) }
            merged
        }
    }

    /** All cached apps — used by the Updates screen / worker as the known-catalog set. */
    suspend fun cachedApps(): List<AppItem> = withContext(io) {
        readCache { dao.getAll() }
    }

    /** Force-refresh one package (e.g. user pulled-to-refresh on Detail screen). */
    suspend fun refresh(packageName: String): AppItem? = withContext(io) {
        val remote = fetchParallel { source -> listOfNotNull(source.get(packageName)) }
        val hit = dedupe(remote).firstOrNull()
        if (hit != null) {
            writeCache { dao.insert(hit.toEntity()) }
            hit
        } else {
            Log.w(TAG, "refresh($packageName): network returned nothing, keeping cache")
            runCatching { dao.getByPkg(packageName)?.toAppItem() }.getOrNull()
        }
    }

    // ─── internals ─────────────────────────────────────────────────────────────

    /**
     * Runs [block] against every registered source IN PARALLEL using
     * coroutineScope + async. Each call is isolated with runCatching: a failing
     * source logs its error and contributes an empty list while the remaining
     * sources keep working.
     */
    private suspend fun fetchParallel(
        block: suspend (AppSource) -> List<AppItem>,
    ): List<AppItem> = coroutineScope {
        sources
            .map { source ->
                async {
                    runCatching { block(source) }
                        .onFailure { e -> Log.e(TAG, "Source ${source.id} failed", e) }
                        .getOrDefault(emptyList())
                }
            }
            .awaitAll()
            .flatten()
    }

    /**
     * Dedup by packageName keeping the FIRST occurrence. Because di/AppModule
     * provides the Set<AppSource> in priority order (fdroid before github), the
     * flatten() output already carries fdroid entries first — so fdroid wins.
     */
    private fun dedupe(items: List<AppItem>): List<AppItem> =
        items.distinctBy { it.packageName }

    private suspend fun isCacheFresh(): Boolean =
        runCatching { dao.hasFreshData(System.currentTimeMillis() - TTL_MS) }
            .getOrDefault(false)

    private inline fun readCache(
        crossinline query: suspend () -> List<AppEntity>,
    ): List<AppItem> = runCatching { query().map { it.toAppItem() } }
        .onFailure { Log.w(TAG, "cache read failed", it) }
        .getOrDefault(emptyList())

    private inline fun writeCache(crossinline block: suspend () -> Unit) {
        runCatching { block() }.onFailure { Log.w(TAG, "cache write failed", it) }
    }

    companion object {
        /** Cache freshness window: 24 hours. */
        const val TTL_MS = 24L * 60L * 60L * 1000L
        private const val TAG = "AppRepository"
    }
}
