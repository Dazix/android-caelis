package com.dazix.caelis.domain.nightsky

/**
 * Whether Night Sky needs the camera at all.
 *
 * Its picture is drawn under the sky, and two layers cover it completely: the planetarium clears to
 * an opaque sky, and the Milky Way panorama has no alpha, so it is opaque over the whole sphere. With
 * either on, nothing of the camera reaches the screen, and running it anyway cost more than battery:
 * a picture on screen poses the sky at its images' instants, so the stars moved only as often as the
 * camera delivered (15-30 times a second at night, measured on a Pixel 7 Pro) instead of every vsync.
 */
object NightSkyCameraPolicy {

    fun isPictureVisible(opaqueBackground: Boolean, milkyWayVisible: Boolean): Boolean =
        !opaqueBackground && !milkyWayVisible
}
