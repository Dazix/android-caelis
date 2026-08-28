package com.dazix.caelis.overlay

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.dazix.caelis.R
import com.dazix.caelis.data.SessionRepository
import com.dazix.caelis.data.SettingsStore
import com.dazix.caelis.domain.SessionSnapshot
import com.dazix.caelis.domain.SessionState
import com.dazix.caelis.domain.TapTarget
import com.dazix.caelis.service.DisplayGeometry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Compact draggable panel drawn on top of the camera app: shots taken, countdown to the next shot,
 * pause/resume, stop, and the two session-comfort toggles (keep the screen on, dim the panel).
 *
 * Deliberately plain Android views. A `ComposeView` in a `WindowManager` window needs a hand-rolled
 * lifecycle/savedstate owner and a recomposer tied to it; for a handful of TextViews and Buttons that
 * is more failure surface than value.
 *
 * ### Why the panel never eats a shutter tap
 * `AccessibilityService.dispatchGesture` injects at input-dispatcher level, so a synthetic tap goes
 * to whatever window owns those pixels — this panel included, up to and including its own Pause/Stop
 * buttons. Two independent mechanisms prevent that:
 *
 *  1. **Placement (the guarantee).** [PanelPlacement] never *puts* the panel over a pixel a shutter
 *     tap can land on: the calibrated target inflated by [AVOID_MARGIN_DP], and the bottom-centre
 *     region the dispatcher's node-search heuristic falls back to. This involves no timing at all, so
 *     there is nothing to race — the window position is only ever set to a non-overlapping value, in
 *     the same `updateViewLayout` call that would have moved it.
 *  2. **Cloaking (defence in depth).** A shutter node can legitimately sit outside both regions (a
 *     landscape camera puts its shutter on the right edge), so around every predicted shot the panel
 *     also drops `FLAG_NOT_TOUCHABLE` and lets touches fall through to the camera — armed by a timer
 *     [OverlayPolicy.CLOAK_LEAD_MS] before the shot rather than by the one-second render tick.
 *
 * Threading: every public method hops to the main thread itself, so the service may call them from
 * its own handler thread. Rendering is throttled to at most one pass per second (leading edge, with
 * a trailing pass so the last state always lands).
 */
class OverlayController(context: Context) {

    private val appContext: Context = context.applicationContext
    private val windowManager: WindowManager =
        appContext.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val settings = SettingsStore(appContext)

    /** Invoked on the main thread when the user taps pause/resume. The service owns the decision. */
    var onPauseResume: (() -> Unit)? = null

    /** Invoked on the main thread when the user taps stop. */
    var onStop: (() -> Unit)? = null

    private var root: OverlayRootView? = null
    private var params: WindowManager.LayoutParams? = null
    private var statusView: TextView? = null
    private var countdownView: TextView? = null
    private var warningView: TextView? = null
    private var pauseButton: Button? = null
    private var screenButton: Button? = null
    private var dimButton: Button? = null
    private var controlsRow: LinearLayout? = null
    private var togglesRow: LinearLayout? = null

    private var scope: CoroutineScope? = null
    private var collectJob: Job? = null
    private var optionsJob: Job? = null
    private var calibrationJob: Job? = null

    private var latest: SessionSnapshot = SessionRepository.snapshot.value
    private var options: OverlayOptions = OverlayOptions()
    private var lastRenderAtUptime = 0L
    private var renderScheduled = false

    /** Regions a dispatched shutter tap can land in; the panel is kept out of all of them. */
    private var avoid: List<Box> = emptyList()
    private var calibrated: TapTarget? = null

    /** True while the panel is passing touches through so a synthetic tap cannot be swallowed. */
    private var cloaked = false

    /** The cadence the cloak window is sized against; see [OverlayPolicy.Cadence]. */
    private val cadence = OverlayPolicy.Cadence()

    /** True while the app's own UI is in front, where the panel would only occlude its own screens. */
    private var ownUiInFront = false

    /** Registered while the panel is up; the second source for [ownUiInFront]. */
    private var activityWatcher: Application.ActivityLifecycleCallbacks? = null

    /** Full opacity until this uptime, then back to [DIM_ALPHA] when dimming is on. */
    private var awakeUntilUptime = 0L

    val isShowing: Boolean get() = root != null

