package com.dazix.caelis.data.horizon

import com.dazix.caelis.BuildConfig
import com.dazix.caelis.domain.horizon.DemTileKey

/*
 * What the terrain code may write to logcat about where the user is.
 *
 * A tile name is a 1° square, and the tiles the app fetches are the squares around the user, so a
 * release log of them is a coarse location history. Logcat is not readable by other apps, but it is
 * in every bug report a user sends. Debug builds keep the names, which is what the alignment and
 * terrain work on the device needs; a release names nothing. An exception's message can carry the
 * name too (a file path, "tile N50E015 came back as ..."), so a release logs its class instead of
 * the throwable.
 */

/** The tile's name in a debug build, a placeholder in a release. */
internal fun DemTileKey.logName(): String = if (BuildConfig.DEBUG) name else "(tile)"

/** A region's user-given name, which is usually a place, under the same rule. */
internal fun regionLogName(name: String): String = if (BuildConfig.DEBUG) name else "(region)"

/** The throwable to hand to `Log` in a debug build; a release passes null and logs [logClass]. */
internal fun Throwable.inDebug(): Throwable? = takeIf { BuildConfig.DEBUG }

/** The exception's class, safe to log in any build. */
internal val Throwable.logClass: String get() = javaClass.simpleName
