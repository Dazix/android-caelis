package com.dazix.caelis.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelNavigationTest {

    private lateinit var app: Application
    private lateinit var viewModel: MainViewModel

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        viewModel = MainViewModel(app)
    }

    @Test
    fun `initial screen is HOME`() {
        assertEquals(Screen.HOME, viewModel.screen.value)
    }

    @Test
    fun `navigating to sub-screen and back returns to parent screen`() {
        viewModel.navigate(Screen.SETTINGS)
        assertEquals(Screen.SETTINGS, viewModel.screen.value)

        viewModel.navigate(Screen.OFFLINE_TERRAIN)
        assertEquals(Screen.OFFLINE_TERRAIN, viewModel.screen.value)

        viewModel.back()
        assertEquals(Screen.SETTINGS, viewModel.screen.value)

        viewModel.back()
        assertEquals(Screen.HOME, viewModel.screen.value)
    }

    @Test
    fun `navigating from home to setup and back returns to home`() {
        viewModel.navigate(Screen.SETUP)
        assertEquals(Screen.SETUP, viewModel.screen.value)

        viewModel.back()
        assertEquals(Screen.HOME, viewModel.screen.value)
    }

    @Test
    fun `switching top level destinations resets back stack`() {
        viewModel.navigate(Screen.SETTINGS)
        assertEquals(Screen.SETTINGS, viewModel.screen.value)

        viewModel.navigate(Screen.SUN_AR)
        assertEquals(Screen.SUN_AR, viewModel.screen.value)

        viewModel.back()
        assertEquals(Screen.HOME, viewModel.screen.value)
    }

    @Test
    fun `selecting app in picker returns back to caller screen`() {
        viewModel.navigate(Screen.APP_PICKER)
        assertEquals(Screen.APP_PICKER, viewModel.screen.value)

        viewModel.selectApp(AppEntry("com.example.camera", "Example Camera", true))
        assertEquals(Screen.HOME, viewModel.screen.value)
    }
}