    /**
     * Adds the window if it is not already up. No-ops (and parks a warning in the snapshot) when the
     * draw-over-other-apps permission is missing. Safe to call repeatedly.
     */
    fun show() {
        onMain {
            if (root != null) return@onMain
            if (!Settings.canDrawOverlays(appContext)) {
                val message = appContext.getString(R.string.ovl_permission_missing)
                SessionRepository.update { it.copy(warning = message) }
                return@onMain
            }
            options = OverlaySettings.current(appContext)
            latest = SessionRepository.snapshot.value
            cloaked = OverlayPolicy.isCloaked(
                latest,
                SystemClock.elapsedRealtime(),
                cadence.observe(latest),
            )
            awakeUntilUptime = SystemClock.uptimeMillis() + WAKE_MS
            val view = buildPanel()
            val layout = buildLayoutParams()
            root = view
            params = layout
            val added = runCatching { windowManager.addView(view, layout) }.isSuccess
            if (!added) {
                root = null
                params = null
                val message = appContext.getString(R.string.ovl_permission_missing)
                SessionRepository.update { it.copy(warning = message) }
                return@onMain
            }
            // Drop our own stale complaint if the permission was granted in the meantime.
            val permissionWarning = appContext.getString(R.string.ovl_permission_missing)
            SessionRepository.update {
                if (it.warning == permissionWarning) it.copy(warning = null) else it
            }
            recomputeAvoidRegions()
            startObserving()
            watchOwnActivities()
            // A session can be started from the app's own screen, in which case the panel must come up
            // already hidden. The flag is remembered even while no window exists, so whatever the last
            // report said still applies to a window created afterwards.
            applyOwnUiSuppression(ownUiInFront)
            renderNow()
        }
    }

    /**
     * Told by the service on every window-state change which package is in front.
     *
     * The panel hides itself while that is *our own* app: over our own screens it occludes the very
     * cards it floats on, and every control it offers is on those screens already, full size. The
     * window itself stays registered, so nothing has to be re-added — and no permission is re-checked —
     * when the user goes back to the app being driven.
     */
    fun onForegroundPackageChanged(packageName: String?) {
        onMain {
            applyOwnUiSuppression(
                OverlayPolicy.suppressForOwnUi(packageName, appContext.packageName)
            )
        }
    }

    /**
     * Applies (and remembers) whether the app's own UI is in front. Main thread only.
     *
     * Written so the remembered flag and the window can never drift apart: the visibility is re-applied
     * whenever it does not already match, not only when the flag flips. An earlier version latched the
     * flag before checking that a window existed, and then returned early forever — a periodic-tap
     * session started from the app's own screen kept the panel on top of that screen for its whole run,
     * because no later event ever named a different package.
     */
    private fun applyOwnUiSuppression(suppress: Boolean) {
        val view = root
        if (view == null) {
            ownUiInFront = suppress
            return
        }
        val wanted = if (suppress) View.GONE else View.VISIBLE
        val unchanged = suppress == ownUiInFront && view.visibility == wanted
        ownUiInFront = suppress
        if (unchanged) return
        view.visibility = wanted
        if (!suppress) {
            wake()
            renderNow()
        }
    }

    /**
     * Second, independent source for "is our own UI in front": our own activities' lifecycle.
     *
     * The accessibility events the service forwards are the only signal that can see *other* apps, but
     * they are also the weaker one — they arrive for our own overlay windows too, they can be missed,
     * and none arrives at all while the app is already open. Our own process knows exactly when one of
     * our activities is resumed, so both signals feed the same decision and either one is enough.
     */
    private fun watchOwnActivities() {
        val application = appContext as? Application ?: return
        if (activityWatcher != null) return
        var resumed = 0
        val watcher = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed++
                applyOwnUiSuppression(true)
            }

            override fun onActivityPaused(activity: Activity) {
                resumed = (resumed - 1).coerceAtLeast(0)
                if (resumed == 0) applyOwnUiSuppression(false)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        activityWatcher = watcher
        application.registerActivityLifecycleCallbacks(watcher)
    }

    /** Removes the window and stops all observation. Safe to call when nothing is showing. */
    fun hide() {
        onMain {
            main.removeCallbacks(renderRunnable)
            main.removeCallbacks(cloakRunnable)
            main.removeCallbacks(dimRunnable)
            renderScheduled = false
            collectJob?.cancel()
            collectJob = null
            optionsJob?.cancel()
            optionsJob = null
            calibrationJob?.cancel()
            calibrationJob = null
            scope?.cancel()
            scope = null
            val view = root
            root = null
            params = null
            statusView = null
            countdownView = null
            warningView = null
            pauseButton = null
            screenButton = null
            dimButton = null
            controlsRow = null
            togglesRow = null
            cloaked = false
            cadence.reset()
            // ownUiInFront is deliberately *not* reset: which app is in front is a fact about the
            // device, not about this window. Clearing it here meant a stop-and-start from the app's own
            // screen re-added the panel visible, on top of the very cards it is supposed to stay off.
            activityWatcher?.let { watcher ->
                (appContext as? Application)?.unregisterActivityLifecycleCallbacks(watcher)
            }
            activityWatcher = null
            if (view != null) {
                view.onConfigChanged = null
                view.onSizeChanged = null
                view.onWakeTouch = null
                runCatching { windowManager.removeViewImmediate(view) }
            }
        }
    }

    // ---------------------------------------------------------------- observation and rendering

