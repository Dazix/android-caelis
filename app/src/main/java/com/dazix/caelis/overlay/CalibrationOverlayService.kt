package com.dazix.caelis.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.dazix.caelis.R
import com.dazix.caelis.data.SettingsStore
import com.dazix.caelis.domain.TapTarget
import com.dazix.caelis.service.DisplayGeometry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Hosts the calibration capture surface as a `TYPE_APPLICATION_OVERLAY` window, on top of the real
 * camera app rather than on top of our own home screen. See [CalibrationOverlay] for the entry point
 * and for why this is not an Activity any more.
 *
 * A plain started service, not a foreground one: it exists only to own a window and a timeout for the
 * few seconds calibration takes, and a process that holds a visible overlay window is not a candidate
 * for background-service teardown while that window is up. It is `START_NOT_STICKY`, so a process
 * death ends the attempt instead of resurrecting a stray full-screen window later.
 *
 * Coordinates come from the raw touch position, which is display space — exactly the space
 * `AccessibilityService.dispatchGesture` replays taps in — and are converted to natural space by
 * [DisplayGeometry] so one calibration survives a rotation.
 */
class CalibrationOverlayService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: SettingsStore
    private var windowManager: WindowManager? = null

    private var surface: CalibrationSurface? = null
    private var targetPackage: String? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        windowManager = getSystemService(WindowManager::class.java)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            dismiss()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.ovl_permission_missing))
            dismiss()
            return START_NOT_STICKY
        }
        targetPackage = intent?.getStringExtra(EXTRA_TARGET_PACKAGE)?.takeIf { it.isNotBlank() }
        val launched = intent?.getBooleanExtra(EXTRA_TARGET_LAUNCHED, false) ?: false
        showSurface(launched)
        armTimeout()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeSurface()
        main.removeCallbacks(timeoutRunnable)
        CalibrationOverlay.reportActive(false)
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------- window plumbing

    private fun showSurface(targetLaunched: Boolean) {
        val existing = surface
        if (existing != null) {
            existing.setInstruction(instructionText(targetLaunched))
            return
        }
        val manager = windowManager ?: run { dismiss(); return }
        val view = CalibrationSurface(
            context = this,
            instruction = instructionText(targetLaunched),
            onCommit = ::commit,
            onCancel = { cancelAndReturn() },
        )
        val added = runCatching { manager.addView(view, layoutParams()) }.isSuccess
        if (!added) {
            toast(getString(R.string.ovl_permission_missing))
            dismiss()
            return
        }
        surface = view
        view.takeFocus()
        CalibrationOverlay.reportActive(true)
    }

    private fun layoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable on purpose — unlike the session panel this window *is* modal: it has to take
            // every touch so the camera app does not fire while the shutter is being designated, and
            // it has to get the back key so back cancels calibration instead of closing the camera.
            // LAYOUT_IN_SCREEN + NO_LIMITS make the window span the whole display, which is what keeps
            // raw touch coordinates identical to the pixels a dispatched gesture is aimed at.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

    private fun removeSurface() {
        val view = surface ?: return
        surface = null
        val manager = windowManager
        if (manager != null) runCatching { manager.removeViewImmediate(view) }
    }

    private fun dismiss() {
        removeSurface()
        CalibrationOverlay.reportActive(false)
        main.removeCallbacks(timeoutRunnable)
        stopSelf()
    }

    private fun armTimeout() {
        main.removeCallbacks(timeoutRunnable)
        main.postDelayed(timeoutRunnable, TIMEOUT_MS)
    }

    /** A surface nobody finished is worse than no surface: it would sit over the camera forever. */
    private val timeoutRunnable = Runnable {
        if (surface != null) toast(getString(R.string.ovl_calib_timed_out))
        dismiss()
    }

    // ----------------------------------------------------------------------------------- capture

    /**
     * [rect] is in raw display pixels; for a tap left/top == right/bottom. The fractions are stored in
     * natural space and filed under the rotation they were captured in, which is the pair the session
     * engine reads back.
     */
    private fun commit(rect: RectF, isDrag: Boolean) {
        val geometry = DisplayGeometry.current(this)
        val fractions = CalibrationMath.screenFractions(
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
            isDrag = isDrag,
            widthPx = geometry.widthPx,
            heightPx = geometry.heightPx,
        )
        val onScreen = TapTarget(
            xFraction = fractions.x,
            yFraction = fractions.y,
            widthFraction = fractions.width,
            heightFraction = fractions.height,
        )
        val target = geometry.toNatural(onScreen)
        val rotation = geometry.rotation
        val pkg = targetPackage

        CalibrationOverlay.reportResult(CalibrationResult(pkg, rotation, target))
        if (pkg != null) {
            // Deliberately not service-scoped: the write has to survive stopSelf().
            storeScope.launch { runCatching { settings.saveTarget(pkg, rotation, target) } }
        }
        toast(
            getString(
                if (isDrag) R.string.ovl_calib_saved_area else R.string.ovl_calib_saved_point
            )
        )
        dismiss()
        returnToApp()
    }

    private fun cancelAndReturn() {
        dismiss()
        returnToApp()
    }

    /**
     * Brings our own UI back so the user sees the result, without a compile-time reference to the UI
     * slice. Holding `SYSTEM_ALERT_WINDOW` is what exempts this from the background-activity-start
     * restriction; if it is refused anyway the calibration is already stored, so nothing is lost.
     */
    private fun returnToApp() {
        val intent = runCatching {
            packageManager.getLaunchIntentForPackage(packageName)
        }.getOrNull() ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { startActivity(intent) }
    }

    // ------------------------------------------------------------------------------------- text

    private fun instructionText(targetLaunched: Boolean): CharSequence {
        val pkg = targetPackage ?: return getString(R.string.ovl_calib_instruction_generic)
        val label = labelFor(pkg)
        return if (targetLaunched) {
            getString(R.string.ovl_calib_instruction, label)
        } else {
            getString(R.string.ovl_calib_instruction_not_launched, label)
        }
    }

    private fun labelFor(packageName: String): String = runCatching {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private fun toast(message: String) {
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val EXTRA_TARGET_PACKAGE =
            "com.dazix.caelis.overlay.extra.CALIB_TARGET_PACKAGE"
        private const val EXTRA_TARGET_LAUNCHED =
            "com.dazix.caelis.overlay.extra.CALIB_TARGET_LAUNCHED"
        private const val ACTION_CANCEL = "com.dazix.caelis.overlay.action.CALIB_CANCEL"

        /** Long enough to find a shutter in an unfamiliar camera app, short enough not to strand one. */
        private const val TIMEOUT_MS = 3 * 60 * 1_000L

        private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun startIntent(context: Context, packageName: String?, targetLaunched: Boolean): Intent =
            Intent(context, CalibrationOverlayService::class.java).apply {
                putExtra(EXTRA_TARGET_PACKAGE, packageName)
                putExtra(EXTRA_TARGET_LAUNCHED, targetLaunched)
            }

        fun cancelIntent(context: Context): Intent =
            Intent(context, CalibrationOverlayService::class.java).setAction(ACTION_CANCEL)
    }
}

