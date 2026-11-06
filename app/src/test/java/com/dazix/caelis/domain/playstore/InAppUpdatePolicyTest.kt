package com.dazix.caelis.domain.playstore

import org.junit.Assert.assertEquals
import org.junit.Test

class InAppUpdatePolicyTest {

    private fun available(versionCode: Int = 42, flexible: Boolean = true) = UpdateCheck(
        availability = UpdateAvailability.AVAILABLE,
        install = UpdateInstall.NONE,
        availableVersionCode = versionCode,
        flexibleAllowed = flexible,
    )

    @Test
    fun `a newer version never offered before is offered`() {
        assertEquals(UpdateAction.Offer(42), InAppUpdatePolicy.decide(available(), null, sessionActive = false))
    }

    @Test
    fun `a version already offered is not offered again`() {
        assertEquals(UpdateAction.None, InAppUpdatePolicy.decide(available(42), 42, sessionActive = false))
    }

    @Test
    fun `the next version after a declined one is offered`() {
        assertEquals(UpdateAction.Offer(43), InAppUpdatePolicy.decide(available(43), 42, sessionActive = false))
    }

    @Test
    fun `an older version than the one offered is not offered`() {
        // A rollback of the track must not reopen the question for a version below the one asked about.
        assertEquals(UpdateAction.None, InAppUpdatePolicy.decide(available(41), 42, sessionActive = false))
    }

    @Test
    fun `no answer from Play offers nothing`() {
        assertEquals(UpdateAction.None, InAppUpdatePolicy.decide(null, null, sessionActive = false))
    }

    @Test
    fun `nothing is offered while a session is active`() {
        assertEquals(UpdateAction.None, InAppUpdatePolicy.decide(available(), null, sessionActive = true))
        val downloaded = available().copy(install = UpdateInstall.DOWNLOADED)
        // The restart would end the session running in this process.
        assertEquals(UpdateAction.None, InAppUpdatePolicy.decide(downloaded, 42, sessionActive = true))
    }

    @Test
    fun `a downloaded update asks for the restart even after its version was offered`() {
        val downloaded = UpdateCheck(UpdateAvailability.UNKNOWN, UpdateInstall.DOWNLOADED, 42, flexibleAllowed = false)
        assertEquals(UpdateAction.OfferRestart, InAppUpdatePolicy.decide(downloaded, 42, sessionActive = false))
    }

    @Test
    fun `an update Play does not allow as flexible is not offered`() {
        assertEquals(UpdateAction.None, InAppUpdatePolicy.decide(available(flexible = false), null, sessionActive = false))
    }

    @Test
    fun `no update, an update in progress or an unknown state offer nothing`() {
        for (availability in listOf(
            UpdateAvailability.NOT_AVAILABLE,
            UpdateAvailability.IN_PROGRESS,
            UpdateAvailability.UNKNOWN,
        )) {
            val check = available().copy(availability = availability)
            assertEquals(availability.name, UpdateAction.None, InAppUpdatePolicy.decide(check, null, sessionActive = false))
        }
    }

    @Test
    fun `a download still running offers nothing yet`() {
        for (install in listOf(UpdateInstall.PENDING, UpdateInstall.DOWNLOADING, UpdateInstall.INSTALLING)) {
            val check = available().copy(availability = UpdateAvailability.UNKNOWN, install = install)
            assertEquals(install.name, UpdateAction.None, InAppUpdatePolicy.decide(check, 42, sessionActive = false))
        }
    }
}
