package com.kinyo.sutoa.data.cache

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.kinyo.sutoa.domain.model.AppItem

/** Room row representation of [AppItem] + cache bookkeeping. */
@Entity(tableName = "apps")
data class AppEntity(
    @PrimaryKey
    @ColumnInfo(name = "packageName") val packageName: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "summary") val summary: String,
    @ColumnInfo(name = "description") val description: String,
    @ColumnInfo(name = "iconUrl") val iconUrl: String?,
    /** Pipe ("|") joined screenshot URLs. */
    @ColumnInfo(name = "screenshots") val screenshots: String,
    @ColumnInfo(name = "versionName") val versionName: String,
    @ColumnInfo(name = "versionCode") val versionCode: Long,
    @ColumnInfo(name = "apkUrl") val apkUrl: String,
    @ColumnInfo(name = "sha256") val sha256: String?,
    @ColumnInfo(name = "sizeBytes") val sizeBytes: Long,
    @ColumnInfo(name = "minSdk") val minSdk: Int?,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "sourceUrl") val sourceUrl: String,
    @ColumnInfo(name = "lastUpdated") val lastUpdated: Long,
    /** Epoch millis when this row was written to the cache. */
    @ColumnInfo(name = "cachedAt") val cachedAt: Long,
)

fun AppEntity.toAppItem(): AppItem = AppItem(
    packageName = packageName,
    name = name,
    summary = summary,
    description = description,
    iconUrl = iconUrl,
    screenshots = if (screenshots.isBlank()) emptyList() else screenshots.split("|"),
    versionName = versionName,
    versionCode = versionCode,
    apkUrl = apkUrl,
    sha256 = sha256,
    sizeBytes = sizeBytes,
    minSdk = minSdk,
    source = source,
    sourceUrl = sourceUrl,
    lastUpdated = lastUpdated,
)

fun AppItem.toEntity(cachedAt: Long = System.currentTimeMillis()): AppEntity = AppEntity(
    packageName = packageName,
    name = name,
    summary = summary,
    description = description,
    iconUrl = iconUrl,
    screenshots = screenshots.joinToString("|"),
    versionName = versionName,
    versionCode = versionCode,
    apkUrl = apkUrl,
    sha256 = sha256,
    sizeBytes = sizeBytes,
    minSdk = minSdk,
    source = source,
    sourceUrl = sourceUrl,
    lastUpdated = lastUpdated,
    cachedAt = cachedAt,
)