/**
 * The capture surface itself: a nearly transparent full-display sheet that takes the gesture, plus an
 * instruction card pinned to the top so it cannot cover the shutter, which is at the bottom in every
 * camera app worth calibrating.
 */
private class CalibrationSurface(
    context: Context,
    instruction: CharSequence,
    private val onCommit: (RectF, Boolean) -> Unit,
    private val onCancel: () -> Unit,
) : FrameLayout(context) {

    private val instructionView: TextView

    init {
        setBackgroundColor(SCRIM)
        isFocusableInTouchMode = true

        addView(
            SelectionView(context, ViewConfiguration.get(context).scaledTouchSlop, onCommit),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16f)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18f).toFloat()
                setColor(CARD)
            }
        }
        instructionView = TextView(context).apply {
            text = instruction
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        val hint = TextView(context).apply {
            text = context.getString(R.string.ovl_calib_hint)
            setTextColor(Color.WHITE)
            alpha = 0.7f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val cancel = Button(context).apply {
            text = context.getString(R.string.ovl_calib_cancel)
            setOnClickListener { onCancel() }
        }
        card.addView(instructionView)
        card.addView(
            hint,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6f) },
        )
        card.addView(
            cancel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(8f)
                gravity = Gravity.END
            },
        )

        val margin = dp(20f)
        addView(
            card,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                leftMargin = margin
                rightMargin = margin
                topMargin = margin
            },
        )
        // The window spans the cutout and the status bar, so the card has to be pushed down itself.
        setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            (card.layoutParams as LayoutParams).topMargin = margin + bars.top
            view.requestLayout()
            insets
        }
    }

    fun setInstruction(text: CharSequence) {
        instructionView.text = text
    }

    fun takeFocus() {
        requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onCancel()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        /** Light enough to see the camera UI through, dark enough to say "you are calibrating". */
        private const val SCRIM = 0x26000000
        private const val CARD = 0xE6101014.toInt()
    }
}

