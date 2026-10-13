package com.kinyo.sutoa.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.kinyo.sutoa.data.download.ApkDownloader
import com.kinyo.sutoa.domain.model.AppItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown with a human-readable message mapped 1:1 to UI snackbars. */
sealed class InstallException(message: String) : Exception(message) {
    class Network(msg: String = "Gagal memuat, cek koneksi") : InstallException(msg)
    class Corrupt(msg: String = "File rusak, coba lagi") : InstallException(msg)
    class HashMismatch(val expected: String, val actual: String) :
        InstallException("Verifikasi gagal, APK ditolak")
    class PackageMismatch(val expected: String, val actual: String) :
        InstallException("Verifikasi gagal, APK ditolak")
    class NoInstallPermission :
        InstallException("Izinkan instalasi aplikasi unknown terlebih dahulu")
}

/**
 * Full pipeline: download → SHA-256 verify → package-name verify → install intent.
 * A failed verification ALWAYS deletes the file and throws [InstallException];
 * unverified APKs are never handed to the system installer.
 */
@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloader: ApkDownloader,
) {

    /**
     * Downloads (if needed) and verifies the APK for [item].
     * @return verified file ready to be installed via [installIntent]
     * @throws InstallException on any failure (message is user-friendly)
     */
    suspend fun prepare(item: AppItem, onProgress: (Float) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            if (item.apkUrl.isBlank()) throw InstallException.Corrupt("URL APK tidak valid")

            val file = try {
                downloader.download(item.apkUrl, item.packageName, item.versionCode, onProgress)
            } catch (t: Throwable) {
                Log.w(TAG, "download failed", t)
                throw InstallException.Network()
            }

            // ── a. SHA-256 verification (when the source provides a hash) ──
            if (!item.sha256.isNullOrBlank()) {
                val actual = runCatching { ApkDownloader.sha256(file) }
                    .getOrElse {
                        file.delete()
                        throw InstallException.Corrupt()
                    }
                if (!actual.equals(item.sha256.trim().lowercase())) {
                    Log.e(TAG, "hash mismatch ${item.packageName}: expected=${item.sha256} actual=$actual")
                    file.delete()
                    throw InstallException.HashMismatch(item.sha256, actual)
                }
            }

            // ── b. package-name verification via getPackageArchiveInfo ──
            val archive = context.packageManager
                .getPackageArchiveInfo(file.absolutePath, PackageManager.GET_META_DATA)
            if (archive == null) {
                file.delete()
                throw InstallException.Corrupt() // not a parseable APK
            }
            val actualPkg = archive.packageName
            if (actualPkg != item.packageName) {
                Log.e(TAG, "package mismatch: expected=${item.packageName} actual=$actualPkg")
                file.delete()
                throw InstallException.PackageMismatch(item.packageName, actualPkg)
            }

            file
        }

    /** ACTION_VIEW install intent (MVP; no PackageInstaller session API yet). */
    fun installIntent(file: File): Intent = installApkIntent(context, file)

    companion object {
        private const val TAG = "ApkInstaller"
    }
}
