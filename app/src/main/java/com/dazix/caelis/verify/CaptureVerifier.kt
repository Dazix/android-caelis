package com.dazix.caelis.verify

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.dazix.caelis.ui.Permissions

/**
 * Answers one question: did the *camera app* save a new frame since the baseline taken just before the
 * tap? Used only when [com.dazix.caelis.domain.SessionConfig.verifyCaptures] is on.
 *
 * ### What counts as proof
 * "The newest row in MediaStore changed" is not proof — a screenshot, a photo arriving in a chat, or
 * anything else that writes an image row would satisfy it and report a shot that never happened. A row
 * counts only when it passes [CameraImageRule]: it has to live where a camera app puts its captures
 * (or be owned by the camera app itself) *and* have been added inside this shot's window. The window
 * start travels inside the baseline token, so the existing three-call shape — [available],
 * [newestToken] before the tap, [hasNewImageSince] after it — did not have to change.
 *
 * ### Degrading
 * Every path degrades silently. Without the photos permission (`READ_MEDIA_IMAGES`, `READ_EXTERNAL_STORAGE`
 * below Android 13, see [Permissions.photos]) or on any provider error [available] is
 * false and [newestToken] returns `null`; the engine then skips verification entirely rather than
 * warning about a photo it simply cannot see. Verification never aborts a session — at worst the engine
 * re-taps once and carries on with a warning. What it must never do is invent a success, so anything
 * unreadable reads as "cannot confirm" ([hasNewImageSince] returns false), never as a capture.
 *
 * Partial media access (`READ_MEDIA_VISUAL_USER_SELECTED` granted, `READ_MEDIA_IMAGES` not) is
 * deliberately treated as unavailable: the user-selected subset cannot contain a photo that does not
 * exist yet, so verification would fail every shot and re-tap the whole session.
 *
 * @param expectedPackage the camera app being driven, when it is known. Rows it owns are accepted
 *   wherever they were saved, and rows owned by some *other* app are rejected outright. Leave it null
 *   (as [com.dazix.caelis.domain.TriggerMode.PeriodicTap] does) and the rule falls back to
 *   where the file lives.
 */
class CaptureVerifier(context: Context, expectedPackage: String? = null) {

    private val context = context.applicationContext
    private val selfPackage: String = this.context.packageName

    /**
     * Set at session start so the rule can use the strongest signal there is. Volatile because the
     * session that writes it and the shot that reads it need not be on the same thread.
     */
    @Volatile
    var expectedPackage: String? = expectedPackage

    /** True when MediaStore images can actually be read right now. */
    fun available(): Boolean =
        context.checkSelfPermission(Permissions.photos()) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Opaque baseline for the shot about to be fired: the moment the window opens, plus the identity of
     * the newest camera row that already existed. `null` only when MediaStore cannot be read at all —
     * an empty camera folder still produces a usable token, because the window alone is enough to tell
     * a new capture from every photo that came before it.
     */
    fun newestToken(): String? {
        if (!available()) return null
        val nowSec = wallClockSeconds()
        val rows = queryRows(minDateAddedSec = null, limit = BASELINE_SCAN_LIMIT) ?: return null
        val newest = rows.firstOrNull {
            CameraImageRule.isPlausibleCameraRow(it, expectedPackage, selfPackage)
        }
        return CaptureToken.encode(nowSec, newest?.id, newest?.dateAddedSec)
    }

    /**
     * True when a row that only the camera could have written appeared since [baseline] was taken.
     *
     * A `null` or unreadable baseline is *not* treated as "anything counts": without a window there is
     * no way to tell this shot's frame from last week's, so the honest answer is "cannot confirm". The
     * engine turns that into one re-tap and a warning, which is the failure the user can act on.
     */
    fun hasNewImageSince(baseline: String?): Boolean {
        if (!available()) return false
        val windowStartSec = CaptureToken.windowStartOf(baseline) ?: return false
        val baselineIdentity = CaptureToken.identityOf(baseline)
        val nowSec = wallClockSeconds()
        val rows = queryRows(
            minDateAddedSec = windowStartSec - CameraImageRule.CLOCK_SKEW_SEC,
            limit = WINDOW_SCAN_LIMIT,
        ) ?: return false
        return rows.any { row ->
            CameraImageRule.accepts(row, expectedPackage, windowStartSec, nowSec, selfPackage) &&
                row.identity() != baselineIdentity
        }
    }

