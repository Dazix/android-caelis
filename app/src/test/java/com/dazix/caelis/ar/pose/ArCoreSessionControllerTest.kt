package com.dazix.caelis.ar.pose

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dazix.caelis.data.SettingsStore
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The consent gate in front of Google Play Services for AR, driven through a fake platform so no
 * Google component is needed. Nothing here can reach a real `Session`: the one place a `Session` is
 * constructed is [RealArCorePlatform], which these tests replace, and every path that would continue
 * past a successful construction needs ARCore's native library.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArCoreSessionControllerTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ArCoreSessionController.resetCountersForTest()
    }

    /** Records every call and answers from the script, so a test can assert what was *not* called. */
    private class FakePlatform(
        var availability: () -> ArCoreApk.Availability = { ArCoreApk.Availability.SUPPORTED_INSTALLED },
        var install: () -> ArCoreApk.InstallStatus = { ArCoreApk.InstallStatus.INSTALLED },
        var create: () -> Session = { throw UnavailableDeviceNotCompatibleException() },
    ) : ArCorePlatform {
        var availabilityCalls = 0
        var installCalls = 0
        var createCalls = 0

        override fun checkAvailability(context: Context): ArCoreApk.Availability {
            availabilityCalls++
            return availability()
        }

        override fun requestInstall(activity: Activity): ArCoreApk.InstallStatus {
            installCalls++
            return install()
        }

        override fun createSession(context: Context): Session {
            createCalls++
            return create()
        }

        val touched: Boolean get() = availabilityCalls + installCalls + createCalls > 0
    }

    private fun controller(
        platform: FakePlatform,
        consent: suspend () -> Boolean,
        geospatial: suspend () -> Boolean = { false },
    ) = ArCoreSessionController(
        context = context,
        consent = consent,
        geospatialConsent = geospatial,
        geospatialApiKeyAvailable = true,
        platform = platform,
        availabilityRetries = 3,
        availabilityRetryDelayMs = 0L,
    )

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).get()

    // ---- No consent: Google is never contacted ----

    @Test
    fun `without consent nothing is asked of Play Services at all`() = runBlocking {
        val platform = FakePlatform()
        val controller = controller(platform, consent = { false })

        assertEquals(ArCoreSessionController.Status.NotConsented, controller.checkAvailability())
        assertEquals(ArCoreSessionController.Status.NotConsented, controller.resume())
        assertEquals(ArCoreSessionController.Status.NotConsented, controller.requestInstallFromUserAction(activity()))

        assertFalse("the platform must not be touched without consent", platform.touched)
        assertNull(controller.session)
        assertEquals(0, ArCoreSessionController.sessionCreationAttempts)
        assertEquals(0, ArCoreSessionController.availabilityCheckCount)
        assertEquals(0, ArCoreSessionController.installRequestCount)
    }

    @Test
    fun `a consent read that fails is no consent and starts nothing`() = runBlocking {
        val platform = FakePlatform()
        val controller = controller(platform, consent = { readOrDeny { throw IOException("store unreadable") } })

        assertEquals(ArCoreSessionController.Status.NotConsented, controller.resume())

        assertFalse(platform.touched)
    }

    @Test
    fun `a cancelled consent read is rethrown and is not mistaken for a refusal`() {
        try {
            runBlocking { readOrDeny { throw CancellationException("scope ended") } }
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
            // expected
        }
    }

    @Test
    fun `the production wiring reads off by default and never reaches the platform`() = runBlocking {
        // A real settings store on an empty data store: this is the answer a fresh install gives.
        val controller = ArCoreSessionController.fromSettings(context, SettingsStore(context))

        assertEquals(ArCoreSessionController.Status.NotConsented, controller.resume())
        assertEquals(0, ArCoreSessionController.sessionCreationAttempts)
        assertEquals(0, ArCoreSessionController.availabilityCheckCount)
    }

    @Test
    fun `two controllers without consent still leave every counter at zero`() = runBlocking {
        val first = controller(FakePlatform(), consent = { false })
        val second = controller(FakePlatform(), consent = { false })

        first.resume()
        second.resume()
        first.checkAvailability()
        second.revalidateConsent()

        assertEquals(0, ArCoreSessionController.sessionCreationAttempts)
        assertEquals(0, ArCoreSessionController.availabilityCheckCount)
    }

    // ---- Consent is read per call ----

    @Test
    fun `consent is read again on every call rather than remembered`() = runBlocking {
        var answer = true
        var reads = 0
        val platform = FakePlatform(availability = { ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE })
        val controller = controller(platform, consent = { reads++; answer })

        assertEquals(ArCoreSessionController.Status.Unsupported, controller.checkAvailability())
        val probesBeforeRevoking = platform.availabilityCalls
        val readsBeforeRevoking = reads

        answer = false
        assertEquals(ArCoreSessionController.Status.NotConsented, controller.checkAvailability())

        assertTrue("every call must consult consent", reads > readsBeforeRevoking)
        assertEquals("a revoked consent must stop the probe at once", probesBeforeRevoking, platform.availabilityCalls)
    }

    @Test
    fun `consent withdrawn while the availability check is still running stops it`() = runBlocking {
        var reads = 0
        val platform = FakePlatform(availability = { ArCoreApk.Availability.UNKNOWN_CHECKING })
        // Yes on entry, no on the first re-read after the transient answer.
        val controller = controller(platform, consent = { reads++ < 1 })

        assertEquals(ArCoreSessionController.Status.NotConsented, controller.checkAvailability())
        assertEquals(1, platform.availabilityCalls)
    }

    @Test
    fun `revalidating a withdrawn consent reports it and publishes not consented`() = runBlocking {
        var answer = true
        val controller = controller(FakePlatform(), consent = { answer })
        assertTrue(controller.revalidateConsent())

        answer = false

        assertFalse(controller.revalidateConsent())
        assertEquals(ArCoreSessionController.Status.NotConsented, controller.status.value)
    }

    // ---- A "yes" that went stale while it was being read ----

    /**
     * Found by a security review: a resume suspended in a consent read, while the user withdrew
     * consent (the guard tick's revalidateConsent closes the controller), went on and created the
     * session anyway. The read itself had answered yes, a moment too early. Each read here closes the
     * controller "during" the read and still answers yes, which is exactly that interleaving, at
     * every read a resume makes in turn.
     */
    @Test
    fun `a close during any consent read stops resume from creating a session`() = runBlocking {
        // How many times an undisturbed resume reads consent, so every one of them is covered.
        var baselineReads = 0
        val baseline = FakePlatform()
        controller(baseline, consent = { baselineReads++; true }).resume()
        assertEquals("the undisturbed resume must reach the constructor", 1, baseline.createCalls)
        assertTrue("resume reads consent at entry, in the availability check and in front of the constructor", baselineReads >= 3)

        for (closeAt in 1..baselineReads) {
            ArCoreSessionController.resetCountersForTest()
            val platform = FakePlatform()
            var reads = 0
            lateinit var controller: ArCoreSessionController
            controller = controller(platform, consent = {
                if (++reads == closeAt) controller.close()
                true
            })

            controller.resume()

            assertEquals("close at read $closeAt: no session may be constructed", 0, platform.createCalls)
            assertEquals("close at read $closeAt", 0, ArCoreSessionController.sessionCreationAttempts)
            assertNull("close at read $closeAt", controller.session)
        }
    }

    @Test
    fun `a close during the geospatial read stops resume from creating a session`() = runBlocking {
        val platform = FakePlatform()
        lateinit var controller: ArCoreSessionController
        controller = controller(platform, consent = { true }, geospatial = {
            controller.close()
            true
        })

        controller.resume()

        assertEquals(0, platform.createCalls)
        assertNull(controller.session)
    }

    @Test
    fun `a close during the first consent read asks Play Services nothing and publishes nothing`() = runBlocking {
        val platform = FakePlatform()
        lateinit var controller: ArCoreSessionController
        controller = controller(platform, consent = {
            controller.close()
            true
        })

        val status = controller.checkAvailability()

        assertEquals("a stale yes must not reach Play Services", 0, platform.availabilityCalls)
        assertFalse("a stale yes must not publish Ready", status == ArCoreSessionController.Status.Ready)
        assertFalse(controller.status.value == ArCoreSessionController.Status.Ready)
    }

    @Test
    fun `a close during a consent re-read while availability is still checking stops the probing`() = runBlocking {
        val platform = FakePlatform(availability = { ArCoreApk.Availability.UNKNOWN_CHECKING })
        var reads = 0
        lateinit var controller: ArCoreSessionController
        controller = controller(platform, consent = {
            if (++reads == 2) controller.close()
            true
        })

        controller.checkAvailability()

        assertEquals("no probe after the controller was closed", 1, platform.availabilityCalls)
    }

    @Test
    fun `a close during the consent read stops an install request`() = runBlocking {
        val platform = FakePlatform()
        lateinit var controller: ArCoreSessionController
        controller = controller(platform, consent = {
            controller.close()
            true
        })

        controller.requestInstallFromUserAction(activity())

        assertEquals(0, platform.installCalls)
        assertEquals(0, ArCoreSessionController.installRequestCount)
    }

    // ---- Availability, only after consent ----

    @Test
    fun `availability maps the platform answers and is asked only with consent`() = runBlocking {
        val expected = mapOf(
            ArCoreApk.Availability.SUPPORTED_INSTALLED to ArCoreSessionController.Status.Ready,
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED to ArCoreSessionController.Status.NeedsInstall,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD to ArCoreSessionController.Status.NeedsInstall,
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE to ArCoreSessionController.Status.Unsupported,
        )
        for ((availability, status) in expected) {
            val platform = FakePlatform(availability = { availability })
            assertEquals(availability.name, status, controller(platform, consent = { true }).checkAvailability())
            assertEquals(1, platform.availabilityCalls)
        }
    }

    @Test
    fun `a transient availability is retried a bounded number of times and then reported as checking`() = runBlocking {
        val platform = FakePlatform(availability = { ArCoreApk.Availability.UNKNOWN_CHECKING })
        val controller = controller(platform, consent = { true })

        assertEquals(ArCoreSessionController.Status.Checking, controller.checkAvailability())

        // One probe, then three retries.
        assertEquals(4, platform.availabilityCalls)
    }

    @Test
    fun `a transient availability that settles is reported as its final answer`() = runBlocking {
        var calls = 0
        val platform = FakePlatform(
            availability = {
                if (calls++ < 2) ArCoreApk.Availability.UNKNOWN_CHECKING else ArCoreApk.Availability.SUPPORTED_INSTALLED
            },
        )

        assertEquals(
            ArCoreSessionController.Status.Ready,
            controller(platform, consent = { true }).checkAvailability(),
        )
    }

    // ---- Install: only from a user action, and only with consent ----

    @Test
    fun `resuming never requests an install`() = runBlocking {
        val platform = FakePlatform(availability = { ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED })

        val status = controller(platform, consent = { true }).resume()

        assertEquals(ArCoreSessionController.Status.NeedsInstall, status)
        assertEquals("an install is a user action", 0, platform.installCalls)
        assertEquals(0, platform.createCalls)
        assertEquals(0, ArCoreSessionController.installRequestCount)
    }

    @Test
    fun `an install requested from a user action is passed on and counted`() = runBlocking {
        val platform = FakePlatform(install = { ArCoreApk.InstallStatus.INSTALL_REQUESTED })
        val controller = controller(platform, consent = { true })

        assertEquals(ArCoreSessionController.Status.NeedsInstall, controller.requestInstallFromUserAction(activity()))

        assertEquals(1, platform.installCalls)
        assertEquals(1, ArCoreSessionController.installRequestCount)
    }

    @Test
    fun `an install that completes immediately makes ARCore ready`() = runBlocking {
        val platform = FakePlatform(install = { ArCoreApk.InstallStatus.INSTALLED })

        assertEquals(
            ArCoreSessionController.Status.Ready,
            controller(platform, consent = { true }).requestInstallFromUserAction(activity()),
        )
    }

    @Test
    fun `a declined install is a failure of its own, not an unsupported device`() = runBlocking {
        val platform = FakePlatform(install = { throw UnavailableUserDeclinedInstallationException() })

        assertEquals(
            ArCoreSessionController.Status.Failed(ArCoreSessionController.Reason.INSTALL_DECLINED),
            controller(platform, consent = { true }).requestInstallFromUserAction(activity()),
        )
    }

    // ---- Session creation ----

    @Test
    fun `a device that cannot run ARCore is reported unsupported after exactly one attempt`() = runBlocking {
        val platform = FakePlatform(create = { throw UnavailableDeviceNotCompatibleException() })
        val controller = controller(platform, consent = { true })

        assertEquals(ArCoreSessionController.Status.Unsupported, controller.resume())

        assertEquals(1, platform.createCalls)
        assertEquals(1, ArCoreSessionController.sessionCreationAttempts)
        assertNull(controller.session)
    }

    @Test
    fun `an ARCore that disappeared between the check and the session asks for an install`() = runBlocking {
        val platform = FakePlatform(create = { throw UnavailableArcoreNotInstalledException() })

        assertEquals(
            ArCoreSessionController.Status.NeedsInstall,
            controller(platform, consent = { true }).resume(),
        )
    }

    @Test
    fun `no session is attempted when availability is not installed`() = runBlocking {
        for (availability in listOf(
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE,
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
            ArCoreApk.Availability.UNKNOWN_CHECKING,
        )) {
            val platform = FakePlatform(availability = { availability })
            controller(platform, consent = { true }).resume()
            assertEquals(availability.name, 0, platform.createCalls)
        }
    }

    @Test
    fun `consent withdrawn right before construction wins over a completed availability check`() = runBlocking {
        // Reads: resume entry, checkAvailability entry, then the one directly in front of the
        // constructor, which is the one that must refuse.
        var reads = 0
        val platform = FakePlatform()
        val controller = controller(platform, consent = { ++reads <= 2 })

        assertEquals(ArCoreSessionController.Status.NotConsented, controller.resume())

        assertEquals(0, platform.createCalls)
        assertEquals(0, ArCoreSessionController.sessionCreationAttempts)
    }

    @Test
    fun `Geospatial consent is not even read when ARCore consent is missing`() = runBlocking {
        var geospatialReads = 0
        val platform = FakePlatform()
        val controller = controller(platform, consent = { false }, geospatial = { geospatialReads++; true })

        controller.resume()

        assertEquals(0, geospatialReads)
    }

    @Test
    fun `close is idempotent and leaves no session behind`() = runBlocking {
        val controller = controller(FakePlatform(), consent = { true })

        controller.close()
        controller.close()
        controller.pause()

        assertNull(controller.session)
    }
}
