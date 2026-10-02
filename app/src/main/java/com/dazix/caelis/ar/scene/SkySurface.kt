package com.dazix.caelis.ar.scene

import android.content.Context
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.dazix.caelis.domain.arframe.SkyFrameMath
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.Material
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One Filament engine, scene and camera drawing into a [SurfaceView], with its own vsync loop.
 *
 * The surface is translucent and sits as a media overlay: above the CameraX preview's own
 * SurfaceView, below the window, so the Compose HUD still draws on top of the sky. Post-processing
 * is off on purpose — tone mapping would shift every unlit colour away from the palette the Canvas
 * renderer used, and night vision depends on those colours being exact.
 *
 * Everything here runs on the main thread, which is where Choreographer delivers frames.
 */
class SkySurface(private val context: Context, surfaceView: SurfaceView) {

    val engine: Engine = Engine.create()
    val scene: Scene = engine.createScene()
    private val renderer: Renderer = engine.createRenderer()
    private val view: View = engine.createView()
    private val cameraEntity: Int = EntityManager.get().create()
    val camera: Camera = engine.createCamera(cameraEntity)

    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private var swapChain: SwapChain? = null
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var fovHorizontalDegrees = 60.0
    private var destroyed = false

    private val cameraModel = FloatArray(16)
    private val materialGlobal = FloatArray(4)

    /** Called once per vsync before the frame is rendered; the place to write transforms and uniforms. */
    var onFrame: ((frameTimeNanos: Long) -> Unit)? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (destroyed) return
            Choreographer.getInstance().postFrameCallback(this)
            onFrame?.invoke(frameTimeNanos)
            val chain = swapChain ?: return
            if (uiHelper.isReadyToRender && renderer.beginFrame(chain, frameTimeNanos)) {
                renderer.render(view)
                renderer.endFrame()
            }
        }
    }

    init {
        view.scene = scene
        view.camera = camera
        view.blendMode = View.BlendMode.TRANSLUCENT
        view.isPostProcessingEnabled = false
        renderer.clearOptions = Renderer.ClearOptions().apply {
            clear = true
            clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        }
        setNightVision(active = false, colors = NightVisionColors.NONE)

        uiHelper.isOpaque = false
        uiHelper.isMediaOverlay = true
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface, uiHelper.swapChainFlags)
            }

            override fun onDetachedFromSurface() {
                swapChain?.let {
                    engine.destroySwapChain(it)
                    // The surface is about to go away; the swap chain must be gone before it does.
                    engine.flushAndWait()
                }
                swapChain = null
            }

            override fun onResized(width: Int, height: Int) {
                viewportWidth = width.coerceAtLeast(1)
                viewportHeight = height.coerceAtLeast(1)
                view.viewport = Viewport(0, 0, viewportWidth, viewportHeight)
                applyProjection()
            }
        }
        uiHelper.attachTo(surfaceView)
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /** Horizontal FOV of the screen, as every AR screen already computes it. */
    fun setFovHorizontal(degrees: Double) {
        if (degrees == fovHorizontalDegrees) return
        fovHorizontalDegrees = degrees
        applyProjection()
    }

    /** Row-major device -> ENU rotation (as SensorManager produces it) plus display rotation. */
    fun setCameraPose(deviceToEnu: FloatArray, displayRotationDegrees: Int) {
        SkyFrameMath.cameraModelMatrix(deviceToEnu, displayRotationDegrees, cameraModel)
        camera.setModelMatrix(cameraModel)
    }

    /**
     * Night vision for every material at once, one place instead of a ColorFilter per Paint. The
     * Canvas renderer used four different colours and so do the materials:
     * - global 0 = shader tint (the AGSL `nightVisionTint`) + active flag in w;
     * - global 1 = palette secondary (star cores);
     * - global 2 = palette primary (star glow, the SRC_IN sprite filter);
     * - global 3 = the MULTIPLY art filter colour (pure red for the red theme, primary otherwise).
     */
    fun setNightVision(active: Boolean, colors: NightVisionColors) {
        setGlobal(0, colors.shaderTint, if (active) 1f else 0f)
        setGlobal(1, colors.secondary, 1f)
        setGlobal(2, colors.primary, 1f)
        setGlobal(3, colors.artMultiply, 1f)
    }

    private fun setGlobal(index: Int, rgb: FloatArray, w: Float) {
        materialGlobal[0] = rgb[0]; materialGlobal[1] = rgb[1]; materialGlobal[2] = rgb[2]
        materialGlobal[3] = w
        view.setMaterialGlobal(index, materialGlobal)
    }

    /** Loads a compiled material from the generated assets (`<name>.filamat`). */
    fun loadMaterial(name: String): Material {
        val bytes = context.assets.open("$name.filamat").use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes).flip()
        return Material.Builder().payload(buffer, buffer.remaining()).build(engine)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        uiHelper.detach()
        swapChain?.let { engine.destroySwapChain(it) }
        swapChain = null
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroy()
    }

    private fun applyProjection() {
        val aspect = viewportWidth.toDouble() / viewportHeight
        camera.setProjection(fovHorizontalDegrees, aspect, NEAR, FAR, Camera.Fov.HORIZONTAL)
    }

    companion object {
        /** Everything celestial sits on a sphere of this radius around the camera. */
        const val SKY_RADIUS = 100f
        private const val NEAR = 0.5
        private const val FAR = 400.0

        init {
            Filament.init()
        }
    }
}
