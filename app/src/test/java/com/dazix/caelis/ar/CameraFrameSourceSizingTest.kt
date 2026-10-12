package com.dazix.caelis.ar

import com.dazix.caelis.ar.scene.CameraBackground
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFrameSourceSizingTest {

    /**
     * The app can hold the whole GPU pool, the unlatched image, and (for the instant a newer image has
     * been acquired and the older one not yet dropped) one more. The camera needs a buffer of its own
     * on top of that, or it drops preview frames and `acquireLatestImage` throws on every vsync.
     */
    @Test
    fun `the reader leaves the camera a buffer when the app holds everything it can`() {
        val heldAtMost = CameraBackground.POOL_SIZE + 1 + 1
        assertTrue(
            "MAX_IMAGES=${CameraFrameSource.MAX_IMAGES} must exceed the $heldAtMost images the app can hold",
            CameraFrameSource.MAX_IMAGES >= heldAtMost + 1,
        )
    }
}
