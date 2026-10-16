package com.dazix.caelis.ar.pose

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Whether Google Play Services for AR is on this phone, as the system's package list says.
 *
 * Asked of the local PackageManager only, never of ARCore (the SDK's availability check talks to
 * Play Services and is consent-gated in [ArCoreSessionController]), so Settings can show it before
 * the user has agreed to anything. It answers "installed", not "this phone is supported": a package
 * can be present on a phone ARCore does not certify, and only the consented check can tell.
 */
sealed interface ArCorePresence {

    /** Installed for this user. [preinstalled] when it shipped with the system image (Pixels do). */
    data class Installed(val versionName: String, val preinstalled: Boolean) : ArCorePresence

    data object NotInstalled : ArCorePresence

    companion object {
        /** The package of Google Play Services for AR. */
        const val PACKAGE = "com.google.ar.core"

        fun read(packageManager: PackageManager): ArCorePresence = try {
            val info = packageManager.getPackageInfo(PACKAGE, 0)
            from(info.versionName, info.applicationInfo?.flags ?: 0, info.applicationInfo?.enabled ?: true)
        } catch (_: PackageManager.NameNotFoundException) {
            NotInstalled
        }

        /** The decision, apart from the PackageManager so it can be unit-tested. */
        fun from(versionName: String?, applicationFlags: Int, enabled: Boolean): ArCorePresence {
            if (!enabled) return NotInstalled
            val preinstalled = applicationFlags and ApplicationInfo.FLAG_SYSTEM != 0
            return Installed(versionName.orEmpty(), preinstalled)
        }
    }
}
