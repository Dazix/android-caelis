package com.dazix.intervalometer.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.dazix.intervalometer.service.ShutterAccessibilityService

/**
 * Everything the app needs from the system before it can tap for the user. Read fresh from the
 * platform on every resume — none of these can be cached, because the user grants them in Settings
 * and comes back.
 */
data class PermissionState(
    val accessibilityEnabled: Boolean = false,
    val canDrawOverlays: Boolean = false,
    val notificationsGranted: Boolean = false,
    val ignoringBatteryOptimizations: Boolean = false,
    val readMediaImagesGranted: Boolean = false,
    /**
     * The user answered the photos dialog with "Select photos": only the pictures they picked are
     * readable, so capture verification cannot see the frames the camera app writes from now on.
     */
    val readMediaPartialGranted: Boolean = false,
    /** Installed outside a store, so Android hides the accessibility switch until unblocked. */
    val installedFromOutsideStore: Boolean = false,
) {
    /** Only the two the tapping itself cannot work without. */
    val readyToTap: Boolean get() = accessibilityEnabled && canDrawOverlays

    val allGranted: Boolean
        get() = readyToTap && notificationsGranted && ignoringBatteryOptimizations
}

object Permissions {

    private val KNOWN_STORES = setOf(
        "com.android.vending",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.amazon.venezia",
        "org.fdroid.fdroid",
    )

    fun read(context: Context): PermissionState = PermissionState(
        accessibilityEnabled = accessibilityServiceEnabled(context),
        canDrawOverlays = Settings.canDrawOverlays(context),
        notificationsGranted = granted(context, Manifest.permission.POST_NOTIFICATIONS),
        ignoringBatteryOptimizations = ignoringBatteryOptimizations(context),
        readMediaImagesGranted = granted(context, Manifest.permission.READ_MEDIA_IMAGES),
        readMediaPartialGranted = partialPhotoAccess(context),
        installedFromOutsideStore = installedFromOutsideStore(context),
    )

    /**
     * True while the app has *only* the selected-photos grant. Full access implies it, so the full
     * grant wins; a platform that does not know the permission answers "denied" and the UI falls
     * back to the plain "not granted yet" wording.
     */
    private fun partialPhotoAccess(context: Context): Boolean =
        !granted(context, Manifest.permission.READ_MEDIA_IMAGES) &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Asks the platform which accessibility services are enabled rather than whether our service
     * object happens to be bound, so the answer is right even while the service is starting up.
     */
    fun accessibilityServiceEnabled(context: Context): Boolean {
        val expected = "${context.packageName}/${ShutterAccessibilityService::class.java.name}"
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val enabled = manager
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { it.id.equals(expected, ignoreCase = true) }
        if (enabled == true) return true
        val raw = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull() ?: return false
        return raw.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun ignoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: false

    private fun installedFromOutsideStore(context: Context): Boolean = runCatching {
        val installer = context.packageManager
            .getInstallSourceInfo(context.packageName)
            .installingPackageName
        installer == null || installer !in KNOWN_STORES
    }.getOrDefault(true)

    fun accessibilitySettings(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun overlaySettings(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun batteryOptimizationExemption(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", context.packageName, null),
        )

    /** Where the "Allow restricted settings" menu item lives, for sideloaded builds. */
    fun appInfo(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
