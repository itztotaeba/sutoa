package com.kinyo.sutoa.data.source

import com.kinyo.sutoa.domain.model.AppItem

/**
 * Plugin interface for an app catalog source (F-Droid, GitHub, GitLab, ...).
 *
 * Rules for implementations:
 *  - NEVER throw: handle your own errors and return empty list / null instead.
 *  - Map everything into the unified [AppItem] model.
 *
 * To add a new source, implement this interface and register it with
 * in the ordered `provideAppSources` set in di/AppModule.kt.
 * No UI / ViewModel / Repository changes required.
 */
interface AppSource {

    /** Unique id, e.g. "fdroid". Also stored in [AppItem.source]. */
    val id: String

    /** Search apps. [page] is 0-based; sources that don't support paging ignore it. */
    suspend fun search(query: String, page: Int = 0): List<AppItem>

    /** Featured apps shown on Home. */
    suspend fun featured(): List<AppItem>

    /** Single app detail lookup by package name, or null when unknown/unreachable. */
    suspend fun get(packageName: String): AppItem?
}
