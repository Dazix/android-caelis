package com.dazix.caelis.debug

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * The tap log format, owned by the writer so the tests parse exactly what [FakeCameraActivity]
 * emits.
 *
 * One line per tap, no header: `<elapsedRealtime ms>,<source>`.
 */
object FakeCameraTapLog {

    const val FILE_NAME = "fake_camera_taps.csv"

    /** Tap arrived as an accessibility `ACTION_CLICK` on the labeled shutter node. */
    const val SOURCE_NODE = "node"

    /** Tap arrived as a dispatched gesture that landed on the labeled shutter. */
    const val SOURCE_GESTURE = "gesture"

    /** Tap arrived as a dispatched gesture on the unlabeled surface: coordinate fallback. */
    const val SOURCE_COORDINATE = "coord"

    private const val TAG = "FakeCameraTapLog"

    data class Tap(val elapsedRealtimeMs: Long, val source: String)

    fun file(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)

    @Synchronized
    fun clear(context: Context) {
        try {
            FileOutputStream(file(context), false).use { it.flush() }
        } catch (e: Exception) {
            Log.e(TAG, "could not clear tap log", e)
        }
    }

    @Synchronized
    fun append(context: Context, elapsedRealtimeMs: Long, source: String) {
        try {
            FileOutputStream(file(context), true).use { out ->
                out.write("$elapsedRealtimeMs,$source\n".toByteArray())
                out.flush()
                out.fd.sync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not append tap", e)
        }
    }

    /** Malformed or partially written lines are skipped rather than failing a whole test. */
    fun read(context: Context): List<Tap> {
        val f = file(context)
        if (!f.isFile) return emptyList()
        return try {
            f.readLines().mapNotNull { line ->
                val parts = line.trim().split(',')
                if (parts.size < 2) return@mapNotNull null
                val at = parts[0].toLongOrNull() ?: return@mapNotNull null
                Tap(at, parts[1])
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not read tap log", e)
            emptyList()
        }
    }

    fun gapsMs(taps: List<Tap>): List<Long> =
        if (taps.size < 2) emptyList()
        else (1 until taps.size).map { taps[it].elapsedRealtimeMs - taps[it - 1].elapsedRealtimeMs }
}
