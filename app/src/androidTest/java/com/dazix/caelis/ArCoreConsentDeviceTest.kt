package com.dazix.caelis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dazix.caelis.ar.pose.ArCoreSessionController
import com.dazix.caelis.data.ArCoreSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * With consent off, Google Play Services for AR is never contacted by this app: no `Session` is
 * constructed, and Play Services is not even asked whether ARCore is installed.
 *
 * These run against the **real** platform on a real device (the controller's default
 * `ArCorePlatform`), which is the whole point: on a phone that has Google Play Services for AR
 * installed, a controller that forgot to check consent would start it here, and the process-wide
 * counters would say so. Consent is supplied by the test rather than read from the app's settings,
 * so the test neither depends on nor changes what the person using this phone has chosen, and can
 * never turn ARCore on by itself.
 *
 * Two controllers stand for the two AR screens (Sun AR and Night Sky), each of which owns one in
 * production.
 */
@RunWith(AndroidJUnit4::class)
class ArCoreConsentDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun resetCounters() {
        ArCoreSessionController.resetCountersForTest()
    }

    private fun controller(settings: ArCoreSettings) = ArCoreSessionController(
        context = context,
        consent = { settings.normalized().arCoreConsent },
        geospatialConsent = { settings.normalized().geospatialConsent },
        geospatialApiKeyAvailable = true,
    )

    private fun assertNothingStarted(vararg controllers: ArCoreSessionController) {
        assertEquals("a Session was constructed without consent", 0, ArCoreSessionController.sessionCreationAttempts)
        assertEquals(
            "Play Services was asked about ARCore without consent",
            0,
            ArCoreSessionController.availabilityCheckCount,
        )
        assertEquals("an install was requested without a user action", 0, ArCoreSessionController.installRequestCount)
        controllers.forEach { assertNull(it.session) }
    }

    @Test
    fun consentOffStartsNothingFromEitherArScreen() = runBlocking {
        val sunAr = controller(ArCoreSettings())
        val nightSky = controller(ArCoreSettings())

        // What each screen does when it opens, and again when it comes back to the foreground.
        repeat(2) {
            assertEquals(ArCoreSessionController.Status.NotConsented, sunAr.resume())
            assertEquals(ArCoreSessionController.Status.NotConsented, nightSky.resume())
        }
        // What a settings screen or a pose source might ask before showing anything.
        assertEquals(ArCoreSessionController.Status.NotConsented, sunAr.checkAvailability())
        assertEquals(ArCoreSessionController.Status.NotConsented, nightSky.checkAvailability())
        sunAr.pause()
        nightSky.close()

        assertNothingStarted(sunAr, nightSky)
    }

    /**
     * The state the settings screen cannot produce but a hand-edited backup can: Geospatial yes,
     * ARCore no. It must read as no to both.
     */
    @Test
    fun geospatialWithoutArCoreStartsNothing() = runBlocking {
        val controller = controller(ArCoreSettings(arCoreConsent = false, geospatialConsent = true))

        assertEquals(ArCoreSessionController.Status.NotConsented, controller.resume())

        assertNothingStarted(controller)
    }

    @Test
    fun consentWithdrawnWhileRunningIsNoticedByTheGuardTick() = runBlocking {
        var settings = ArCoreSettings(arCoreConsent = true, geospatialConsent = true)
        val controller = ArCoreSessionController(
            context = context,
            consent = { settings.normalized().arCoreConsent },
            geospatialConsent = { settings.normalized().geospatialConsent },
            geospatialApiKeyAvailable = true,
        )

        // No call here has reached ARCore: the controller only reads consent until something asks it
        // to resume, and nothing has.
        assertEquals(true, controller.revalidateConsent())

        settings = settings.withArCoreConsent(false)

        assertEquals(false, controller.revalidateConsent())
        assertEquals(ArCoreSessionController.Status.NotConsented, controller.status.value)
        assertNothingStarted(controller)
    }
}
