package com.dazix.caelis.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.roundToInt

/**
 * Reads the battery level on demand only — the engine asks once per shot, so there is nothing to
 * poll and no receiver to keep registered. `null` means "unknown right now", which
 * [com.dazix.caelis.domain.SessionPlanner] treats as "do not stop".
 */
class BatteryMonitor(context: Context) {

    private val context = context.applicationContext

    /** Charge level in 0..100, or `null` when the platform will not say. */
    fun percent(): Int? = fromProperty() ?: fromStickyBroadcast()

    private fun fromProperty(): Int? = runCatching {
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
    }.getOrNull()

    /**
     * Fallback for devices whose fuel gauge does not expose BATTERY_PROPERTY_CAPACITY. Reading the
     * sticky ACTION_BATTERY_CHANGED with a null receiver is a one-shot read, not a registration.
     */
    private fun fromStickyBroadcast(): Int? = runCatching {
        val intent: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return@runCatching null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return@runCatching null
        (level * 100f / scale).roundToInt().coerceIn(0, 100)
    }.getOrNull()
}
