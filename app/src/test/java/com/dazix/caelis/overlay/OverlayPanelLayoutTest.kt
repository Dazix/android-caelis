package com.dazix.caelis.overlay

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.dazix.caelis.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * The floating panel does not move its controls when the handheld warning comes and goes, and a row
 * lays its cells out where it measured them.
 *
 * On the device the warning appeared above the rows and widened the panel, so Pause and Stop jumped
 * down and every button changed width; and the Czech "Ztlumení: vyp." toggle, wrapped to two lines in
 * the narrower panel, was moved down by the baseline-aligned row and lost the bottom of its pill.
 * The panel here is built the way `OverlayController.buildPanel` builds it, once per language the
 * app ships: the panel is the narrowest place a translation lands, and a longer word than the Czech
 * one wraps the same way.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
// Real text layout: the legacy shadows never wrap a label, and the wrap is what the bug needs.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayPanelLayoutTest(private val language: String) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun languages() = listOf("en", "cs", "sk", "pl", "de").map { arrayOf<Any>(it) }
    }

    // Before anything below reads a string.
    private val context: Context = RuntimeEnvironment.setQualifiers(language)
        .let { ApplicationProvider.getApplicationContext() }

    private fun dp(value: Float) = (value * context.resources.displayMetrics.density).roundToInt()

    /** Styled like the panel's option toggles (see `OverlayController.optionButton`). */
    private fun toggle(label: Int) = Button(context).apply {
        text = context.getString(label)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(48f)
        minimumHeight = dp(48f)
        setPadding(dp(10f), 0, dp(10f), 0)
        isAllCaps = false
    }

    private fun cell(leadingGap: Boolean) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (leadingGap) leftMargin = dp(8f)
        }

    private fun row() =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private val panel = OverlayRootView(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
    }
    private val warning = OverlayRootView.WidthFollowingTextView(context).apply {
        text = context.getString(R.string.session_warning_handheld)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        visibility = View.GONE
    }
    private val toggles = EqualCellRow(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(6f), 0, 0)
        addView(toggle(R.string.ovl_option_screen_on), cell(leadingGap = false))
        addView(toggle(R.string.ovl_option_dim_off), cell(leadingGap = true))
    }

    init {
        panel.addView(TextView(context).apply {
            text = "Start za 0:04"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        })
        panel.addView(toggles, row())
        panel.addView(warning, row())
        panel.widthFollower = warning
    }

    private fun layOut() {
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
    }

    private fun assertCellsInRow(what: String) {
        val first = toggles.getChildAt(0)
        val second = toggles.getChildAt(1)
        assertEquals("$what: cells start at the same height", first.top, second.top)
        for (cell in listOf(first, second)) {
            assertTrue(
                "$what: cell ends inside the row (bottom ${cell.bottom}, row ${toggles.height})",
                cell.bottom <= toggles.height - toggles.paddingBottom,
            )
        }
    }

    @Test
    fun `a wrapped toggle label does not push its neighbour out of the row`() {
        assertEquals("the panel is built in the language under test", language, context.resources.configuration.locales[0].language)
        layOut()
        assertCellsInRow("without the warning")
        warning.visibility = View.VISIBLE
        layOut()
        assertCellsInRow("with the warning")
    }

    @Test
    fun `the warning neither widens the panel nor moves the controls`() {
        layOut()
        val width = panel.width
        val rowTop = toggles.top
        val cellWidth = toggles.getChildAt(1).width

        warning.visibility = View.VISIBLE
        layOut()

        assertEquals("panel width", width, panel.width)
        assertEquals("toggles row position", rowTop, toggles.top)
        assertEquals("toggle width", cellWidth, toggles.getChildAt(1).width)
        assertEquals("the warning spans the panel's content", width - panel.paddingLeft - panel.paddingRight, warning.width)
        assertTrue("the warning is drawn in full (${warning.lineCount} lines)", warning.height >= warning.layout.height)
        assertTrue("the warning sits under the controls", warning.top >= toggles.bottom)

        warning.visibility = View.GONE
        layOut()
        assertEquals("panel width after the warning", width, panel.width)
    }
}
