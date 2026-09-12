package com.dazix.caelis.data.horizon

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager

/**
 * Answers what the current build and the current connection actually allow.
 *
 * The `INTERNET` permission is checked at runtime even though it is granted at install time and
 * cannot be revoked. That is not defensive noise: deleting the `INTERNET` line from the manifest is
 * a documented, supported way to build this app from source without a network, and everything from
 * the settings screen to the consent prompt has to disappear cleanly when somebody does. Asking the
 * package manager is how the UI finds out, rather than a build flag that would then have to be kept
 * in step with the manifest.
 */
object NetworkAvailability {

    /** True when this build declares `INTERNET`. False in a manifest-trimmed build from source. */
    fun hasInternetPermission(context: Context): Boolean =
        context.packageManager.checkPermission(
            Manifest.permission.INTERNET,
            context.packageName,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * True when the active connection is metered, or when that cannot be determined.
     *
     * Unknown counts as metered on purpose. Guessing "unmetered" would spend somebody's mobile data
     * on a 19 MB tile; guessing the other way only makes them tap once more.
     */
    fun isMetered(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        return try {
            manager.isActiveNetworkMetered
        } catch (e: SecurityException) {
            // ACCESS_NETWORK_STATE stripped along with INTERNET in a trimmed build.
            true
        }
    }

    /** True when there is any usable connection at all, as far as the system will say. */
    fun isConnected(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return try {
            manager.activeNetwork != null
        } catch (e: SecurityException) {
            false
        }
    }
}
