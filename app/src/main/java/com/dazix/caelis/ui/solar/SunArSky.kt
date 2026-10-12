package com.dazix.caelis.ui.solar

import android.graphics.Paint
import android.graphics.RectF
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.dazix.caelis.R
import com.dazix.caelis.ar.DeviceOrientation
import com.dazix.caelis.ar.pose.SkyPoseFrame
import com.dazix.caelis.ar.pose.SkyPoseRig
import com.dazix.caelis.ar.scene.CameraBackground
import com.dazix.caelis.ar.scene.NormalGoldSolar
import com.dazix.caelis.ar.scene.NormalSunrise
import com.dazix.caelis.ar.scene.NormalSunset
import com.dazix.caelis.ar.scene.SkyGridMarkers
import com.dazix.caelis.ar.scene.SkySurface
import com.dazix.caelis.ar.scene.SunArScene
import com.dazix.caelis.domain.arframe.SkyProjector
import com.dazix.caelis.domain.horizon.HorizonProfile
import com.dazix.caelis.domain.solar.LunarCoordinates
import com.dazix.caelis.domain.solar.LunarTrajectory
import com.dazix.caelis.domain.solar.SolarCoordinates
import com.dazix.caelis.domain.solar.SolarTrajectory
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val MoonDisk = Color(0xFFE0F7FA)
private val MoonRise = Color(0xFF4FC3F7)
private val MoonSet = Color(0xFF26A69A)
private val Casing = Color(0x99000000)

/**
 * The Sun AR sky drawn by Filament ([SunArScene]: grid, terrain silhouette, day arcs), with the
 * Compose overlay for everything that is text or screen-space UI on top.
 *
 * Both halves come from one frame loop: each vsync latches the newest camera image, samples the pose
 * at that image's instant, renders the GPU frame and invalidates the overlay, which projects through
 * the scene's projector, so a time bubble cannot lag its arc. The camera picture is drawn by the
 * same Filament frame ([CameraBackground]) rather than by a `PreviewView` underneath, because only
 * then are image and overlay certain to come from the same pose; the sky renders into a TextureView
 * for the reason Night Sky does (see `NightSkyGpuSky`): a SurfaceView is presented apart from the
 * window and the overlay slid against it during a pan.
 */
@Composable
internal fun SunArGpuSky(
    orientation: DeviceOrientation?,
    onGetRenderMatrix: (timestampNs: Long, out: FloatArray) -> Unit,
    rig: SkyPoseRig,
    displayRotation: Int,
    fovHorizontalDegrees: Float,
    solarTrajectory: SolarTrajectory,
    lunarTrajectory: LunarTrajectory,
    currentSunPos: SolarCoordinates?,
    currentMoonPos: LunarCoordinates?,
    horizonProfile: HorizonProfile?,
    shadeOccludedTrajectory: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    var surface by remember { mutableStateOf<SkySurface?>(null) }
    var scene by remember { mutableStateOf<SunArScene?>(null) }
    var viewportWidth by remember { mutableStateOf(1f) }
    var viewportHeight by remember { mutableStateOf(1f) }

    // Hidden until the first frame is drawn (the first one compiles the line shader), then faded in.
    var revealed by remember { mutableStateOf(false) }
    val skyAlpha by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "sunArReveal",
    )

    DisposableEffect(surface) {
        val built = surface?.let { SunArScene(it) }
        scene = built
        onDispose { built?.destroy() }
    }
    var background by remember { mutableStateOf<CameraBackground?>(null) }
    DisposableEffect(surface) {
        val built = surface?.let { CameraBackground(it) }
        background = built
        onDispose { built?.destroy() }
    }
    SideEffect {
        scene?.setProfile(horizonProfile)
        scene?.setTrajectories(
            solarTrajectory,
            lunarTrajectory,
            shadeProfile = if (shadeOccludedTrajectory) horizonProfile else null,
            density = density,
        )
    }

    val currentOrientation by rememberUpdatedState(orientation)
    val currentGetMatrix by rememberUpdatedState(onGetRenderMatrix)
    val renderMatrix = remember { FloatArray(16) }
    val currentRotation by rememberUpdatedState(displayRotation)
    val currentFov by rememberUpdatedState(fovHorizontalDegrees)
    val currentRig by rememberUpdatedState(rig)
    val poseFrame = remember { SkyPoseFrame() }
    var frameTick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameNanos { nanos ->
                val sc = scene
                val sf = surface
                if (sc != null && sf != null && currentOrientation != null) {
                    // The newest camera image goes to the GPU first, and the pose is the one it was
                    // taken with, not the newest sensor sample: the picture is tens of milliseconds
                    // old, and the newest pose ran the overlay ahead of the ridge on every turn
                    // (PoseTimestampPolicy). With no image yet the pose follows the phone live.
                    //
                    // The rig also decides who owns the camera (CameraX or ARCore) and whose pose
                    // draws: ARCore's own matrix for the frame it just handed over, or the sensors'
                    // at the image's instant. Sun AR has no zoom, so the zoom is always 1x.
                    currentRig.advance(
                        background = background,
                        viewportWidth = viewportWidth,
                        viewportHeight = viewportHeight,
                        displayRotation = currentRotation,
                        zoomRatio = 1f,
                        pictureEnabled = true,
                        livePoseAllowed = true,
                        out = poseFrame,
                    )
                    if (poseFrame.useRigMatrix) {
                        poseFrame.matrix.copyInto(renderMatrix, 0, 0, 16)
                    } else {
                        currentGetMatrix(poseFrame.poseTimestampNs, renderMatrix)
                    }
                    sc.update(renderMatrix, currentRotation * 90, viewportWidth, viewportHeight, currentFov)
                    sf.renderFrame(nanos)
                    revealed = true
                    frameTick = nanos
                }
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx -> TextureView(ctx).also { surface = SkySurface(ctx, it, ownFrameLoop = false) } },
            onRelease = {
                // Whichever of this and the scene's DisposableEffect runs first, the scene and the
                // camera picture are released before the engine they live in (both destroys are
                // idempotent).
                scene?.destroy()
                scene = null
                background?.destroy()
                background = null
                surface?.destroy()
                surface = null
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = skyAlpha }
                .onSizeChanged {
                    viewportWidth = it.width.toFloat()
                    viewportHeight = it.height.toFloat()
                },
        )
        val sc = scene
        if (sc != null && revealed) {
            SunArOverlay(
                projector = sc.projector,
                frameTick = frameTick,
                solarTrajectory = solarTrajectory,
                lunarTrajectory = lunarTrajectory,
                currentSunPos = currentSunPos,
                currentMoonPos = currentMoonPos,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = skyAlpha },
            )
        }
    }
}

