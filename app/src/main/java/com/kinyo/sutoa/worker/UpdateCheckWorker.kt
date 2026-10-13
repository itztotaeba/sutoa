package com.kinyo.sutoa.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kinyo.sutoa.MainActivity
import com.kinyo.sutoa.R
import com.kinyo.sutoa.data.cache.AppCacheDao
import com.kinyo.sutoa.data.cache.toAppItem
import com.kinyo.sutoa.domain.repository.AppRepository
import com.kinyo.sutoa.util.PackageUtils
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Periodic (12h, unmetered) update checker:
 *  - refreshes the catalog from all sources into Room
 *  - for installed apps whose cached versionCode > installed longVersionCode,
 *    posts a notification linking to that app's DetailScreen.
 * Compares numeric version codes only — never versionName strings.
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: AppRepository,
    private val dao: AppCacheDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Refresh remote catalog into cache; tolerate offline (use whatever is cached).
        val remoteCount = repository.featured().size
        if (remoteCount == 0 && dao.getAll().isEmpty()) return Result.retry()

        val updates = dao.getAll()
            .map { it.toAppItem() }
            .filter { item ->
                val installed = PackageUtils.installedVersionCode(applicationContext, item.packageName)
                installed != null && item.versionCode > installed
            }

        if (updates.isNotEmpty()) notifyUpdates(updates.map { it.name to it.packageName })
        return Result.success()
    }

    private fun notifyUpdates(updates: List<Pair<String, String>>) {
        ensureChannel()
        val firstPkg = updates.first().second

        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            firstPkg.hashCode(),
            Intent(applicationContext, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_OPEN_PACKAGE, firstPkg)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = if (updates.size == 1) {
            "${updates.first().first} punya versi baru"
        } else {
            "${updates.size} aplikasi punya versi baru"
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Update tersedia")
            .setContentText(text)
            .setStyle(
                NotificationCompat.InboxStyle().also { style ->
                    updates.take(5).forEach { (name, _) -> style.addLine(name) }
                }
            )
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .build()

        val nm = NotificationManagerCompat.from(applicationContext)
        if (ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            nm.notify(NOTIF_ID, notification)
        }
    }

    private fun ensureChannel() {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Notifikasi update aplikasi" },
        )
    }

    companion object {
        const val CHANNEL_ID = "updates"
        const val GROUP = "sutoa.updates"
        const val WORK_NAME = "update-check"
        const val NOTIF_ID = 4200
        const val EXTRA_OPEN_PACKAGE = "open_package"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