    private fun startObserving() {
        val created = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = created
        collectJob = created.launch {
            SessionRepository.snapshot.collectLatest { snapshot ->
                latest = snapshot
                applyCloak()
                scheduleRender()
            }
        }
        optionsJob = created.launch {
            OverlaySettings.options(appContext).collectLatest { updated ->
                options = updated
                awakeUntilUptime = SystemClock.uptimeMillis() + WAKE_MS
                scheduleRender()
                refreshWindow()
            }
        }
        // The calibration a session runs with decides the region the panel has to stay out of.
        calibrationJob = created.launch {
            settings.lastConfig.collectLatest { config ->
                calibrated = config?.target ?: config?.mode?.targetPackage?.let { storedTarget(it) }
                recomputeAvoidRegions()
            }
        }
        // The countdown has to tick even when the snapshot does not change.
        main.postDelayed(renderRunnable, TICK_MS)
    }

    /** Mirrors how the session engine resolves a stored calibration: this rotation, else natural. */
    private suspend fun storedTarget(packageName: String): TapTarget? {
        val rotation = DisplayGeometry.currentRotation(appContext)
        // The same read the session and the setup screen use: any stored slot counts, because the
        // fractions are rotation-independent. Reading only two slots here would leave the panel free to
        // sit exactly on a shutter the session *will* tap, for a calibration captured in another
        // rotation — the avoid regions are computed from this.
        return runCatching { settings.anyTarget(packageName, rotation) }.getOrNull()
    }

    private val renderRunnable: Runnable = Runnable {
        renderScheduled = false
        if (root == null) return@Runnable
        renderNow()
        main.postDelayed(renderRunnable, TICK_MS)
    }

    private fun scheduleRender() {
        if (root == null || renderScheduled) return
        val waited = SystemClock.uptimeMillis() - lastRenderAtUptime
        if (waited >= TICK_MS) {
            main.removeCallbacks(renderRunnable)
            renderRunnable.run()
        } else {
            renderScheduled = true
            main.removeCallbacks(renderRunnable)
            main.postDelayed(renderRunnable, TICK_MS - waited)
        }
    }

    private fun renderNow() {
        lastRenderAtUptime = SystemClock.uptimeMillis()
        val snapshot = latest
        val status = statusView ?: return
        val countdown = countdownView ?: return
        val warning = warningView ?: return
        val pause = pauseButton ?: return

        val planned = snapshot.shotsPlanned
        status.text = if (planned != null && planned > 0) {
            appContext.resources.getQuantityString(
                R.plurals.ovl_shots_of, planned, snapshot.shotsTaken, planned,
            )
        } else {
            appContext.resources.getQuantityString(
                R.plurals.ovl_shots, snapshot.shotsTaken, snapshot.shotsTaken,
            )
        }

        countdown.text = countdownTextFor(snapshot)

        val warningText = snapshot.warning
        if (warningText.isNullOrBlank()) {
            warning.visibility = View.GONE
        } else {
            warning.text = warningText
            warning.visibility = View.VISIBLE
        }

        val paused = snapshot.state is SessionState.Paused
        pause.text = appContext.getString(
            if (paused) R.string.ovl_action_resume else R.string.ovl_action_pause
        )
        renderOptionButtons()

        applyCloak()
        refreshWindow()
    }

    private fun renderOptionButtons() {
        screenButton?.let { button ->
            button.text = appContext.getString(
                if (options.keepScreenOn) R.string.ovl_option_screen_on else R.string.ovl_option_screen_off
            )
            button.contentDescription = appContext.getString(R.string.ovl_option_screen_description)
        }
        dimButton?.let { button ->
            button.text = appContext.getString(
                if (options.dimPanel) R.string.ovl_option_dim_on else R.string.ovl_option_dim_off
            )
            button.contentDescription = appContext.getString(R.string.ovl_option_dim_description)
        }
    }

    private fun countdownTextFor(snapshot: SessionSnapshot): String {
        val now = SystemClock.elapsedRealtime()
        return when (val state = snapshot.state) {
            is SessionState.Paused -> appContext.getString(R.string.ovl_paused)
            is SessionState.Idle -> appContext.getString(R.string.ovl_idle)
            is SessionState.Stopped -> appContext.getString(R.string.ovl_stopped)
            is SessionState.WaitingToStart ->
                appContext.getString(R.string.ovl_starts_in, formatCountdown(state.startsAtElapsed - now))
            is SessionState.Running -> {
                val next = snapshot.nextShotAtElapsed
                if (next == null || next - now <= 0L) {
                    appContext.getString(R.string.ovl_shooting_now)
                } else {
                    appContext.getString(R.string.ovl_next_in, formatCountdown(next - now))
                }
            }
        }
    }

    // ------------------------------------------------------------------------------- view setup