    /**
     * The newest [limit] image rows, newest first, or `null` when the provider could not be read.
     * `null` and "no rows" have to stay distinguishable: one is a failure to look, the other is a
     * successful look at an empty library.
     */
    private fun queryRows(minDateAddedSec: Long?, limit: Int): List<ImageRow>? {
        val uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
        )
        val query = Bundle().apply {
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.Images.Media._ID),
            )
            putInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_DESCENDING,
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            if (minDateAddedSec != null) {
                // Pushed into the provider so a library with ten thousand photos still only ships the
                // handful that could possibly belong to this shot.
                putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.MediaColumns.DATE_ADDED} >= ?",
                )
                putStringArray(
                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    arrayOf(minDateAddedSec.coerceAtLeast(0L).toString()),
                )
            }
        }
        return runCatching {
            context.contentResolver.query(uri, projection, query, null)?.use { cursor ->
                val rows = ArrayList<ImageRow>(cursor.count.coerceAtMost(limit))
                while (cursor.moveToNext()) {
                    rows += ImageRow(
                        id = cursor.getLong(0),
                        dateAddedSec = if (cursor.isNull(1)) 0L else cursor.getLong(1),
                        relativePath = if (cursor.isNull(2)) null else cursor.getString(2),
                        bucket = if (cursor.isNull(3)) null else cursor.getString(3),
                        ownerPackage = if (cursor.isNull(4)) null else cursor.getString(4),
                    )
                }
                rows
            }
        }.onFailure {
            Log.i(TAG, "MediaStore unreadable, verification skipped: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    /** Wall clock, because `DATE_ADDED` is wall-clock seconds; never used to measure a duration. */
    private fun wallClockSeconds(): Long = System.currentTimeMillis() / 1000L

    private companion object {
        const val TAG = "CaptureVerifier"

        /**
         * How deep to look for the newest pre-existing camera photo. A baseline that is missed costs
         * nothing — the window already excludes every older photo — so this only needs to be deep
         * enough to see past a burst of screenshots.
         */
        const val BASELINE_SCAN_LIMIT = 32

        /**
         * How many of the rows added since the window opened to examine. Anything more than a couple is
         * already a camera app writing a burst plus whatever else the device saved in those seconds.
         */
        const val WINDOW_SCAN_LIMIT = 16
    }
}

/** One MediaStore image row, reduced to the columns the rule looks at. */
internal data class ImageRow(
    val id: Long,
    val dateAddedSec: Long,
    val relativePath: String?,
    val bucket: String?,
    val ownerPackage: String?,
) {
    /** `DATE_ADDED` is folded in so a provider that reuses ids still reads as a different row. */
    fun identity(): String = "$id/$dateAddedSec"
}

/**
 * Whether a MediaStore row is plausibly the frame this shot just asked the camera for. Pure, so the
 * rule that decides whether a session is lying to its user is unit-tested rather than argued about.
 */
internal object CameraImageRule {

    /**
     * `DATE_ADDED` is truncated to whole seconds and written by the provider from its own clock, so a
     * row saved in the same second as the baseline can read as marginally older. Small enough that a
     * photo from before the shot cannot slip through.
     */
    const val CLOCK_SKEW_SEC = 3L

    /** Where Android tells camera apps to save captures; `Pictures/`, `Download/` etc. are not it. */
    private const val CAMERA_ROOT = "dcim"

    /**
     * Buckets that live under `DCIM/` on some devices but are never a capture: Samsung puts
     * screenshots and screen recordings there, and a restored/synced folder is full of old photos.
     */
    private val NON_CAMERA_BUCKETS = setOf(
        "screenshots",
        "screenshot",
        "screen recordings",
        "screen_recordings",
        "restored",
        "download",
        "downloads",
    )

    /**
     * Camera folders for the case where `RELATIVE_PATH` is empty — rows the media scanner picked up
     * from a legacy path can arrive with only a bucket name.
     */
    private val CAMERA_BUCKETS = setOf("camera", "100andro", "100media", "dcim", "open camera")

    /**
     * Owners that mean "the provider indexed a file somebody wrote", not "this app created the row".
     * Their rows have to be judged by where the file lives instead.
     */
    private val SCANNER_OWNERS = setOf(
        "android",
        "com.android.providers.media",
        "com.android.providers.media.module",
        "com.google.android.providers.media.module",
    )

    fun accepts(
        row: ImageRow,
        expectedPackage: String?,
        windowStartSec: Long,
        nowSec: Long,
        selfPackage: String? = null,
    ): Boolean = isInWindow(row.dateAddedSec, windowStartSec, nowSec) &&
        isPlausibleCameraRow(row, expectedPackage, selfPackage)

    /**
     * The row has to have been added after the shutter was pressed and not in the future. The upper
     * bound matters: a row carrying a bogus far-future `DATE_ADDED` would otherwise satisfy every
     * window of every shot for the rest of the session.
     */
    fun isInWindow(dateAddedSec: Long, windowStartSec: Long, nowSec: Long): Boolean =
        dateAddedSec > 0L &&
            dateAddedSec >= windowStartSec - CLOCK_SKEW_SEC &&
            dateAddedSec <= nowSec + CLOCK_SKEW_SEC

    /**
     * Could the camera app have written this row?
     *
     * Ownership decides it when both sides are known: the camera app's own rows count wherever it saves
     * them, and another app's rows never count no matter where they landed. Otherwise — owner redacted,
     * unknown, or the media scanner's — the file's location decides: under `DCIM/`, and not in one of
     * the folders that live there without being captures.
     */
    fun isPlausibleCameraRow(
        row: ImageRow,
        expectedPackage: String?,
        selfPackage: String? = null,
    ): Boolean {
        val owner = normalize(row.ownerPackage)
        val expected = normalize(expectedPackage)
        if (owner != null) {
            if (expected != null && owner == expected) return true
            // This app writes no images of its own; the only thing that ever creates a row under its
            // name is the instrumented suite's stand-in camera, and refusing it would mean the
            // on-device verification test stopped testing verification.
            if (selfPackage != null && owner == normalize(selfPackage)) return true
            if (expected != null && owner !in SCANNER_OWNERS) return false
        }

        val bucket = normalize(row.bucket)
        if (bucket != null && bucket in NON_CAMERA_BUCKETS) return false

        val segments = normalize(row.relativePath)?.split('/')?.filter { it.isNotEmpty() }.orEmpty()
        if (segments.isNotEmpty()) return segments.first() == CAMERA_ROOT
        return bucket != null && bucket in CAMERA_BUCKETS
    }

    private fun normalize(value: String?): String? =
        value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}

/**
 * The baseline handed to [CaptureVerifier.hasNewImageSince]. Opaque to the engine, which only ever
 * stores it and hands it back, so it can carry both halves of the answer: when this shot's window
 * opened, and which camera row was already the newest at that instant.
 */
internal object CaptureToken {

    private const val PREFIX = "v2"
    private const val NONE = "-"
    private const val SEP = '|'

    fun encode(windowStartSec: Long, id: Long?, dateAddedSec: Long?): String =
        listOf(
            PREFIX,
            windowStartSec.toString(),
            id?.toString() ?: NONE,
            dateAddedSec?.toString() ?: NONE,
        ).joinToString(SEP.toString())

    /** The instant the shot's window opened, or `null` when the token is missing or unrecognised. */
    fun windowStartOf(token: String?): Long? {
        val parts = split(token) ?: return null
        return parts[1].toLongOrNull()
    }

    /**
     * The newest camera row at baseline time in [ImageRow.identity] form, or `null` when there was
     * none — which is a perfectly ordinary state (an empty camera folder), not an error.
     */
    fun identityOf(token: String?): String? {
        val parts = split(token) ?: return null
        if (parts[2] == NONE) return null
        return "${parts[2]}/${parts[3]}"
    }

    private fun split(token: String?): List<String>? {
        val parts = (token ?: return null).split(SEP)
        if (parts.size != 4 || parts[0] != PREFIX) return null
        return parts
    }
}