/** Paints and scratch space for [SunArOverlay], allocated once. Colours are the Canvas ones. */
internal class SunArOverlayPaints {
    val grid = SkyGridMarkers()
    val rect = RectF()
    val projected = FloatArray(3)
    val bearing = FloatArray(2)

    val bubbleText = textPaint(android.graphics.Color.WHITE)
    val bubbleBg = fillPaint(android.graphics.Color.argb(220, 20, 20, 20))
    val bubbleBorder = strokePaint(android.graphics.Color.argb(255, 255, 179, 0))
    val markerText = textPaint(android.graphics.Color.WHITE)
    val markerBg = fillPaint(android.graphics.Color.argb(230, 20, 20, 20))
    val markerBorder = strokePaint(android.graphics.Color.argb(255, 255, 179, 0))
    val pointerText = textPaint(android.graphics.Color.argb(255, 255, 179, 0))
    val pointerBg = fillPaint(android.graphics.Color.argb(220, 20, 20, 20))
    val pointerBorder = strokePaint(android.graphics.Color.argb(255, 255, 179, 0))

    private fun textPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        this.color = color
    }

    private fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    private fun strokePaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.color = color
    }

    companion object {
        val MOON_BORDER_ARGB = android.graphics.Color.argb(180, 128, 222, 234)
    }
}

/**
 * The Sun AR overlay: grid labels, time bubbles, rise/set badges, the live sun and moon, and the
 * pointer to either when it is off screen — everything Filament cannot draw, projected through
 * [projector]. [frameTick] only has to change to redraw it after the projector moved.
 */
