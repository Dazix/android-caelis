package com.dazix.caelis.docs

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.dazix.caelis.domain.SessionSnapshot
import com.dazix.caelis.domain.SessionState
import com.dazix.caelis.ui.AppNavigationShell
import com.dazix.caelis.ui.CaelisTheme
import com.dazix.caelis.ui.HomeScreen
import com.dazix.caelis.ui.ModeChoice
import com.dazix.caelis.ui.RampScreen
import com.dazix.caelis.ui.Screen
import com.dazix.caelis.ui.SetupScreen
import com.dazix.caelis.ui.SetupUiState
import com.dazix.caelis.ui.TimeUnitChoice
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/**
 * The app screens inside the Play listing's phone screenshots, rendered in the listing's own
 * language. They used to be the English captures from [DocumentationScreenshotsTest] for every
 * listing, so a Czech or German visitor saw an English app.
 *
 * Written only when recording (`./gradlew :app:recordRoborazziDebug --tests '*StoreListingScreenshotsTest*'`)
 * into `design/play-store/raw/<language>/`, where `design/play-store/phone-screenshot.html` frames them.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-420dpi", sdk = [34])
class StoreListingScreenshotsTest(private val language: String) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun languages() = listOf("en", "cs", "sk", "pl", "de")
    }

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var previousLocale: Locale

    @Before
    fun setLanguage() {
        previousLocale = Locale.getDefault()
        // Number formats ("3,4 s") follow the default locale, the strings follow the qualifier.
        Locale.setDefault(Locale.forLanguageTag(language))
        RuntimeEnvironment.setQualifiers("+$language")
    }

    @After
    fun restoreLanguage() {
        Locale.setDefault(previousLocale)
    }

    private fun shotPath(fileName: String): String {
        val rootDir = if (File("design").exists()) File(".") else File("..")
        val targetDir = File(rootDir, "design/play-store/raw/$language").apply { mkdirs() }
        return File(targetDir, fileName).absolutePath
    }

    private val setup = SetupUiState(
        mode = ModeChoice.TIMELAPSE,
        targetPackage = "com.google.android.GoogleCamera",
        targetLabel = "Pixel Camera",
        intervalValue = "5",
        intervalUnit = TimeUnitChoice.SECONDS,
        shotCount = "120",
        unlimitedShots = false,
        startDelayValue = "3",
        startDelayUnit = TimeUnitChoice.SECONDS,
    )

    @Test
    fun timeLapseRunning() {
        composeTestRule.setContent {
            CaelisTheme(darkTheme = true) {
                AppNavigationShell(current = Screen.HOME, onNavigate = {}, enabled = true) {
                    HomeScreen(
                        snapshot = SessionSnapshot(
                            state = SessionState.Running,
                            shotsTaken = 24,
                            shotsPlanned = 120,
                            lastIntervalMs = 5000L,
                            nextShotAtElapsed = 3500L,
                        ),
                        setup = setup,
                        calibrated = true,
                        blockers = emptyList(),
                        serviceUnavailable = false,
                        handheldWarning = true,
                        onStart = {},
                        onStop = {},
                        onPause = {},
                        onResume = {},
                        onNavigate = {},
                        onCalibrate = {},
                        onOpenAccessibilitySettings = {},
                        onOpenOverlaySettings = {},
                        onDismissServiceMessage = {},
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(shotPath("time-lapse.png"))
    }

    @Test
    fun ramp() {
        composeTestRule.setContent {
            CaelisTheme(darkTheme = true) {
                RampScreen(
                    state = setup.copy(
                        rampEnabled = true,
                        rampEndValue = "30",
                        rampEndUnit = TimeUnitChoice.SECONDS,
                        rampOverShots = "100",
                    ),
                    floorMs = 1000L,
                    onChange = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(shotPath("ramp.png"))
    }

    @Test
    fun setup() {
        composeTestRule.setContent {
            CaelisTheme(darkTheme = true) {
                SetupScreen(
                    state = setup.copy(
                        minBatteryEnabled = true,
                        minBatteryPercent = "20",
                        minFreeSpaceEnabled = true,
                        minFreeSpaceMb = "1000",
                    ),
                    floorMs = 1000L,
                    mediaPermissionGranted = true,
                    mediaPermissionPartial = false,
                    onChange = {},
                    onJitterChange = {},
                    onRequestMediaPermission = {},
                    onOpenAppSettings = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(shotPath("setup.png"))
    }
}
