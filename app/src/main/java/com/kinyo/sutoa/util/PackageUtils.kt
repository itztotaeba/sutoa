package com.kinyo.sutoa.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/** Helpers around the device's installed packages. */
object PackageUtils {

    /** Installed version code of [packageName], or null when not installed. */
    fun installedVersionCode(context: Context, packageName: String): Long? =
        installedPackageInfo(context, packageName)?.longVersionCodeSafe()

    fun isInstalled(context: Context, packageName: String): Boolean =
        installedVersionCode(context, packageName) != null

    /** Launch intent for an installed app, or null if it has no launcher activity. */
    fun getLaunchIntent(context: Context, packageName: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(packageName)

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(context: Context, packageName: String): PackageInfo? = try {
        context.packageManager.getPackageInfo(packageName, 0)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    } catch (t: Throwable) {
        null
    }

    /** Always compare numeric version codes — never versionName strings. */
    @Suppress("DEPRECATION")
    private fun PackageInfo.longVersionCodeSafe(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

    /** All installed launchable packages (visible thanks to <queries> in the manifest). */
    fun installedLaunchablePackages(context: Context): List<String> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(main, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .distinct()
        }.getOrDefault(emptyList())
    }
}