    private fun buildPanel(): OverlayRootView {
        val themed = android.view.ContextThemeWrapper(
            appContext,
            android.R.style.Theme_DeviceDefault
        )
        val pad = dp(themed, 12f)
        val palette = OverlayPalette.of(appContext)

        val panel = OverlayRootView(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(themed, PANEL_CORNER_DP).toFloat()
                setColor(OverlayPalette.withAlpha(palette.surface, OverlayPalette.SURFACE_ALPHA))
                setStroke(
                    dp(themed, 1f),
                    OverlayPalette.withAlpha(palette.stroke, OverlayPalette.STROKE_ALPHA),
                )
            }
            elevation = dp(themed, 6f).toFloat()
            contentDescription = themed.getString(R.string.ovl_action_drag_handle)
        }

        val shots = TextView(themed).apply {
            setTextColor(palette.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            alpha = 0.85f
        }
        // The countdown is what the panel exists for and it is read from wherever the phone is propped
        // up, so it is the largest thing here by a wide margin. Written out in sp and bold rather than
        // read off the app's type scale: this is a plain View with no Compose theme to ask, and the
        // expressive display step it imitates (weight and tighter tracking, never a different size) is
        // exactly what those two lines say.
        val countdown = TextView(themed).apply {
            setTextColor(palette.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD,
            )
            letterSpacing = -0.01f
        }
        val warning = TextView(themed).apply {
            setTextColor(palette.warning)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            visibility = View.GONE
            maxWidth = dp(themed, 200f)
        }
        val buttons = EqualCellRow(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(themed, ROW_GAP_DP), 0, 0)
        }
        val pause = Button(themed).apply {
            text = themed.getString(R.string.ovl_action_pause)
            minWidth = dp(themed, 96f)
            minHeight = dp(themed, TOUCH_TARGET_DP)
            minimumHeight = dp(themed, TOUCH_TARGET_DP)
            tonalPill(themed, palette.buttonContainer, palette.onButtonContainer)
            setOnClickListener { onPauseResume?.invoke() }
        }
        val stop = Button(themed).apply {
            text = themed.getString(R.string.ovl_action_stop)
            minWidth = dp(themed, 88f)
            minHeight = dp(themed, TOUCH_TARGET_DP)
            minimumHeight = dp(themed, TOUCH_TARGET_DP)
            // Ending a series is the one destructive action on the panel, and it carries the error
            // container for it — the same distinction the app's own Stop button makes.
            tonalPill(themed, palette.stopContainer, palette.onStopContainer)
            setOnClickListener { onStop?.invoke() }
        }
        // Both buttons carry weight so the row fills the panel and its two halves are the same
        // width: Pause and Stop used to be sized by their own labels, which left the row narrower
        // than the toggles under it and the two rows visibly out of line at both edges.
        buttons.addView(pause, rowCell(themed))
        buttons.addView(stop, rowCell(themed, leadingGap = true))