@Composable
internal fun SunArOverlay(
    projector: SkyProjector,
    frameTick: Long,
    solarTrajectory: SolarTrajectory,
    lunarTrajectory: LunarTrajectory,
    currentSunPos: SolarCoordinates?,
    currentMoonPos: LunarCoordinates?,
    modifier: Modifier = Modifier,
) {
    val paints = remember { SunArOverlayPaints() }
    paints.grid.updateLabels(
        stringResource(R.string.sun_ar_zenith),
        stringResource(R.string.sun_ar_nadir),
        arrayOf(
            stringResource(R.string.sun_ar_cardinal_n),
            stringResource(R.string.sun_ar_cardinal_ne),
            stringResource(R.string.sun_ar_cardinal_e),
            stringResource(R.string.sun_ar_cardinal_se),
            stringResource(R.string.sun_ar_cardinal_s),
            stringResource(R.string.sun_ar_cardinal_sw),
            stringResource(R.string.sun_ar_cardinal_w),
            stringResource(R.string.sun_ar_cardinal_nw),
        ),
    )
    // Strings built when the data changes, not on every frame.
    val sunriseLabel = remember(solarTrajectory) { solarTrajectory.sunriseMarker?.let { markerLabel("🌅", it.formattedTime, it.coordinates.azimuthDegrees) } }
    val sunsetLabel = remember(solarTrajectory) { solarTrajectory.sunsetMarker?.let { markerLabel("🌇", it.formattedTime, it.coordinates.azimuthDegrees) } }
    val moonriseLabel = remember(lunarTrajectory) { lunarTrajectory.moonriseMarker?.let { markerLabel("🌙", it.formattedTime, it.coordinates.azimuthDegrees) } }
    val moonsetLabel = remember(lunarTrajectory) { lunarTrajectory.moonsetMarker?.let { markerLabel("🌘", it.formattedTime, it.coordinates.azimuthDegrees) } }
    val sunPointerLabel = remember(currentSunPos) { currentSunPos?.let { signedDegrees(it.elevationDegrees) } }
    val moonPointerLabel = remember(currentMoonPos) { currentMoonPos?.let { "${it.phaseGlyph} ${signedDegrees(it.elevationDegrees)}" } }

    Canvas(modifier = modifier) {
        @Suppress("UNUSED_VARIABLE")
        val tick = frameTick
        val w = size.width
        val h = size.height
        paints.grid.horizonTextPaint.textSize = 5.3.sp.toPx()
        paints.grid.gridTextPaint.textSize = 4.5.sp.toPx()
        paints.bubbleText.textSize = 11.sp.toPx()
        paints.bubbleBorder.strokeWidth = 1.2.dp.toPx()
        paints.markerText.textSize = 12.sp.toPx()
        paints.markerBorder.strokeWidth = 1.5.dp.toPx()
        paints.pointerText.textSize = 11.sp.toPx()
        paints.pointerBorder.strokeWidth = 1.2.dp.toPx()

        paints.grid.draw(drawContext.canvas.nativeCanvas, projector, w, h)

        val sunBubbles = solarTrajectory.timeBubbles
        for (i in sunBubbles.indices) {
            val c = sunBubbles[i].coordinates
            drawTimeBubble(projector, paints, c.azimuthDegrees, c.apparentElevationDegrees, sunBubbles[i].formattedTime, borderArgb = null)
        }
        val moonBubbles = lunarTrajectory.timeBubbles
        for (i in moonBubbles.indices) {
            val c = moonBubbles[i].coordinates
            drawTimeBubble(projector, paints, c.azimuthDegrees, c.apparentElevationDegrees, moonBubbles[i].formattedTime, SunArOverlayPaints.MOON_BORDER_ARGB)
        }

        solarTrajectory.sunriseMarker?.let { drawMarker(projector, paints, it.coordinates.azimuthDegrees, it.coordinates.apparentElevationDegrees, sunriseLabel!!, NormalSunrise) }
        solarTrajectory.sunsetMarker?.let { drawMarker(projector, paints, it.coordinates.azimuthDegrees, it.coordinates.apparentElevationDegrees, sunsetLabel!!, NormalSunset) }
        lunarTrajectory.moonriseMarker?.let { drawMarker(projector, paints, it.coordinates.azimuthDegrees, it.coordinates.apparentElevationDegrees, moonriseLabel!!, MoonRise) }
        lunarTrajectory.moonsetMarker?.let { drawMarker(projector, paints, it.coordinates.azimuthDegrees, it.coordinates.apparentElevationDegrees, moonsetLabel!!, MoonSet) }

        if (currentSunPos != null) {
            val az = currentSunPos.azimuthDegrees
            val el = currentSunPos.apparentElevationDegrees
            val inFront = projector.projectAltAz(az, el, paints.projected)
            val x = paints.projected[0]
            val y = paints.projected[1]
            if (inFront && x in 0f..w && y in 0f..h) drawLiveSun(x, y)
            else drawOffscreenPointer(projector, paints, az, el, sunPointerLabel!!, isMoon = false)
        }
        if (currentMoonPos != null) {
            val az = currentMoonPos.azimuthDegrees
            val el = currentMoonPos.apparentElevationDegrees
            val inFront = projector.projectAltAz(az, el, paints.projected)
            val x = paints.projected[0]
            val y = paints.projected[1]
            if (inFront && x in 0f..w && y in 0f..h) drawLiveMoon(x, y)
            else drawOffscreenPointer(projector, paints, az, el, moonPointerLabel!!, isMoon = true)
        }
    }
}

