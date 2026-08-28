package com.dazix.caelis.monitor

import android.content.Context
import android.os.Environment
import android.os.StatFs

/**
 * Reads free space on the primary shared storage on demand only — the volume the camera app writes
 * its frames to. `null` means "unknown right now", which
 * [com.dazix.caelis.domain.SessionPlanner] treats as "do not stop".
 */
class StorageMonitor(context: Context) {

    private val context = context.applicationContext

    /** Bytes still writable on the primary shared volume, or `null` when it cannot be measured. */
    fun freeBytes(): Long? {
        for (path in candidatePaths()) {
            val bytes = runCatching { StatFs(path).availableBytes }.getOrNull()
            if (bytes != null && bytes >= 0L) return bytes
        }
        return null
    }

    /**
     * The shared volume root first; the app-private directory on the same volume is the fallback for
     * the case where the root is not stat-able for this process. Both live on the same filesystem,
     * so the number the user sees is the same either way.
     */
    private fun candidatePaths(): List<String> = buildList {
        runCatching {
            if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                @Suppress("DEPRECATION")
                add(Environment.getExternalStorageDirectory().absolutePath)
            }
        }
        runCatching { context.getExternalFilesDir(null)?.absolutePath }.getOrNull()?.let(::add)
        add(context.filesDir.absolutePath)
    }
}
