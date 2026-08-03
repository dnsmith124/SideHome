package fi.palonkorpi.sidehome.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import fi.palonkorpi.sidehome.color.Lch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Keeping the system wallpaper matched to the theme (SID-180).
 *
 * SideHome paints its own background and never shows the system wallpaper — but Android 12's
 * go-home and app-close transitions do, revealing whatever wallpaper predates the launcher before
 * SideHome's window is drawn. The fix is to make the system wallpaper *be* the theme: render the
 * current sky to a bitmap and hand it to [WallpaperManager], for the home and/or lock slot the
 * user approved in the one-time consent modal.
 *
 * Cadence is a perceptual delta, not a schedule: the mood drifts continuously, but
 * `WallpaperManager.setBitmap` writes a file and broadcasts system-wide, so the wallpaper only
 * re-sets once the sky has moved a just-noticeable distance from the frame last set. Fast around
 * dawn and dusk, near-silent through midday and midnight, and free of any WorkManager/alarm —
 * the launcher process is alive whenever the evaluation matters.
 */

/**
 * Perceptual distance between two moods — Euclidean in OKLab, the cartesian form of the
 * launcher's OKLCH. Comparing in the polar form would mis-weigh hue near zero chroma, where a
 * huge hue swing is invisible; in cartesian space that swing collapses to the tiny move it looks
 * like.
 */
fun moodDelta(a: Lch, b: Lch): Double {
    val dl = a.l - b.l
    val da = a.c * cos(a.h) - b.c * cos(b.h)
    val db = a.c * sin(a.h) - b.c * sin(b.h)
    return sqrt(dl * dl + da * da + db * db)
}

/**
 * The re-set gate: roughly a just-noticeable shift of the sky. At the dawn/dusk pace of the solar
 * model this fires a handful of times around each transition and stays quiet the rest of the day.
 */
const val WALLPAPER_DELTA_THRESHOLD = 0.02

/** Whether the wallpaper is due a re-render: never set yet, or drifted past the threshold. */
fun wallpaperDue(current: Lch, lastSet: Lch?): Boolean =
    lastSet == null || moodDelta(current, lastSet) >= WALLPAPER_DELTA_THRESHOLD

/**
 * Set [bitmap] as the wallpaper for the approved slots. Returns whether the set succeeded — the
 * caller records the rendered mood only on success, so a failed set retries on the next delta.
 * `allowBackup = false`: the wallpaper is derived state, regenerated on any device this app runs on.
 */
fun applyWallpaper(context: Context, bitmap: Bitmap, home: Boolean, lock: Boolean): Boolean {
    if (!home && !lock) return false
    var flags = 0
    if (home) flags = flags or WallpaperManager.FLAG_SYSTEM
    if (lock) flags = flags or WallpaperManager.FLAG_LOCK
    return runCatching {
        WallpaperManager.getInstance(context).setBitmap(bitmap, null, false, flags)
    }.isSuccess
}
