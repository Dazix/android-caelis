package com.dazix.caelis.domain

sealed interface TriggerMode {
    val targetPackage: String?

    data class Timelapse(override val targetPackage: String) : TriggerMode

    data class PeriodicTap(override val targetPackage: String?) : TriggerMode
}
