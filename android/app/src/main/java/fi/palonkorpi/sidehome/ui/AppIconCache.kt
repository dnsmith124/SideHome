package fi.palonkorpi.sidehome.ui

import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Rasterised real app icons, for grouped mode's peeking bubbles (SID-23, SPEC §2b Mode C) — the one
 * place in the launcher that draws an app's *own* icon instead of our thin-line glyph.
 *
 * Compose cannot paint a `Drawable`, and there is no image-loading library in this project (nor may
 * one be added), so each icon is rasterised by hand once and kept as an [ImageBitmap].
 *
 * **Why a process-wide singleton rather than a `remember` inside the drawer.** An icon is a property
 * of the *package*, not of any screen — the same argument [rememberBadgedPackages] makes for the
 * notification dots, and this file follows that precedent deliberately. It is also the only place
 * that survives what actually happens: the drawer **remounts every time it opens**, so a `remember`
 * in it would re-rasterise all forty icons on every summon, and flipping List → Bubbles → List would
 * do it again. On a Helio A22 an adaptive-icon rasterise is not free, and forty of them on the main
 * thread is a visible hitch — which is the whole reason for both the cache and the [Dispatchers.IO]
 * hop below.
 *
 * Nothing here retains a `Context`: the [PackageManager] is read from the composition at call time
 * and handed to the loader, and only bitmaps are held. They are tiny (a 42dp icon at this density is
 * ~54px → ~11KB) and there are at most four per bucket, so there is no eviction policy — the cache
 * tops out around forty entries and is bounded by the number of installed apps.
 */
object AppIconCache {

    /**
     * One state slot per icon, rather than a single [androidx.compose.runtime.snapshots.SnapshotStateMap]
     * of them all. A snapshot map records reads of the *whole* map, so one icon landing would
     * invalidate every peek icon on screen — ~30 recompositions per arrival, ~30 times over, during
     * the very first frames of the drawer opening. A slot each means an icon's arrival recomposes
     * exactly the one `Image` that is waiting for it.
     */
    private val slots = ConcurrentHashMap<String, MutableState<ImageBitmap?>>()

    /**
     * The icon for [packageName] at [sizePx], or null while it is still being rasterised (the caller
     * holds the slot with a placeholder so the cluster cannot reflow underneath it). Cached across
     * drawer opens and mode flips; a package whose icon fails to load stays null and is retried on
     * the next composition, which is the honest answer for an app that vanished mid-session.
     */
    @Composable
    fun rememberIcon(packageName: String, sizePx: Int): ImageBitmap? {
        val pm = LocalContext.current.packageManager
        // Size is part of the key: the same icon at two sizes is two rasters, and reusing one for the
        // other would either blur it or waste memory.
        val key = "$packageName@$sizePx"
        // Creating the slot during composition is a side effect, but an idempotent one — the same key
        // always yields the same state object, so a recomposition sees exactly what the last one did.
        val slot = slots.computeIfAbsent(key) { mutableStateOf<ImageBitmap?>(null) }
        val icon by slot
        LaunchedEffect(key) {
            // Re-checked here rather than trusted from composition: two bubbles could ask at once, and
            // this is the read that decides whether the expensive part runs at all.
            if (slot.value == null) {
                val bitmap = withContext(Dispatchers.IO) { rasterise(pm, packageName, sizePx) }
                // Back on the composition's dispatcher, so the state write lands on the main thread.
                if (bitmap != null) slot.value = bitmap
            }
        }
        return icon
    }

    /**
     * The expensive bit, off the main thread: an IPC to `PackageManager`, then an adaptive-icon
     * draw into a bitmap. Null on any failure — an app can be uninstalled between the drawer listing
     * it and this running, and a missing icon is a placeholder, never a crash.
     */
    private fun rasterise(pm: PackageManager, packageName: String, sizePx: Int): ImageBitmap? =
        runCatching {
            pm.getApplicationIcon(packageName)
                .toBitmap(width = sizePx, height = sizePx, config = Bitmap.Config.ARGB_8888)
                .asImageBitmap()
        }.getOrNull()
}
