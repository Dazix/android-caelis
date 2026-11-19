package com.dazix.caelis.verify

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.dazix.caelis.ui.Permissions
import java.util.concurrent.atomic.AtomicLong

/**
 * Answers one question: did the camera app save a new frame since the baseline taken just before the
 * tap? Used only when [com.dazix.caelis.domain.SessionConfig.verifyCaptures] is on. The engine sees
 * three calls — [available], [newestToken] before the tap, [hasNewImageSince] after it — and two ways
 * of answering them hide behind those calls, chosen by the platform level.
 *
 * ### Android 13 and newer: insert notifications, no permission
 * Google Play allows READ_MEDIA_IMAGES only to apps whose core purpose is the photo library, and
 * refused it for a check that the photo picker cannot do (the frame does not exist yet when anything
 * could be picked). So from Android 13 the app declares no photos permission at all and registers a
 * [ContentObserver] on the images collection instead: MediaStore delivers its insert notifications
 * to an app that cannot read the row (on a Pixel 10 Pro with Google Camera every frame's INSERT
 * arrived within a second of `DATE_ADDED`, its rows unreadable). Any image INSERT after the baseline
 * counts ([ImageInsertLog]). That is weaker than the rule below — whose row it was cannot be asked
 * without the permission, so a screenshot taken during the shot passes as the frame — which is the
 * trade the user accepted for keeping the check at all. Android defers and merges notifications for a
 * cached process; a session never is one, because the bound accessibility service keeps it up.
 *
 * ### Android 11 and 12: the MediaStore rows
 * "The newest row in MediaStore changed" is not proof — a screenshot, a photo arriving in a chat, or
 * anything else that writes an image row would satisfy it and report a shot that never happened. A row
 * counts only when it passes [CameraImageRule]: it has to live where a camera app puts its captures
 * (or be owned by the camera app itself) *and* have been added inside this shot's window. The window
 * start travels inside the baseline token. This needs READ_EXTERNAL_STORAGE ([Permissions.photos]),
 * which the manifest caps at API 32.
 *
 * ### Degrading
 * Every path degrades silently. Without the photos permission below Android 13 [available] is false
 * and the engine skips verification rather than warning about a photo it simply cannot see. A
 * provider error or an observer that could not be registered makes [newestToken] return `null`, which
 * leaves that shot unverified. Verification never aborts a session — at worst the engine re-taps once
 * and carries on with a warning. What it must never do is invent a success, so anything unreadable
 * reads as "cannot confirm" ([hasNewImageSince] returns false), never as a capture.
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

    private val usesInsertNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private val inserts = ImageInsertLog()

    /** Registered by the first verified shot of a session, dropped by [close]. */
    private var observer: ContentObserver? = null

    /**
     * True when a new capture can be seen at all: always from Android 13 (no permission is involved),
     * below it only with the photos permission granted.
     */
    fun available(): Boolean =
        usesInsertNotifications ||
            context.checkSelfPermission(Permissions.photos()) == PackageManager.PERMISSION_GRANTED

    /**
     * Opaque baseline for the shot about to be fired. From Android 13 it is the insert count so far;
     * below it, the moment the window opens plus the identity of the newest camera row that already
     * existed. `null` only when nothing can be watched — an empty camera folder still produces a usable
     * token, because the window alone is enough to tell a new capture from every photo before it.
     */
    fun newestToken(): String? {
        if (!available()) return null
        if (usesInsertNotifications) {
            return if (startWatching()) CaptureToken.encodeInserts(inserts.mark()) else null
        }
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
        if (usesInsertNotifications) {
            val mark = CaptureToken.insertMarkOf(baseline) ?: return false
            return inserts.hasInsertAfter(mark)
        }
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

    /** Stops watching for inserts. The next verified shot registers the observer again. */
    @Synchronized
    fun close() {
        observer?.let { registered ->
            runCatching { context.contentResolver.unregisterContentObserver(registered) }
        }
        observer = null
    }

    /**
     * Registers the insert observer once per session. No handler: the callback runs on a binder
     * thread and only bumps [inserts], which is atomic, so the main thread never waits for it.
     */
    @Synchronized
    private fun startWatching(): Boolean {
        if (observer != null) return true
        val created = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int) {
                inserts.record(flags)
            }
        }
        return runCatching {
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                true,
                created,
            )
            observer = created
        }.onFailure {
            Log.i(TAG, "MediaStore not observable, verification skipped: ${it.javaClass.simpleName}")
        }.isSuccess
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
 * Counts the image inserts MediaStore announced, for the Android 13+ path. A notification is all there
 * is: the row behind it cannot be read without the photos permission, so whose photo it was is not
 * known. Only INSERT counts — the camera's own follow-up UPDATEs (the pending row finished) and any
 * delete would otherwise make one frame look like several, or a deleted photo like a new one.
 */
internal class ImageInsertLog {

    private val count = AtomicLong(0L)

    /** Called with the flags of every change notification on the images collection. */
    fun record(flags: Int) {
        if (flags and ContentResolver.NOTIFY_INSERT != 0) count.incrementAndGet()
    }

    /** The baseline for a shot: how many inserts had arrived before its tap. */
    fun mark(): Long = count.get()

    fun hasInsertAfter(mark: Long): Boolean = count.get() > mark
}

/**
 * The baseline handed to [CaptureVerifier.hasNewImageSince]. Opaque to the engine, which only ever
 * stores it and hands it back, so it can carry both halves of the answer: when this shot's window
 * opened, and which camera row was already the newest at that instant.
 */
internal object CaptureToken {

    private const val PREFIX = "v2"
    private const val INSERTS_PREFIX = "i1"
    private const val NONE = "-"
    private const val SEP = '|'

    /** The Android 13+ baseline: the [ImageInsertLog.mark] taken before the tap. */
    fun encodeInserts(mark: Long): String = "$INSERTS_PREFIX$SEP$mark"

    /** The insert mark of an Android 13+ token, or `null` for any other or a missing one. */
    fun insertMarkOf(token: String?): Long? {
        val parts = (token ?: return null).split(SEP)
        if (parts.size != 2 || parts[0] != INSERTS_PREFIX) return null
        return parts[1].toLongOrNull()
    }

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
