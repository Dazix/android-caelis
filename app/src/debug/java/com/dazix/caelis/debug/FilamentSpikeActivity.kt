package com.dazix.caelis.debug

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.dazix.caelis.R
import com.dazix.caelis.ar.OrientationTracker
import com.dazix.caelis.ar.scene.NightSkyFrame
import com.dazix.caelis.ar.scene.NightSkyScene
import com.dazix.caelis.ar.scene.SkySurface
import com.dazix.caelis.ar.scene.StarGeometry
import com.dazix.caelis.domain.nightsky.CelestialMatrixCalculator
import com.dazix.caelis.domain.nightsky.NightSkyCatalogLoader
import com.dazix.caelis.domain.nightsky.NightVisionTheme
import com.dazix.caelis.domain.nightsky.SiderealTime

/**
 * P0 go/no-go spike for the Filament sky, debug builds only.
 *
 * Proves on a real phone, before anything in the Canvas pipeline is deleted, that a translucent
 * Filament SurfaceView composites over the CameraX preview, that the whole [NightSkyScene]
 * (Milky Way, atmosphere, catalogue billboards, constellation figures and artwork, landscape,
 * grid) renders at display rate, and that the sensor tracker drives the camera. Frame statistics
 * go to logcat under [TAG].
 *
 * Extras: `lat`, `lon` (degrees, default Prague), `fov` (horizontal degrees, default 60),
 * `zoom` (telescope factor, default 1), `nv` (night vision: RED / AMBER / GREEN), `grid` (boolean),
 * `az` + `el` (float degrees: a fixed view instead of the sensors), `bare` (boolean: no Milky Way,
 * atmosphere or landscape, so the camera image must show through).
 */
class FilamentSpikeActivity : ComponentActivity() {

    private lateinit var surface: SkySurface
    private lateinit var scene: NightSkyScene
    private lateinit var tracker: OrientationTracker
    private val frame = NightSkyFrame()

    private var frames = 0
    private var windowStartNs = 0L
    private var worstFrameNs = 0L
    private var lastFrameNs = 0L
    private var onFrameCpuNs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val latitude = intent.getDoubleExtra("lat", 50.08)
        val longitude = intent.getDoubleExtra("lon", 14.43)
        frame.latitudeDegrees = latitude
        frame.fovHorizontalDegrees = intent.getFloatExtra("fov", 60f)
        frame.zoomRatio = intent.getFloatExtra("zoom", 1f)
        frame.showGrid = intent.getBooleanExtra("grid", false)
        if (intent.getBooleanExtra("bare", false)) {
            // Only stars, figures and grid: proves the camera image shows through the sky.
            frame.showMilkyWay = false
            frame.showAtmosphere = false
            frame.showLandscape = false
        }
        intent.getStringExtra("nv")?.let { name ->
            frame.nightVisionActive = true
            frame.nightVisionTheme = NightVisionTheme.valueOf(name)
        }
        frame.density = resources.displayMetrics.density

        val root = FrameLayout(this)
        val preview = PreviewView(this)
        val skyView = SurfaceView(this)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(preview, FrameLayout.LayoutParams(match, match))
        root.addView(skyView, FrameLayout.LayoutParams(match, match))
        setContentView(root)
        skyView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            frame.viewportWidth = v.width.toFloat()
            frame.viewportHeight = v.height.toFloat()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            bindCamera(preview)
        } else {
            Log.w(TAG, "No camera permission; rendering the sky over black")
        }

        val setupStart = SystemClock.elapsedRealtimeNanos()
        val catalog = resources.openRawResource(R.raw.catalog).use(NightSkyCatalogLoader::loadFromStream)
        surface = SkySurface(this, skyView)
        scene = NightSkyScene(this, surface, catalog, StarGeometry(catalog))
        decode(R.drawable.milky_way_starless)?.let(scene::setMilkyWayBitmap)
        decode(R.drawable.landscape_photorealistic)?.let(scene::setLandscapeBitmap)
        Log.i(
            TAG,
            "stars=${scene.stars.starCount} constellations=${scene.constellationCount} " +
                "setupMs=${(SystemClock.elapsedRealtimeNanos() - setupStart) / 1_000_000}",
        )

        // `az` / `el` extras pin the view instead of following the sensors, so a phone lying on a
        // desk can still be pointed at the sky from adb.
        val fixedView = if (intent.hasExtra("el")) {
            FloatArray(16).also {
                CelestialMatrixCalculator.computeManualRotationMatrix(
                    intent.getFloatExtra("az", 0f).toDouble(), intent.getFloatExtra("el", 0f).toDouble(), it,
                )
            }
        } else {
            null
        }

        tracker = OrientationTracker(this)
        surface.onFrame = { frameTimeNanos ->
            val t0 = SystemClock.elapsedRealtimeNanos()
            if (fixedView != null) fixedView.copyInto(frame.deviceToEnu) else tracker.getLiveMatrix(frame.deviceToEnu)
            @Suppress("DEPRECATION")
            frame.displayRotationDegrees = (display?.rotation ?: 0) * 90
            frame.dtSeconds = if (lastFrameNs == 0L) 0.016f else (frameTimeNanos - lastFrameNs) / 1e9f
            frame.lstDegrees = SiderealTime.calculateLST(SiderealTime.toJulianDate(System.currentTimeMillis()), longitude)
            scene.update(frame)
            onFrameCpuNs += SystemClock.elapsedRealtimeNanos() - t0
            recordFrame(frameTimeNanos)
        }
    }

    override fun onResume() {
        super.onResume()
        tracker.start()
    }

    override fun onPause() {
        tracker.stop()
        super.onPause()
    }

    override fun onDestroy() {
        scene.destroy()
        surface.destroy()
        super.onDestroy()
    }

    private fun decode(id: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        return BitmapFactory.decodeResource(resources, id, options)
    }

    private fun bindCamera(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun recordFrame(frameTimeNanos: Long) {
        if (lastFrameNs != 0L) worstFrameNs = maxOf(worstFrameNs, frameTimeNanos - lastFrameNs)
        lastFrameNs = frameTimeNanos
        if (windowStartNs == 0L) windowStartNs = frameTimeNanos
        frames++
        val elapsed = frameTimeNanos - windowStartNs
        if (elapsed >= 2_000_000_000L) {
            val fps = frames * 1e9 / elapsed
            Log.i(
                TAG,
                "fps=%.1f worstFrameMs=%.1f onFrameCpuUs=%d".format(
                    fps, worstFrameNs / 1e6, onFrameCpuNs / frames / 1000,
                ),
            )
            frames = 0
            windowStartNs = frameTimeNanos
            worstFrameNs = 0
            onFrameCpuNs = 0
        }
    }

    companion object {
        const val TAG = "SkySpike"
    }
}