private fun markerLabel(emoji: String, time: String, azimuthDegrees: Double) = "$emoji $time (${azimuthDegrees.toInt()}°)"

private fun signedDegrees(elevation: Double) = "${if (elevation >= 0) "+" else ""}${elevation.toInt()}°"

/** A time pill on an arc. Only points above -15° are labelled, as only those stretches are drawn. */
private fun DrawScope.drawTimeBubble(
    projector: SkyProjector,
    paints: SunArOverlayPaints,
    azimuth: Double,
    elevation: Double,
    text: String,
    borderArgb: Int?,
) {
    if (elevation < -15.0) return
    if (!projector.projectAltAz(azimuth, elevation, paints.projected)) return
    val px = paints.projected[0]
    val py = paints.projected[1]
    val margin = 60f
    if (px !in -margin..(size.width + margin) || py !in -margin..(size.height + margin)) return
    val pw = 46.dp.toPx()
    val ph = 21.dp.toPx()
    val radius = ph * 0.5f
    val left = px - pw * 0.5f
    val top = py - ph * 0.5f
    paints.rect.set(left, top, left + pw, top + ph)
    val canvas = drawContext.canvas.nativeCanvas
    canvas.drawRoundRect(paints.rect, radius, radius, paints.bubbleBg)
    val border = paints.bubbleBorder.color
    if (borderArgb != null) paints.bubbleBorder.color = borderArgb
    canvas.drawRoundRect(paints.rect, radius, radius, paints.bubbleBorder)
    paints.bubbleBorder.color = border
    canvas.drawText(text, px, py + paints.bubbleText.textSize * 0.35f, paints.bubbleText)
}

/** A rise or set badge: a dot on the horizon with the time and azimuth above it. */
private fun DrawScope.drawMarker(
    projector: SkyProjector,
    paints: SunArOverlayPaints,
    azimuth: Double,
    elevation: Double,
    label: String,
    accent: Color,
) {
    if (!projector.projectAltAz(azimuth, elevation, paints.projected)) return
    val px = paints.projected[0]
    val py = paints.projected[1]
    val margin = 80f
    if (px !in -margin..(size.width + margin) || py !in -margin..(size.height + margin)) return
    val textW = paints.markerText.measureText(label) + 16.dp.toPx()
    val ph = 26.dp.toPx()
    val radius = ph * 0.5f
    val left = px - textW * 0.5f
    val top = py - ph * 0.5f - 16.dp.toPx()
    paints.rect.set(left, top, left + textW, top + ph)
    paints.markerBorder.color = android.graphics.Color.argb(
        (accent.alpha * 255).toInt(),
        (accent.red * 255).toInt(),
        (accent.green * 255).toInt(),
        (accent.blue * 255).toInt(),
    )
    drawCircle(color = accent, radius = 4.dp.toPx(), center = Offset(px, py))
    val canvas = drawContext.canvas.nativeCanvas
    canvas.drawRoundRect(paints.rect, radius, radius, paints.markerBg)
    canvas.drawRoundRect(paints.rect, radius, radius, paints.markerBorder)
    canvas.drawText(label, px, top + ph * 0.5f + paints.markerText.textSize * 0.35f, paints.markerText)
}

private fun DrawScope.drawLiveSun(x: Float, y: Float) {
    val c = Offset(x, y)
    drawCircle(color = NormalGoldSolar, radius = 11.dp.toPx(), center = c)
    drawCircle(color = Color.White, radius = 4.dp.toPx(), center = c)
    drawCircle(color = Casing, radius = 11.5.dp.toPx(), center = c, style = Stroke(width = 1.2.dp.toPx()))
}

private fun DrawScope.drawLiveMoon(x: Float, y: Float) {
    val c = Offset(x, y)
    drawCircle(color = SunArScene.MoonArc, radius = 10.5.dp.toPx(), center = c)
    drawCircle(color = MoonDisk, radius = 7.5.dp.toPx(), center = c)
    drawCircle(color = Color.White, radius = 3.5.dp.toPx(), center = c)
    drawCircle(color = Casing, radius = 11.0.dp.toPx(), center = c, style = Stroke(width = 1.2.dp.toPx()))
}

/**
 * The sun or moon pinned to the screen edge in the direction to turn, with an arrow outwards and
 * its elevation on a tag inwards. The edge keeps clear of the top bar and the bottom controls.
 */
