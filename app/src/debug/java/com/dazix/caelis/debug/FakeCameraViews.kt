package com.dazix.caelis.debug

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The two tap surfaces [FakeCameraActivity] renders. Each one reports which delivery path fired, so
 * a test can tell a node click from a dispatched gesture instead of just counting taps.
 */

/**
 * The labeled shutter. Deliberately reports itself as an `ImageButton` and answers
 * `ACTION_CLICK`, so it looks like a real camera shutter to the node-search path.
 *
 * Both delivery paths are distinguished at the source:
 *  - accessibility `ACTION_CLICK` on the node -> [FakeCameraTapLog.SOURCE_NODE]
 *  - a dispatched touch gesture landing on this view -> [FakeCameraTapLog.SOURCE_GESTURE]
 */
internal class ShutterView(context: Context) : View(context) {

    var onTap: ((String) -> Unit)? = null

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            // ACTION_DOWN is the instant the gesture landed; measuring on it keeps the reported
            // gaps free of the stroke duration.
            MotionEvent.ACTION_DOWN -> {
                onTap?.invoke(FakeCameraTapLog.SOURCE_GESTURE)
                isPressed = true
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                invalidate()
            }
        }
        // Consumed here on purpose: letting super run would also fire performClick() and the tap
        // would be counted twice.
        return true
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_CLICK) {
            onTap?.invoke(FakeCameraTapLog.SOURCE_NODE)
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.ImageButton"

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = minOf(width, height) / 2f
        ring.strokeWidth = outer * 0.09f
        canvas.drawCircle(cx, cy, outer - ring.strokeWidth, ring)
        fill.color = if (isPressed) Color.rgb(0xFF, 0x66, 0x55) else Color.WHITE
        canvas.drawCircle(cx, cy, outer * (if (isPressed) 0.68f else 0.74f), fill)
    }
}

/**
 * The unlabeled variant: no id, no content description, not clickable, hidden from accessibility.
 * The only way to make it fire is to land a touch gesture on it, which is precisely the
 * coordinate-fallback path.
 */
internal class UnlabeledCaptureView(context: Context) : View(context) {

    var onTap: ((String) -> Unit)? = null

    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(0x33, 0x33, 0x33)
        strokeWidth = 4f
    }
    private var lastX = -1f
    private var lastY = -1f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
        contentDescription = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            lastX = event.x
            lastY = event.y
            onTap?.invoke(FakeCameraTapLog.SOURCE_COORDINATE)
            invalidate()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (lastX < 0f) return
        canvas.drawCircle(lastX, lastY, 48f, marker)
    }
}
