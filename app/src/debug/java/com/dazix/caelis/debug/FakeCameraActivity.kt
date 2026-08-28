package com.dazix.caelis.debug

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.dazix.caelis.R
import com.dazix.caelis.domain.TapTarget

/**
 * Debug-only stand-in for a real camera app.
 *
 * It exists so the tap pipeline can be measured without a camera in the loop: the node search,
 * the accessibility click, the gesture dispatch and the coordinate fallback all run against a
 * surface whose taps are timestamped to a file we can read from a test.
 *
 * Two render modes, chosen per launch:
 *  - default: a shutter-like button pinned bottom centre whose accessibility node carries
 *    `viewIdResourceName = "<pkg>:id/shutter_button"` and `contentDescription = "Take photo"`,
 *    so the service exercises exactly the same node-search path it uses on a real camera app;
 *  - [EXTRA_UNLABELED] `= true`: a full-screen custom view with no id, no content description and
 *    no accessibility presence at all, so the service finds nothing and has to fall back to
 *    tapping the configured [TapTarget] coordinates.
 *
 * Every tap appends one `elapsedRealtime,source` line to `filesDir/fake_camera_taps.csv`
 * (see [FakeCameraTapLog]). Pass [EXTRA_CLEAR] `= true` to truncate the log during `onCreate` /
 * `onNewIntent` so each test starts from zero.
 */
class FakeCameraActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private var taps = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTypeface(Typeface.MONOSPACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val pad = dp(16f)
            setPadding(pad, pad * 3, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            )
        }
        root.addView(status)

        setContentView(root)
        // After setContentView: the window's insets controller lives on the decor view, and asking for
        // it before that view exists throws inside PhoneWindow.
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Applies the launch extras. Runs from both [onCreate] and [onNewIntent] so a re-launch of the
     * already-running activity can clear the log and swap render mode without a restart.
     */
    private fun handleIntent(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_CLEAR, false)) {
            FakeCameraTapLog.clear(this)
            taps = 0
        }
        val unlabeled = intent.getBooleanExtra(EXTRA_UNLABELED, false)
        renderTarget(unlabeled)
        render(unlabeled)
        Log.i(TAG, "fake camera ready (unlabeled=$unlabeled, log=${FakeCameraTapLog.file(this)})")
    }

    /** Rebuilds the tap surface; the status text view is kept so the screen stays readable. */
    private fun renderTarget(unlabeled: Boolean) {
        for (i in root.childCount - 1 downTo 0) {
            val child = root.getChildAt(i)
            if (child !== status) root.removeViewAt(i)
        }
        if (unlabeled) {
            // Nothing in this window may be findable, otherwise the service would never need the
            // coordinate fallback. Hiding the whole tree also hides the status text.
            root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            val surface = UnlabeledCaptureView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                onTap = ::recordTap
            }
            root.addView(surface, 0)
        } else {
            root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            val shutter = ShutterView(this).apply {
                id = R.id.shutter_button
                contentDescription = SHUTTER_CONTENT_DESCRIPTION
                isClickable = true
                isFocusable = true
                layoutParams = FrameLayout.LayoutParams(
                    dp(SHUTTER_SIZE_DP),
                    dp(SHUTTER_SIZE_DP),
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ).apply { bottomMargin = dp(SHUTTER_MARGIN_DP) }
                onTap = ::recordTap
            }
            root.addView(shutter)
        }
    }

    private fun recordTap(source: String) {
        val at = SystemClock.elapsedRealtime()
        taps += 1
        FakeCameraTapLog.append(this, at, source)
        Log.i(TAG, "tap #$taps source=$source at=$at")
        render(intent.getBooleanExtra(EXTRA_UNLABELED, false))
    }

    private fun render(unlabeled: Boolean) {
        status.text = buildString {
            append("FAKE CAMERA")
            append(if (unlabeled) "  [unlabeled / coordinate fallback]" else "  [labeled node]")
            append("\ntaps: ")
            append(taps)
            append("\nlog: ")
            append(FakeCameraTapLog.FILE_NAME)
        }
    }

    override fun onResume() {
        super.onResume()
        current = this
        resumed = true
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (current === this) current = null
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val TAG = "FakeCamera"

        /** Boolean. Truncate the tap log before rendering, so a test starts from zero. */
        const val EXTRA_CLEAR = "clear"

        /** Boolean. Render the unlabeled full-screen variant with no findable node. */
        const val EXTRA_UNLABELED = "unlabeled"

        /** Hard-coded (never localized) so the node search matches byte for byte. */
        const val SHUTTER_CONTENT_DESCRIPTION = "Take photo"

        /** Short id name; the node reports it as `<applicationId>:id/shutter_button`. */
        const val SHUTTER_VIEW_ID_NAME = "shutter_button"

        private const val SHUTTER_SIZE_DP = 96f
        private const val SHUTTER_MARGIN_DP = 40f

        @Volatile
        private var current: FakeCameraActivity? = null

        @Volatile
        private var resumed = false

        /** True between `onResume` and `onPause`; tests poll this instead of sleeping blindly. */
        val isResumed: Boolean get() = resumed

        /** Closes the activity from a test's `@After` without needing UiAutomator back presses. */
        fun finishCurrent() {
            val activity = current ?: return
            Handler(Looper.getMainLooper()).post { activity.finish() }
        }
    }
}
