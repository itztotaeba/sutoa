package com.kinyo.sutoa.domain.model

/**
 * Unified app model. Every [com.kinyo.sutoa.data.source.AppSource] must map its
 * DTOs into this model so the UI stays source-agnostic.
 */
data class AppItem(
    val packageName: String,
    val name: String,
    val summary: String,
    val description: String,
    val iconUrl: String?,
    val screenshots: List<String>,
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String,
    val sha256: String?,
    val sizeBytes: Long,
    val minSdk: Int?,
    /** Source plugin id, e.g. "fdroid" or "github". */
    val source: String,
    /** Web page of the app on its source (repo / fdroid page). */
    val sourceUrl: String,
    /** Epoch millis of last upstream update reported by the source. */
    val lastUpdated: Long,
) {
    val sizeMbLabel: String
        get() = if (sizeBytes <= 0L) "?" else String.format("%.1f MB", sizeBytes / 1048576.0)
}
