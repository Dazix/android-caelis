package com.dazix.caelis.domain.nightsky

/**
 * Whether Night Sky needs the camera at all.
 *
 * The camera picture belongs to planetarium mode alone: outside it the sky is a dark dome with the
 * stars (and the Milky Way, if it is on), never the camera. Even in planetarium mode the Milky Way
 * panorama, which has no alpha and is opaque over the whole sphere, would cover the picture
 * completely, so with it on the camera stays off too. Running the camera unseen cost more than
 * battery: a picture on screen poses the sky at its images' instants, so the stars moved only as
 * often as the camera delivered (15-30 times a second at night, measured on a Pixel 7 Pro) instead
 * of every vsync.
 *
 * Under a virtual place the camera never runs: it shows where the phone is, and the stars, horizon
 * and terrain are those of somewhere else. Planetarium mode then keeps its opaque fill.
 */
object NightSkyCameraPolicy {

    fun isPictureVisible(planetariumMode: Boolean, milkyWayVisible: Boolean, virtualPlace: Boolean = false): Boolean =
        planetariumMode && !milkyWayVisible && !virtualPlace
}