/**
 * Captures the gesture and draws the in-progress rectangle. Reports raw (display) coordinates so the
 * service does not have to reason about where the content view sits inside the window.
 */
private class SelectionView(
    context: Context,
    private val touchSlop: Int,
    private val onGesture: (RectF, Boolean) -> Unit,
) : View(context) {

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0x33FFFFFF
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f * context.resources.displayMetrics.density
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f * context.resources.displayMetrics.density
    }
    private val markerRadius = 18f * context.resources.displayMetrics.density

    private var downRaw = floatArrayOf(0f, 0f)
    private var downLocal = floatArrayOf(0f, 0f)
    private var currentRaw = floatArrayOf(0f, 0f)
    private var currentLocal = floatArrayOf(0f, 0f)
    private var dragging = false
    private var pressed = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRaw = floatArrayOf(event.rawX, event.rawY)
                downLocal = floatArrayOf(event.x, event.y)
                currentRaw = downRaw.copyOf()
                currentLocal = downLocal.copyOf()
                dragging = false
                pressed = true
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!pressed) return false
                currentRaw = floatArrayOf(event.rawX, event.rawY)
                currentLocal = floatArrayOf(event.x, event.y)
                if (!dragging &&
                    (abs(currentRaw[0] - downRaw[0]) > touchSlop ||
                        abs(currentRaw[1] - downRaw[1]) > touchSlop)
                ) {
                    dragging = true
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!pressed) return false
                pressed = false
                currentRaw = floatArrayOf(event.rawX, event.rawY)
                val rect = RectF(
                    min(downRaw[0], currentRaw[0]),
                    min(downRaw[1], currentRaw[1]),
                    max(downRaw[0], currentRaw[0]),
                    max(downRaw[1], currentRaw[1]),
                )
                val wasDrag = dragging
                dragging = false
                invalidate()
                onGesture(rect, wasDrag)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                dragging = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dragging) {
            val left = min(downLocal[0], currentLocal[0])
            val top = min(downLocal[1], currentLocal[1])
            val right = max(downLocal[0], currentLocal[0])
            val bottom = max(downLocal[1], currentLocal[1])
            canvas.drawRect(left, top, right, bottom, fillPaint)
            canvas.drawRect(left, top, right, bottom, strokePaint)
        } else if (pressed) {
            // Confirms the finger position without hiding the button underneath it.
            canvas.drawCircle(currentLocal[0], currentLocal[1], markerRadius, markerPaint)
        }
    }
}
