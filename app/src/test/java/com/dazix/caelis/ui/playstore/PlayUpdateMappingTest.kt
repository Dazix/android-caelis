package com.dazix.caelis.ui.playstore

import com.dazix.caelis.domain.playstore.UpdateAvailability
import com.dazix.caelis.domain.playstore.UpdateInstall
import com.google.android.play.core.install.model.InstallStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import com.google.android.play.core.install.model.UpdateAvailability as PlayAvailability

/** Against the library's own constants, so a renumbering in a new release fails here, not on a phone. */
class PlayUpdateMappingTest {

    @Test
    fun `every availability Play reports maps to its own state`() {
        assertEquals(UpdateAvailability.AVAILABLE, PlayUpdateMapping.availability(PlayAvailability.UPDATE_AVAILABLE))
        assertEquals(UpdateAvailability.NOT_AVAILABLE, PlayUpdateMapping.availability(PlayAvailability.UPDATE_NOT_AVAILABLE))
        assertEquals(
            UpdateAvailability.IN_PROGRESS,
            PlayUpdateMapping.availability(PlayAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS),
        )
        assertEquals(UpdateAvailability.UNKNOWN, PlayUpdateMapping.availability(PlayAvailability.UNKNOWN))
        assertEquals(UpdateAvailability.UNKNOWN, PlayUpdateMapping.availability(-1))
    }

    @Test
    fun `every install status Play reports maps to its own state`() {
        mapOf(
            InstallStatus.PENDING to UpdateInstall.PENDING,
            InstallStatus.DOWNLOADING to UpdateInstall.DOWNLOADING,
            InstallStatus.DOWNLOADED to UpdateInstall.DOWNLOADED,
            InstallStatus.INSTALLING to UpdateInstall.INSTALLING,
            InstallStatus.INSTALLED to UpdateInstall.INSTALLED,
            InstallStatus.FAILED to UpdateInstall.FAILED,
            InstallStatus.CANCELED to UpdateInstall.CANCELED,
            InstallStatus.UNKNOWN to UpdateInstall.NONE,
            InstallStatus.REQUIRES_UI_INTENT to UpdateInstall.NONE,
        ).forEach { (code, expected) -> assertEquals("status $code", expected, PlayUpdateMapping.install(code)) }
    }
}