private fun DrawScope.drawOffscreenPointer(
    projector: SkyProjector,
    paints: SunArOverlayPaints,
    azimuth: Double,
    elevation: Double,
    tag: String,
    isMoon: Boolean,
) {
    val az = Math.toRadians(azimuth)
    val el = Math.toRadians(elevation)
    projector.screenBearing(
        (sin(az) * cos(el)).toFloat(), (cos(az) * cos(el)).toFloat(), sin(el).toFloat(), paints.bearing,
    )
    val dirX = paints.bearing[0]
    val dirY = paints.bearing[1]
    val w = size.width
    val h = size.height

    val marginX = 46.dp.toPx()
    val marginTop = 92.dp.toPx()
    val marginBottom = 150.dp.toPx()
    val cx = w * 0.5f
    val cy = h * 0.5f
    val halfW = cx - marginX
    val halfH = if (dirY < 0f) cy - marginTop else cy - marginBottom
    val tx = if (abs(dirX) > 0.0001f) halfW / abs(dirX) else Float.MAX_VALUE
    val ty = if (abs(dirY) > 0.0001f) halfH / abs(dirY) else Float.MAX_VALUE
    val t = minOf(tx, ty)
    val edgeX = (cx + dirX * t).coerceIn(marginX, w - marginX)
    val edgeY = (cy + dirY * t).coerceIn(marginTop, h - marginBottom)
    val edge = Offset(edgeX, edgeY)

    val accent = if (isMoon) SunArScene.MoonArc else NormalGoldSolar
    val bodyRadius = 6.5.dp.toPx()
    if (isMoon) {
        drawCircle(color = accent, radius = bodyRadius, center = edge)
        drawCircle(color = MoonDisk, radius = bodyRadius - 1.5.dp.toPx(), center = edge)
    } else {
        drawCircle(color = accent, radius = bodyRadius, center = edge)
        drawCircle(color = Color.White, radius = 2.5.dp.toPx(), center = edge)
    }
    drawCircle(color = Casing, radius = bodyRadius + 0.5.dp.toPx(), center = edge, style = Stroke(width = 1.0.dp.toPx()))
    if (!isMoon) {
        val rayInner = bodyRadius + 2.0.dp.toPx()
        val rayOuter = rayInner + 3.0.dp.toPx()
        for (i in 0 until 8) {
            val angle = (i * Math.PI / 4.0).toFloat()
            val cosA = cos(angle)
            val sinA = sin(angle)
            drawLine(
                color = accent,
                start = Offset(edgeX + cosA * rayInner, edgeY + sinA * rayInner),
                end = Offset(edgeX + cosA * rayOuter, edgeY + sinA * rayOuter),
                strokeWidth = 1.4.dp.toPx(),
            )
        }
    }

    val arrowDist = 18.dp.toPx()
    val arrowLen = 10.dp.toPx()
    val arrowSpread = 6.dp.toPx()
    val tip = Offset(edgeX + dirX * arrowDist, edgeY + dirY * arrowDist)
    val perpX = -dirY
    val perpY = dirX
    val baseLeft = Offset(tip.x - dirX * arrowLen + perpX * arrowSpread, tip.y - dirY * arrowLen + perpY * arrowSpread)
    val baseRight = Offset(tip.x - dirX * arrowLen - perpX * arrowSpread, tip.y - dirY * arrowLen - perpY * arrowSpread)
    drawLine(color = Casing, start = tip, end = baseLeft, strokeWidth = 3.2.dp.toPx())
    drawLine(color = Casing, start = tip, end = baseRight, strokeWidth = 3.2.dp.toPx())
    drawLine(color = accent, start = tip, end = baseLeft, strokeWidth = 2.0.dp.toPx())
    drawLine(color = accent, start = tip, end = baseRight, strokeWidth = 2.0.dp.toPx())

    val textW = paints.pointerText.measureText(tag) + 12.dp.toPx()
    val ph = 18.dp.toPx()
    val radius = ph * 0.5f
    val tagDist = 20.dp.toPx()
    val tagX = edgeX - dirX * tagDist
    val tagY = edgeY - dirY * tagDist
    val left = tagX - textW * 0.5f
    val top = tagY - ph * 0.5f
    paints.rect.set(left, top, left + textW, top + ph)
    val canvas = drawContext.canvas.nativeCanvas
    canvas.drawRoundRect(paints.rect, radius, radius, paints.pointerBg)
    val border = paints.pointerBorder.color
    if (isMoon) paints.pointerBorder.color = SunArOverlayPaints.MOON_BORDER_ARGB
    canvas.drawRoundRect(paints.rect, radius, radius, paints.pointerBorder)
    paints.pointerBorder.color = border
    canvas.drawText(tag, tagX, top + ph * 0.5f + paints.pointerText.textSize * 0.35f, paints.pointerText)
}