        val toggles = EqualCellRow(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(themed, ROW_GAP_DP), 0, 0)
        }
        val screen = optionButton(themed) {
            OverlaySettings.setKeepScreenOn(appContext, !options.keepScreenOn)
        }
        val dim = optionButton(themed) {
            OverlaySettings.setDimPanel(appContext, !options.dimPanel)
        }
        toggles.addView(screen, rowCell(themed))
        toggles.addView(dim, rowCell(themed, leadingGap = true))

        panel.addView(shots)
        panel.addView(countdown)
        panel.addView(warning)
        // The rows span the panel rather than their own content, which is what lets the cells inside
        // them share that width equally and keeps the two rows flush with each other.
        panel.addView(buttons, rowParams())
        panel.addView(toggles, rowParams())

        statusView = shots
        controlsRow = buttons
        togglesRow = toggles
        countdownView = countdown
        warningView = warning
        pauseButton = pause
        screenButton = screen
        dimButton = dim
        renderOptionButtons()

        attachDragHandling(panel)
        panel.onConfigChanged = {
            recomputeAvoidRegions()
            refreshWindow()
        }
        panel.onSizeChanged = { refreshWindow() }
        // While dimmed the first touch only wakes the panel, so a half-visible Stop cannot be hit by
        // accident. Handled at dispatch level because the buttons would otherwise consume it first.
        panel.onWakeTouch = {
            val dimmed = options.dimPanel && SystemClock.uptimeMillis() >= awakeUntilUptime
            wake()
            dimmed
        }
        return panel
    }

    /**
     * Small, quiet toggle: this is a session comfort switch, not a primary action. Quiet in weight and
     * opacity only — the touch target is the same 48dp minimum as everything else, because a control
     * that is hard to hit is not a quiet control, it is a broken one.
     */
    /**
     * The pill shape and tonal colouring the app's buttons have, applied to a platform [Button].
     *
     * Written out rather than themed: the panel's context is `Theme.DeviceDefault`, which has no
     * Material 3 button style to inherit, and a `ComposeView` here would need its own lifecycle owner
     * and recomposer (see the class comment). Fully rounded, because in the expressive shape language a
     * button this small is a pill.
     */
    private fun Button.tonalPill(themed: Context, container: Int, onContainer: Int) {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(themed, BUTTON_CORNER_DP).toFloat()
            setColor(container)
        }
        setTextColor(onContainer)
        isAllCaps = false
        stateListAnimator = null
    }

    /** A row of the panel: as wide as the panel, so its cells can divide that width. */
    private fun rowParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

    /**
     * One button in a row: zero intrinsic width and equal weight, so the two buttons of a row always
     * split it evenly and the rows line up with each other whatever their labels say — including in
     * Czech, where "Pozastavit" and "Pokračovat" are far from the same length as "Stop".
     */
    private fun rowCell(themed: Context, leadingGap: Boolean = false): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (leadingGap) leftMargin = dp(themed, BUTTON_GAP_DP)
        }

    private fun optionButton(themed: Context, onToggle: () -> Unit): Button =
        Button(themed).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(themed, TOUCH_TARGET_DP)
            minimumHeight = dp(themed, TOUCH_TARGET_DP)
            val h = dp(themed, 10f)
            setPadding(h, 0, h, 0)
            val palette = OverlayPalette.of(appContext)
            // Quieter than the primary pair: an outline-weight pill, so a comfort toggle never competes
            // with Pause and Stop for attention.
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(themed, BUTTON_CORNER_DP).toFloat()
                setColor(OverlayPalette.withAlpha(palette.surface, 0x00))
                setStroke(dp(themed, 1f), OverlayPalette.withAlpha(palette.stroke, 0x99))
            }
            setTextColor(palette.onSurface)
            isAllCaps = false
            stateListAnimator = null
            alpha = 0.85f
            setOnClickListener {
                onToggle()
                // Do not wait for the settings flow to come back around: this is a button press.
                options = OverlaySettings.current(appContext)
                renderOptionButtons()
                refreshWindow()
            }
        }

    private fun buildLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE keeps the camera app interactive and keys flowing to it. KEEP_SCREEN_ON
            // and NOT_TOUCHABLE are state-dependent and live in computeFlags().
            //
            // LAYOUT_IN_SCREEN + NO_LIMITS, and no fitted insets, for one reason: this window's x/y are
            // computed by PanelPlacement in *raw display pixels* — the same space DisplayGeometry
            // resolves the calibrated tap target in, and the same space a dispatched gesture lands in.
            // Without these flags the window frame starts below the status bar and ends above the
            // navigation bar, so every position is silently offset by the top inset: the panel dragged
            // to the bottom has its buttons clipped off the frame, and each avoid box sits a status bar
            // higher than the pixels it is protecting — which would break the guarantee in the class
            // comment above rather than just look wrong.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = wantX()
            y = wantY()
            flags = computeFlags()
            alpha = computeAlpha()
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        }

    /** The position the user last dragged the panel to, or the default corner on a first run. */
    private fun wantX(): Int = desiredX ?: dp(appContext, 16f).also { desiredX = it }

    private fun wantY(): Int = desiredY ?: dp(appContext, 96f).also { desiredY = it }

    private fun attachDragHandling(panel: View) {
        val slop = ViewConfiguration.get(appContext).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        panel.setOnTouchListener { _, event ->
            if (params == null) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = wantX()
                    startY = wantY()
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        // The *desired* position follows the finger; the applied one is whatever
                        // PanelPlacement allows, so the panel is repelled from the shutter live
                        // instead of only being corrected when the finger lifts.
                        desiredX = startX + dx.roundToInt()
                        desiredY = startY + dy.roundToInt()
                        wake()
                        refreshWindow()
                    }
                    dragging
                }

                MotionEvent.ACTION_UP -> {
                    // A press that never became a drag is a click. Routing it through performClick is
                    // what makes the panel reachable by anything that is not a finger — a screen
                    // reader's activate, Switch Access, a keyboard — instead of silently doing nothing.
                    if (!dragging) panel.performClick()
                    dragging
                }

                MotionEvent.ACTION_CANCEL -> dragging

                else -> false
            }
        }
        // Clicking the panel only wakes it; the wake is what a dimmed panel needs before it can be read.
        panel.setOnClickListener { wake() }
        panel.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: AccessibilityNodeInfo,
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                // Dragging is a gesture nobody using a screen reader or Switch Access can perform, and
                // the panel's own description talks about it. This is the same affordance as an action:
                // jump the panel to the opposite corner, which is all a drag is ever used for.
                info.addAction(
                    AccessibilityNodeInfo.AccessibilityAction(
                        ACTION_MOVE_PANEL_ID,
                        appContext.getString(R.string.ovl_action_move_panel),
                    )
                )
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if (action == ACTION_MOVE_PANEL_ID) {
                    movePanelToOppositeCorner()
                    return true
                }
                return super.performAccessibilityAction(host, action, args)
            }
        }
    }

    /**
     * Jumps the panel between the two corners it is useful in. The accessibility action's whole job:
     * a drag is only ever used to get the panel out of the way, and a drag is exactly what a screen
     * reader or Switch Access user cannot do.
     */
    private fun movePanelToOppositeCorner() {
        val view = root ?: return
        val bounds = runCatching { windowManager.currentWindowMetrics.bounds }.getOrNull()
        val screenWidth = bounds?.width()?.takeIf { it > 0 }
            ?: appContext.resources.displayMetrics.widthPixels
        val width = if (view.width > 0) view.width else dp(appContext, 180f)
        val margin = dp(appContext, 16f)
        val leftHome = margin
        val rightHome = (screenWidth - width - margin).coerceAtLeast(leftHome)
        val startedLeft = (desiredX ?: leftHome) <= (leftHome + rightHome) / 2
        desiredX = if (startedLeft) rightHome else leftHome
        wake()
        refreshWindow()

        // Announced from the position that was actually *applied*, not the one that was asked for:
        // PanelPlacement can push the panel back off an avoid region, and saying "moved to the right"
        // while it sits where it was is worse than saying nothing.
        val landedX = params?.x ?: return
        val landedRight = landedX > (leftHome + rightHome) / 2
        if (landedRight == startedLeft) {
            // Nothing about a window jumping is perceivable to someone who is not looking at it, so the
            // action says what it did. Without this the user performs "move panel" and gets silence.
            @Suppress("DEPRECATION") // API 36 deprecates it with no replacement for a one-shot
            // confirmation like this one; the alternative — describing the position in the panel's own
            // state so it is read on the next focus — would only be heard if the user went looking.
            view.announceForAccessibility(
                appContext.getString(
                    if (landedRight) {
                        R.string.ovl_panel_moved_right
                    } else {
                        R.string.ovl_panel_moved_left
                    }
                )
            )
        }
    }

    // ------------------------------------------------------------------- window state and layout

    private fun computeFlags(): Int {
        // The layout flags belong here too: this value *replaces* the ones the constructor was given.
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (OverlayPolicy.keepScreenOn(latest.state, options.keepScreenOn)) {
            flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        }
        if (cloaked) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return flags
    }

    /**
     * The window's own opacity.
     *
     * Dimming used to drop the whole window to [DIM_ALPHA] = 0.25, countdown included — the one number
     * the panel exists to show became unreadable, and the only way to read it was to touch a panel you
     * could barely see. The window now goes no lower than [DIM_WINDOW_ALPHA]; what actually recedes is
     * the panel's *chrome*, faded per view in [applyLayerStyling], so the countdown stays legible while
     * the buttons and the background stop competing with the camera preview.
     *
     * While cloaked the window sits at [CLOAK_WINDOW_ALPHA]: the platform forces a non-touchable window
     * above 0.8 down to 0.8 anyway (and logs about it), so this both matches what the user will see and
     * gives the state a visible cue instead of a silently dead panel.
     */
    private fun computeAlpha(): Float = when {
        cloaked -> CLOAK_WINDOW_ALPHA
        !options.dimPanel -> 1f
        SystemClock.uptimeMillis() < awakeUntilUptime -> 1f
        else -> DIM_WINDOW_ALPHA
    }

    /**
     * Fades the parts of the panel that are not the countdown: dimmed, so the preview shows through;
     * cloaked, so "your touches are going to the camera right now" is something the user can see.
     */
    private fun applyLayerStyling() {
        val dimmed = options.dimPanel && SystemClock.uptimeMillis() >= awakeUntilUptime
        val chrome = when {
            cloaked -> CLOAK_CHROME_ALPHA
            dimmed -> DIM_CHROME_ALPHA
            else -> 1f
        }
        statusView?.alpha = if (dimmed) DIM_CHROME_ALPHA else 0.85f
        controlsRow?.alpha = chrome
        togglesRow?.alpha = chrome
        countdownView?.alpha = 1f
    }

    private fun wake() {
        awakeUntilUptime = SystemClock.uptimeMillis() + WAKE_MS
        main.removeCallbacks(dimRunnable)
        if (options.dimPanel) main.postDelayed(dimRunnable, WAKE_MS)
        refreshWindow()
    }

    private val dimRunnable = Runnable { refreshWindow() }

    /**
     * Re-evaluates the cloak and arms a timer for the next transition. Called on every snapshot and
     * every render tick, so a rescheduled or cancelled shot cannot leave a stale timer behind.
     */
    private fun applyCloak() {
        if (root == null) return
        val now = SystemClock.elapsedRealtime()
        val cadenceMs = cadence.observe(latest)
        val wanted = OverlayPolicy.isCloaked(latest, now, cadenceMs)
        main.removeCallbacks(cloakRunnable)
        OverlayPolicy.nextCloakChangeDelayMs(latest, now, cadenceMs)?.let {
            main.postDelayed(cloakRunnable, it.coerceAtLeast(0L))
        }
        if (wanted == cloaked) return
        cloaked = wanted
        refreshWindow()
    }

    private val cloakRunnable = Runnable { applyCloak() }

    /**
     * The single place that touches the window: resolves the position, the flags and the alpha, and
     * pushes them in one `updateViewLayout` — and only when something actually changed.
     */
    private fun refreshWindow() {
        val view = root ?: return
        val layout = params ?: return
        val position = resolvePosition(view)
        val flags = computeFlags()
        val alpha = computeAlpha()
        val changed = layout.x != position.x || layout.y != position.y ||
            layout.flags != flags || layout.alpha != alpha
        layout.x = position.x
        layout.y = position.y
        layout.flags = flags
        layout.alpha = alpha
        applyLayerStyling()
        if (changed) runCatching { windowManager.updateViewLayout(view, layout) }
    }

    /** Keeps the panel on screen and out of every region a dispatched shutter tap can land in. */
    private fun resolvePosition(view: View): PanelPosition {
        val bounds = runCatching { windowManager.currentWindowMetrics.bounds }.getOrNull()
        val screenWidth = bounds?.width()?.takeIf { it > 0 }
            ?: appContext.resources.displayMetrics.widthPixels
        val screenHeight = bounds?.height()?.takeIf { it > 0 }
            ?: appContext.resources.displayMetrics.heightPixels
        val width = if (view.width > 0) view.width else dp(appContext, 180f)
        val height = if (view.height > 0) view.height else dp(appContext, 156f)
        return PanelPlacement.resolve(
            desiredX = wantX(),
            desiredY = wantY(),
            panelWidth = width,
            panelHeight = height,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            avoid = avoid,
            safe = safeArea(screenWidth, screenHeight),
        )
    }

    /**
     * The part of the display the panel may occupy: everything except the system bars and the cutout.
     *
     * The window itself spans the whole display so its coordinates match the pixels a dispatched
     * gesture is aimed at (see [buildLayoutParams]); this is what keeps the panel from being *placed*
     * behind the status bar or the navigation pill, where the system would swallow its touches.
     * `getInsetsIgnoringVisibility` rather than the current insets: a camera app that hides the bars
     * must not let the panel drift into a strip that reappears the moment the user swipes.
     */
    private fun safeArea(screenWidth: Int, screenHeight: Int): Box? {
        val insets = runCatching {
            windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
        }.getOrNull() ?: return null
        return Box(
            left = insets.left,
            top = insets.top,
            right = screenWidth - insets.right,
            bottom = screenHeight - insets.bottom,
        )
    }

    /**
     * The regions a dispatched tap can hit, in current-screen pixels:
     *  * the calibrated target, inflated by [AVOID_MARGIN_DP] — a point target becomes a comfortable
     *    box rather than a single pixel, and jitter inside a rect target stays covered;
     *  * the bottom-centre third, which is the dispatcher's last-resort node-search region and where
     *    a portrait camera app puts its shutter.
     */
    private fun recomputeAvoidRegions() {
        val geometry = runCatching { DisplayGeometry.current(appContext) }.getOrNull()
        if (geometry == null) {
            avoid = emptyList()
            return
        }
        val margin = dp(appContext, AVOID_MARGIN_DP)
        val boxes = ArrayList<Box>(2)
        calibrated?.let { target ->
            // Calibrations are always stored in natural space; see DisplayGeometry.
            val rect = runCatching { geometry.rectFor(target, Surface.ROTATION_0) }.getOrNull()
            if (rect != null) {
                boxes += Box(
                    left = rect.left.roundToInt(),
                    top = rect.top.roundToInt(),
                    right = rect.right.roundToInt(),
                    bottom = rect.bottom.roundToInt(),
                ).inflated(margin)
            }
        }
        val region = runCatching { geometry.shutterSearchRegion() }.getOrNull()
        if (region != null) {
            boxes += Box(region.left, region.top, region.right, region.bottom)
        }
        avoid = boxes
        refreshWindow()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    companion object {
        private const val TICK_MS = 1_000L

        /** The expressive `extraLarge` corner, so the floating panel matches the app's cards. */
        private const val PANEL_CORNER_DP = 28f

        /** Fully rounded: at this size the expressive shape scale says "pill". */
        private const val BUTTON_CORNER_DP = 999f

        /** The accessibility minimum, applied to every control on the panel. */
        private const val TOUCH_TARGET_DP = 48f

        /**
         * Vertical air above each stacked row. Without it the toggles row sat flush against the
         * Pause/Stop row and the two read as one block. Deliberately tighter than the gap between
         * two buttons of a row: the four controls still have to read as one panel, not as two
         * detached strips.
         */
        private const val ROW_GAP_DP = 6f

        /** Horizontal gap between the two buttons of a row. */
        private const val BUTTON_GAP_DP = 8f

        /** Opacity of a dimmed panel: readable at a glance, no longer the brightest thing on screen. */
        /**
         * How far a dimmed panel fades. Referenced by the settings copy, which is why it is public.
         * The window itself never goes below [DIM_WINDOW_ALPHA]; this is what the chrome reaches once
         * the two are multiplied.
         */
        const val DIM_ALPHA = 0.25f

        /** Id of the "move the panel" accessibility action; a resource id so it is unique. */
        private val ACTION_MOVE_PANEL_ID = R.id.ovl_action_move_panel

        /** Floor for the *window* while dimmed, so the countdown stays readable. */
        private const val DIM_WINDOW_ALPHA = 0.55f

        /** Chrome (status line, buttons, toggles) while dimmed: 0.55 x 0.45 lands at DIM_ALPHA. */
        private const val DIM_CHROME_ALPHA = 0.45f

        /** What the platform clamps a non-touchable window to anyway; also the visible cloak cue. */
        private const val CLOAK_WINDOW_ALPHA = 0.8f

        /** Buttons while the panel is passing touches through to the app underneath. */
        private const val CLOAK_CHROME_ALPHA = 0.45f

        /** How long a touch (or a rotation, or a settings change) holds the panel at full opacity. */
        private const val WAKE_MS = 6_000L

        /** Breathing room around the calibrated target the panel must not be placed in. */
        private const val AVOID_MARGIN_DP = 28f

        /** Panel position the *user* asked for, remembered across hide()/show() and rotation. */
        private var desiredX: Int? = null
        private var desiredY: Int? = null

        /** mm:ss, or h:mm:ss past an hour. Rounds up so "0:01" is never shown for 1400 ms. */
        internal fun formatCountdown(remainingMs: Long): String {
            val totalSeconds = (remainingMs.coerceAtLeast(0L) + 999L) / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            val locale = Locale.getDefault()
            return if (hours > 0L) {
                String.format(locale, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(locale, "%d:%02d", minutes, seconds)
            }
        }
    }
}

/**
 * A row of equal-width cells that cannot clip one of them.
 *
 * The cells are `width = 0` + `weight = 1f`, so [LinearLayout] measures them twice: once at their
 * natural width, then again at the width the weights hand out. A label that fitted on one line in
 * the first pass can wrap in the second and make its cell taller than the height the row already
 * recorded — the row does not go back and grow, so the extra line is drawn outside it and clipped.
 * The Czech "Ztlumení: vypnuto" toggle did exactly that and lost the bottom of its pill.
 *
 * So after the normal measure the row checks its cells, adopts the tallest one's height and gives
 * every cell that height, which also keeps two pills side by side the same size. See [RowMetrics].
 */
private class EqualCellRow(context: Context) : LinearLayout(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val padding = paddingTop + paddingBottom
        val cells = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != GONE }
        val corrected = RowMetrics.correctedHeight(
            measuredHeight = measuredHeight,
            cellHeights = cells.map { child ->
                val lp = child.layoutParams as? MarginLayoutParams
                child.measuredHeight + (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
            },
            verticalPadding = padding,
        ) ?: return
        val cellHeight = RowMetrics.cellHeight(corrected, padding)
        cells.forEach { child ->
            val lp = child.layoutParams as? MarginLayoutParams
            val margins = (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
            child.measure(
                MeasureSpec.makeMeasureSpec(child.measuredWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((cellHeight - margins).coerceAtLeast(0), MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(measuredWidth, corrected)
    }
}

/**
 * Root view that reports the events the controller has to react to: a display configuration change
 * (i.e. a rotation), the panel's own size settling, and the first touch on a dimmed panel.
 */
private class OverlayRootView(context: Context) : LinearLayout(context) {

    var onConfigChanged: (() -> Unit)? = null
    var onSizeChanged: (() -> Unit)? = null

    /** Returns true when the touch was consumed purely to wake the panel up. */
    var onWakeTouch: (() -> Boolean)? = null

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        // Post so the new display metrics are in place before we clamp.
        post { onConfigChanged?.invoke() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The first placement runs before the panel has been measured; redo it once it has a size.
        post { onSizeChanged?.invoke() }
    }

    private var swallowingGesture = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swallowingGesture = onWakeTouch?.invoke() == true
                if (swallowingGesture) return true
            }
            // The whole gesture is swallowed, not just the down: handing a stray MOVE to the drag
            // listener that never saw a DOWN would teleport the panel by the gesture's origin.
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (swallowingGesture) {
                swallowingGesture = false
                return true
            }
            else -> if (swallowingGesture) return true
        }
        return super.dispatchTouchEvent(event)
    }
}
