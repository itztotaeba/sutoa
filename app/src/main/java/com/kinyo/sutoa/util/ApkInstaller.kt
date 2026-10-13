package com.kinyo.sutoa.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.kinyo.sutoa.data.download.ApkDownloader
import com.kinyo.sutoa.domain.model.AppItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Errors carrying a human-readable message mapped 1:1 to UI snackbars:
 *  - Network      -> "Gagal memuat, cek koneksi"
 *  - Corrupt      -> "File rusak, coba lagi"
 *  - HashMismatch / PackageMismatch -> "Verifikasi gagal, APK ditolak"
 */
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
 * Full install pipeline for one [AppItem]:
 *
 *   a. download APK (OkHttp streaming, byteStream — never bytes(), no OOM)
 *   b. verify SHA-256 against the source-provided hash (when present)
 *   c. verify packageName via PackageManager.getPackageArchiveInfo()
 *   d. only when ALL checks pass -> hand a FileProvider ACTION_VIEW intent to the UI
 *   e. any failure -> delete the file and throw [InstallException] with a clear message
 *
 * An unverified APK is NEVER handed to the system installer.
 */
@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloader: ApkDownloader,
) {

    /**
     * Downloads (if needed) and fully verifies the APK for [item].
     *
     * @return verified file ready to be installed via [installIntent]
     * @throws InstallException on any failure; the message is user-friendly and the
     *         offending file has already been deleted.
     */
    suspend fun prepare(item: AppItem, onProgress: (Float) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            if (item.apkUrl.isBlank()) throw InstallException.Corrupt("URL APK tidak valid")

            // ── a. download → file (streaming; cleanup handled inside downloader & here) ──
            val apk = try {
                downloader.download(item.apkUrl, item.packageName, item.versionCode, onProgress)
            } catch (t: Throwable) {
                Log.w(TAG, "download failed for ${item.packageName}", t)
                // Defensive: make sure no partial file lingers if the downloader was interrupted.
                runCatching { downloader.targetFile(item.packageName, item.versionCode).delete() }
                throw InstallException.Network()
            }

            // ── b. SHA-256 verification (only when the source provides an expected hash) ──
            val expectedSha = item.sha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            if (expectedSha != null) {
                val actualSha = runCatching { sha256Hex(apk) }
                    .getOrElse {
                        apk.delete()
                        Log.e(TAG, "hash computation failed for ${apk.name}", it)
                        throw InstallException.Corrupt()
                    }
                if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                    apk.delete()
                    Log.e(TAG, "SHA-256 mismatch ${item.packageName}: " +
                        "expected=$expectedSha actual=$actualSha")
                    throw InstallException.HashMismatch(expectedSha, actualSha)
                }
            }

            // ── c. package-name verification via getPackageArchiveInfo ──
            val info = context.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES,
            ) ?: run {
                apk.delete()
                Log.e(TAG, "not a parseable APK: ${apk.name}")
                throw InstallException.Corrupt()
            }
            if (info.packageName != item.packageName) {
                apk.delete()
                Log.e(TAG, "packageName mismatch: expected=${item.packageName} " +
                    "actual=${info.packageName}")
                throw InstallException.PackageMismatch(item.packageName, info.packageName ?: "?")
            }

            // ── d. all checks passed — file is safe to install ──
            apk
        }

    /**
     * Launches the system installer for a previously [prepare]-d file.
     *
     * Checks REQUEST_INSTALL_PACKAGES first: when the user has not granted
     * "install unknown apps" we open the matching settings screen
     * (ACTION_MANAGE_UNKNOWN_APP_SOURCES) and return false so the caller can show
     * a hint instead of crashing with SecurityException.
     *
     * @return true when the installer sheet was launched.
     */
    fun install(apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            Log.i(TAG, "no install permission — opening unknown-sources settings")
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }
            .onFailure { e ->
                Log.e(TAG, "failed to launch installer", e)
                apk.delete() // couldn't install — don't leave stale apks around
            }
            .isSuccess
    }

    /** Convenience: prepare + install in one call. Throws [InstallException] on failure. */
    suspend fun downloadVerifyAndInstall(
        item: AppItem,
        onProgress: (Float) -> Unit = {},
    ): Boolean = install(prepare(item, onProgress))

    /** Streaming SHA-256 of [file] as lowercase hex (buffered — safe for large APKs). */
    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * ACTION_VIEW install intent through our FileProvider (cache-path "apks/").
     * Always carries FLAG_GRANT_READ_URI_PERMISSION so the system installer can
     * actually read the content URI. MVP: no PackageInstaller session API yet.
     */
    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    companion object {
        private const val TAG = "ApkInstaller"
    }
}
