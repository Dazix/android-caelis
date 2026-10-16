package com.dazix.caelis.ar.pose

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ArCorePresenceTest {

    @Test
    fun aSystemPackageUpdatedFromPlayIsPreinstalled() {
        // A Pixel: shipped in /product, updated by Play; both flags are set.
        val flags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        assertEquals(
            ArCorePresence.Installed("1.56.262080393", preinstalled = true),
            ArCorePresence.from("1.56.262080393", flags, enabled = true),
        )
    }

    @Test
    fun aPackageInstalledFromPlayIsNotPreinstalled() {
        assertEquals(
            ArCorePresence.Installed("1.56.0", preinstalled = false),
            ArCorePresence.from("1.56.0", 0, enabled = true),
        )
    }

    @Test
    fun aDisabledPackageCountsAsMissing() {
        // Disabled by the user: ARCore cannot start, so telling them it is there would mislead.
        assertEquals(ArCorePresence.NotInstalled, ArCorePresence.from("1.56.0", ApplicationInfo.FLAG_SYSTEM, enabled = false))
    }

    @Test
    fun aMissingVersionNameIsStillInstalled() {
        assertEquals(ArCorePresence.Installed("", preinstalled = false), ArCorePresence.from(null, 0, enabled = true))
    }
}
