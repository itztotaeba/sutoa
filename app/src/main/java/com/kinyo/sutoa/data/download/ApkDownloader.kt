package com.kinyo.sutoa.data.download

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Streams APK files into the app cache dir (`cacheDir/apks/`) with progress callback.
 * Also exposes a SHA-256 helper used by the installer for verification.
 */
@Singleton
class ApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) {

    /** Destination file for a given package/version (created lazily). */
    fun targetFile(packageName: String, versionCode: Long): File {
        val dir = File(context.cacheDir, APK_DIR).apply { mkdirs() }
        return File(dir, "${packageName}_${versionCode}.apk")
    }

    /**
     * Download [url] to `cacheDir/apks/{pkg}_{versionCode}.apk`.
     * Returns the downloaded file. If the file already exists it is returned as-is
     * (caller may re-download by deleting first).
     *
     * @param onProgress fraction 0f..1f (-1 when total size unknown)
     * @throws IOException on network / IO failure
     */
    suspend fun download(
        url: String,
        packageName: String,
        versionCode: Long,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val dest = targetFile(packageName, versionCode)
        if (dest.exists() && dest.length() > 0) {
            Log.d(TAG, "already downloaded: ${dest.name}")
            return@withContext dest
        }

        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val body = response.body ?: throw IOException("Empty response body")
            val total = response.headers["content-length"]?.toLongOrNull()
                ?: body.contentLength().takeIf { it > 0 } ?: -1L

            val tmp = File(dest.parentFile, dest.name + ".part")
            try {
                tmp.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var done = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            done += read
                            onProgress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f)
                        }
                        out.flush()
                    }
                }
            } catch (t: Throwable) {
                // Cleanup: never leave a partial/corrupt file behind.
                if (!tmp.delete()) Log.w(TAG, "failed to delete partial download ${tmp.name}")
                if (dest.exists() && dest.length() == 0L) dest.delete()
                throw t
            }
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                throw IOException("Failed to move temp file into place")
            }
            dest
        }
    }

    companion object {
        const val APK_DIR = "apks"

        /** Streaming SHA-256 of a file, hex lowercase. */
        fun sha256(file: File): String {
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

        private const val TAG = "ApkDownloader"
    }
}
