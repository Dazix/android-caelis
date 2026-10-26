package com.dazix.caelis.ui

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.dazix.caelis.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Google Play's prominent-disclosure rule: the accessibility settings open only after an explicit
 * "Agree" in a dialog that says what the service reads and does. Pressing the button, dismissing the
 * dialog or declining must never get there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityDisclosureTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun text(id: Int) = context.getString(id)

    private var consents = 0

    private fun setUp() {
        composeTestRule.setContent {
            CaelisTheme(darkTheme = true) {
                val open = rememberAccessibilityDisclosure { consents++ }
                Button(onClick = open) { Text(OPEN) }
            }
        }
    }

    @Test
    fun `pressing the button shows the disclosure and opens nothing`() {
        setUp()
        composeTestRule.onNodeWithText(OPEN).performClick()

        composeTestRule.onNodeWithText(text(R.string.a11y_disclosure_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.a11y_disclosure_body)).assertIsDisplayed()
        assertEquals(0, consents)
    }

    @Test
    fun `declining closes the dialog without opening the settings`() {
        setUp()
        composeTestRule.onNodeWithText(OPEN).performClick()
        composeTestRule.onNodeWithText(text(R.string.a11y_disclosure_decline)).performClick()

        composeTestRule.onNodeWithText(text(R.string.a11y_disclosure_title)).assertDoesNotExist()
        assertEquals(0, consents)
    }

    @Test
    fun `only agreeing opens the settings, once per agreement`() {
        setUp()
        composeTestRule.onNodeWithText(OPEN).performClick()
        composeTestRule.onNodeWithText(text(R.string.a11y_disclosure_agree)).performClick()

        assertEquals(1, consents)
        composeTestRule.onNodeWithText(text(R.string.a11y_disclosure_title)).assertDoesNotExist()

        // Asked again on the next press: an earlier answer is not consent to switching it on again.
        composeTestRule.onNodeWithText(OPEN).performClick()
        composeTestRule.onNodeWithText(text(R.string.a11y_disclosure_title)).assertIsDisplayed()
        assertEquals(1, consents)
    }

    private companion object {
        const val OPEN = "open accessibility settings"
    }
}
