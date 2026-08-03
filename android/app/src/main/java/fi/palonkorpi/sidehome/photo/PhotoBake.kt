package fi.palonkorpi.sidehome.photo

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.media.ExifInterface
import android.net.Uri
import fi.palonkorpi.sidehome.color.Lch
import fi.palonkorpi.sidehome.color.argbToOklab
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Photo theme's bake pipeline (SID-43).
 *
 * Everything expensive happens **once, at pick time** — never per frame. The chosen image is
 * decoded (EXIF-upright), framed to the screen through the positioner's transform, and baked
 * twice:
 *
 *  1. **Crisp** — the photo with soft scrim gradients baked behind the fixed home regions (the
 *     clock/date/glance band across the top, the favourites column down the left), so home text
 *     holds legible on arbitrary photo detail while the rest of the image stays untouched.
 *  2. **Blurred** — the whole photo blurred and gently dimmed. This is what the frameless
 *     overlays (drawer, folder, strip) stand on: the backdrop recedes to it, per the photo-theme
 *     carve-out in DECISIONS (Overlay + colour model).
 *
 * Both land on disk as JPEGs and are loaded as plain bitmaps — the A22 never blurs live; the
 * only runtime motion is a crossfade between two static layers. The mood (ink/accent source)
 * is the image's average OKLab, frozen all day.
 *
 * The pixel operations are pure `IntArray` functions so the geometry is unit-testable without
 * a device; the Bitmap layer is a thin wrapper around them.
 */

/** On-disk names, in `filesDir`. A re-pick overwrites all three; `photoGeneration` is the cache
 *  key. The clean crop survives so the zones can rebake lazily (the home's row count changed)
 *  and so the lock wallpaper can be the photo itself, zones and all left out. */
private const val CRISP_FILE = "photo_crisp.jpg"
private const val BLUR_FILE = "photo_blur.jpg"
private const val SOURCE_FILE = "photo_source.jpg"

/** The overlay blur works at 1/[BLUR_DOWNSCALE] scale: a box blur on a small image, scaled back
 *  up with filtering, reads as a wide soft gaussian at a fraction of the work. */
private const val BLUR_DOWNSCALE = 4
private const val BLUR_RADIUS = 6
private const val BLUR_PASSES = 3

/** Bump when the bake's *look* changes (the fade, the scrims): the launcher rebakes lazily from
 *  the stored crop when its stored style trails this, so a taste fix reaches installs without a
 *  re-pick. */
const val BAKE_STYLE_VERSION = 4

/** How strongly the overlay bake is pushed toward the ink's ground (0..1). This is the app
 *  library's and the weather pane's whole floor — their text is full-strength ink on exactly
 *  this — so it is a decisive wash, not a tint: whatever the photo held, the blurred ground
 *  lands far enough toward the ink's opposite that contrast holds everywhere (round 6). */
private const val BLUR_DIM_ALPHA = 0.45f

/** The crisp bake's whole treatment (device pass round 7 — the item-shaped blur blocks read as
 *  ugly however soft): **one linear gradient from the left edge, full height** — darkening or
 *  lightening toward the ink's ground, fading to nothing by [EDGE_FADE_REACH]. No blur on the
 *  home at all; the overlay bake keeps its frost for the library and the weather. */
private const val EDGE_FADE_ALPHA = 0.45f
private const val EDGE_FADE_REACH = 0.78f

/** The ink's ground is the left band — where the clock, date and labels all stand — so the ink
 *  decision samples exactly that, not a whole-image average a bright right half can outvote. */
private const val INK_REGION_FRAC = 0.55f

/** The extraction's sample grid — 64×64 is 4096 samples, plenty for an average. */
private const val SAMPLE_SIZE = 64

/**
 * The average OKLab lightness of the pixels inside [zones] (px rects, `[l,t,r,b]`), sampled on a
 * [step] grid. **This, not the whole-image average, decides the ink** (device pass round 6): the
 * text stands in the zones, and a photo that averages bright can still hold a dark subject exactly
 * there — dark ink on a navy shirt was invisible. Falls back to 0.5 when the zones sample nothing.
 */
fun zoneLightness(pixels: IntArray, w: Int, h: Int, zones: List<FloatArray>, step: Int = 8): Double {
    var sum = 0.0
    var n = 0
    for (z in zones) {
        val left = z[0].toInt().coerceIn(0, w - 1)
        val top = z[1].toInt().coerceIn(0, h - 1)
        val right = z[2].toInt().coerceIn(0, w)
        val bottom = z[3].toInt().coerceIn(0, h)
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                sum += argbToOklab(pixels[y * w + x])[0]
                n += 1
                x += step
            }
            y += step
        }
    }
    return if (n == 0) 0.5 else sum / n
}

/**
 * The image's mood: the average of its pixels in OKLab, returned polar. An average (not a
 * dominant-colour cluster) on purpose: the mood only steers ink and accent, and the average is
 * what the ink actually sits on once the image is everywhere behind it.
 */
fun averageMood(pixels: IntArray): Lch {
    require(pixels.isNotEmpty()) { "no pixels to average" }
    var l = 0.0
    var a = 0.0
    var b = 0.0
    for (px in pixels) {
        val lab = argbToOklab(px)
        l += lab[0]
        a += lab[1]
        b += lab[2]
    }
    val n = pixels.size.toDouble()
    l /= n; a /= n; b /= n
    return Lch(l, hypot(a, b), atan2(b, a))
}

/**
 * One separable box-blur pass over packed ARGB pixels, sliding-window so a pass is O(n) whatever
 * the radius. Alpha is carried through untouched (photo pixels are opaque). Callers stack
 * [BLUR_PASSES] passes — three box passes approximate a gaussian.
 */
fun boxBlurPass(px: IntArray, w: Int, h: Int, radius: Int): IntArray {
    if (radius <= 0 || px.isEmpty()) return px.copyOf()
    val tmp = IntArray(px.size)
    val out = IntArray(px.size)
    blurAxis(px, tmp, w, h, radius, horizontal = true)
    blurAxis(tmp, out, h, w, radius, horizontal = false)
    return out
}

/** One axis of [boxBlurPass]. [lanes]×[len] traversal: rows when [horizontal], columns when not. */
private fun blurAxis(src: IntArray, dst: IntArray, len: Int, lanes: Int, radius: Int, horizontal: Boolean) {
    val window = 2 * radius + 1
    for (lane in 0 until lanes) {
        var sumR = 0; var sumG = 0; var sumB = 0
        fun idx(i: Int): Int = if (horizontal) lane * len + i else i * lanes + lane
        // Prime the window with the edge pixel repeated, so borders don't darken.
        for (i in -radius..radius) {
            val p = src[idx(i.coerceIn(0, len - 1))]
            sumR += (p shr 16) and 0xFF; sumG += (p shr 8) and 0xFF; sumB += p and 0xFF
        }
        for (i in 0 until len) {
            dst[idx(i)] = (0xFF shl 24) or
                ((sumR / window) shl 16) or ((sumG / window) shl 8) or (sumB / window)
            val leaving = src[idx((i - radius).coerceIn(0, len - 1))]
            val entering = src[idx((i + radius + 1).coerceIn(0, len - 1))]
            sumR += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
            sumG += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
            sumB += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
}

/**
 * The positioner's frame as an affine map (SID-43 crop flow, grown a rotation in round 9): the
 * source cover-fitted to the screen, then zoomed by [zoom], rotated by [rotDeg] degrees, and
 * dragged by ([panX], [panY]) screen px — all about the screen centre, mirroring the positioner's
 * `graphicsLayer` exactly, so "Use photo" bakes precisely the frame that was on screen. A rotated
 * frame is no longer a rectangle of the source, which is why this is a matrix and not a crop
 * window. Returns the nine row-major values [android.graphics.Matrix.setValues] takes; pure so
 * the geometry the bake renders with is the geometry the tests pin.
 */
fun frameMatrix(
    srcW: Int,
    srcH: Int,
    outW: Int,
    outH: Int,
    zoom: Float,
    panX: Float,
    panY: Float,
    rotDeg: Float,
): FloatArray {
    val cover = maxOf(outW.toDouble() / srcW, outH.toDouble() / srcH)
    val z = zoom.toDouble().coerceAtLeast(1.0)
    val s = cover * z
    val rad = Math.toRadians(rotDeg.toDouble())
    val c = cos(rad)
    val n = sin(rad)
    // Cover-fit places the source at (bx, by); the layer transform then zooms and rotates about
    // the screen centre (cx, cy) and slides by the pan: p' = R·z·(cover·p + b − C) + C + pan.
    val bx = (outW - srcW * cover) / 2.0
    val by = (outH - srcH * cover) / 2.0
    val cx = outW / 2.0
    val cy = outH / 2.0
    val tx = z * ((bx - cx) * c - (by - cy) * n) + cx + panX
    val ty = z * ((bx - cx) * n + (by - cy) * c) + cy + panY
    return floatArrayOf(
        (s * c).toFloat(), (-s * n).toFloat(), tx.toFloat(),
        (s * n).toFloat(), (s * c).toFloat(), ty.toFloat(),
        0f, 0f, 1f,
    )
}

/**
 * The smallest [frameMatrix] zoom at which a source rotated by [rotDeg] still covers the whole
 * screen — the screen's corners must stay inside the tilted photo, so the photo has to span the
 * screen's bounding box as measured in its own rotated frame. 0° is exactly the cover fit (1).
 */
fun rotatedMinZoom(srcW: Int, srcH: Int, outW: Int, outH: Int, rotDeg: Float): Float {
    val cover = maxOf(outW.toDouble() / srcW, outH.toDouble() / srcH)
    val rad = Math.toRadians(rotDeg.toDouble())
    val cosA = abs(cos(rad))
    val sinA = abs(sin(rad))
    val needW = outW * cosA + outH * sinA
    val needH = outW * sinA + outH * cosA
    return (maxOf(needW / srcW, needH / srcH) / cover).toFloat()
}

/**
 * The pan clamp under rotation: a drag may spend at most the photo's overhang beyond the screen,
 * and that overhang is a box in the *photo's* rotated frame — so the pan is carried there,
 * clamped per axis, and carried back. Returns the clamped `[panX, panY]`; 0° reduces to the
 * plain per-axis overhang clamp the unrotated positioner had.
 */
fun clampFramePan(
    srcW: Int,
    srcH: Int,
    outW: Int,
    outH: Int,
    zoom: Float,
    rotDeg: Float,
    panX: Float,
    panY: Float,
): FloatArray {
    val cover = maxOf(outW.toDouble() / srcW, outH.toDouble() / srcH)
    val s = cover * zoom.toDouble().coerceAtLeast(1.0)
    val rad = Math.toRadians(rotDeg.toDouble())
    val cosA = abs(cos(rad))
    val sinA = abs(sin(rad))
    val maxX = ((srcW * s - (outW * cosA + outH * sinA)) / 2.0).coerceAtLeast(0.0)
    val maxY = ((srcH * s - (outW * sinA + outH * cosA)) / 2.0).coerceAtLeast(0.0)
    val c = cos(rad)
    val n = sin(rad)
    val ux = (c * panX + n * panY).coerceIn(-maxX, maxX)
    val uy = (-n * panX + c * panY).coerceIn(-maxY, maxY)
    return floatArrayOf((c * ux - n * uy).toFloat(), (n * ux + c * uy).toFloat())
}

/** The whole pick-time pipeline. Heavy — call off the main thread. Returns the extracted mood,
 *  or null when the image can't be read; on null nothing on disk has changed. */
object PhotoBake {

    /** Decode the pick for the positioner — at up to [reqW]×[reqH] so a zoom has real pixels to
     *  spend. The bake later crops *this* bitmap; the URI's read grant only needs to reach here. */
    fun loadSource(context: Context, uri: Uri, reqW: Int, reqH: Int): Bitmap? =
        runCatching { decodeScaled(context, uri, reqW, reqH) }.getOrNull()

    /** Bake the positioner's frame of [source] — its zoom, pan and rotation, replayed through
     *  [frameMatrix] onto a screen-sized canvas. [source] is left alive — the positioner that
     *  handed it over owns it. */
    fun bake(
        context: Context,
        source: Bitmap,
        widthPx: Int,
        heightPx: Int,
        zoom: Float,
        panX: Float,
        panY: Float,
        rotDeg: Float,
    ): Lch? = runCatching {
        val framed = renderFrame(source, widthPx, heightPx, zoom, panX, panY, rotDeg)
        val mood = moodOf(framed) { fgDark -> bakeAll(context, framed, fgDark) }
        // The clean frame survives for lazy rebakes and the lock wallpaper. save() consumes it.
        save(context, SOURCE_FILE, framed)
        mood
    }.getOrNull()

    /** The framed screen: [source] drawn through the positioner's transform onto a fresh
     *  [outW]×[outH] bitmap. The screen-space twin of the crop this pipeline used to take —
     *  a rotated frame isn't a source rectangle, so it is rendered, not cut. */
    private fun renderFrame(
        source: Bitmap,
        outW: Int,
        outH: Int,
        zoom: Float,
        panX: Float,
        panY: Float,
        rotDeg: Float,
    ): Bitmap {
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val matrix = Matrix().apply {
            setValues(frameMatrix(source.width, source.height, outW, outH, zoom, panX, panY, rotDeg))
        }
        Canvas(out).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /**
     * Re-run both bakes from the stored clean crop — the lazy path for a bake-style change
     * (SID-43). Returns the recomputed mood (the caller re-stores it), or null when there is no
     * stored crop or the bake fails, and then nothing on disk has changed.
     */
    fun rebake(context: Context, widthPx: Int, heightPx: Int): Lch? =
        runCatching {
            val clean = BitmapFactory.decodeFile(context.filesDir.resolve(SOURCE_FILE).path)
                ?: return null
            val scaled =
                if (clean.width == widthPx && clean.height == heightPx) clean
                else Bitmap.createScaledBitmap(clean, widthPx, heightPx, true)
            val mood = moodOf(scaled) { fgDark -> bakeAll(context, scaled, fgDark) }
            if (scaled !== clean) clean.recycle()
            scaled.recycle()
            mood
        }.getOrNull()

    /**
     * The shared head of [bake] and [rebake]: the ink-aware mood. Hue and chroma are the whole
     * image's average — the accent should feel like the photo — but **lightness is the left
     * band's own** (round 6): ink and every mood-derived tone answer to the ground the text
     * actually stands on, not to a whole-image average a bright right half can outvote. [run]
     * receives the ink decision and does the baking.
     */
    private inline fun moodOf(cropped: Bitmap, run: (fgDark: Boolean) -> Unit): Lch {
        val whole = extractMood(cropped)
        val pixels = IntArray(cropped.width * cropped.height)
        cropped.getPixels(pixels, 0, cropped.width, 0, 0, cropped.width, cropped.height)
        val inkRegion = listOf(floatArrayOf(0f, 0f, cropped.width * INK_REGION_FRAC, cropped.height.toFloat()))
        val inkL = zoneLightness(pixels, cropped.width, cropped.height, inkRegion)
        run(inkL > 0.55)
        return Lch(inkL, whole.c, whole.h)
    }

    /** The shared tail of [bake] and [rebake]: the faded crisp bake for the home, the dimmed
     *  frost for the overlays. [cropped] is left alive. */
    private fun bakeAll(context: Context, cropped: Bitmap, fgDark: Boolean) {
        save(context, CRISP_FILE, bakeCrisp(cropped, fgDark))
        val blurred = blurredOf(cropped, BLUR_DOWNSCALE, BLUR_RADIUS, BLUR_PASSES)
        Canvas(blurred).drawColor(withAlpha(scrimColor(fgDark), BLUR_DIM_ALPHA))
        save(context, BLUR_FILE, blurred)
    }

    /** Both bakes off disk, crisp first — or null when either is missing/corrupt. */
    fun load(context: Context): Pair<Bitmap, Bitmap>? = runCatching {
        val crisp = BitmapFactory.decodeFile(context.filesDir.resolve(CRISP_FILE).path) ?: return null
        val blur = BitmapFactory.decodeFile(context.filesDir.resolve(BLUR_FILE).path) ?: return null
        crisp to blur
    }.getOrNull()

    /** The crisp bake alone — the *home* wallpaper frame (SID-180). */
    fun loadCrisp(context: Context): Bitmap? = runCatching {
        BitmapFactory.decodeFile(context.filesDir.resolve(CRISP_FILE).path)
    }.getOrNull()

    /** The clean crop, zones and all left out — the *lock* wallpaper frame: the lock screen is a
     *  photo, not a launcher, so it gets the photo. */
    fun loadClean(context: Context): Bitmap? = runCatching {
        BitmapFactory.decodeFile(context.filesDir.resolve(SOURCE_FILE).path)
    }.getOrNull()

    private fun extractMood(bitmap: Bitmap): Lch {
        val small = Bitmap.createScaledBitmap(bitmap, SAMPLE_SIZE, SAMPLE_SIZE, true)
        val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
        small.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
        small.recycle()
        return averageMood(pixels)
    }

    /** The crisp bake: the photo and one full-height fade from the left edge toward the ink's
     *  ground — darkening under light ink, lightening under dark. Nothing else. */
    private fun bakeCrisp(src: Bitmap, fgDark: Boolean): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        Canvas(out).drawRect(0f, 0f, w, h, Paint().apply {
            shader = LinearGradient(
                0f, 0f, w * EDGE_FADE_REACH, 0f,
                withAlpha(scrimColor(fgDark), EDGE_FADE_ALPHA),
                withAlpha(scrimColor(fgDark), 0f),
                Shader.TileMode.CLAMP,
            )
        })
        return out
    }

    /** A blur of [src], undimmed and mutable, at the given recipe. */
    private fun blurredOf(src: Bitmap, downscale: Int, radius: Int, passes: Int): Bitmap {
        val smallW = (src.width / downscale).coerceAtLeast(1)
        val smallH = (src.height / downscale).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, smallW, smallH, true)
        var pixels = IntArray(smallW * smallH)
        small.getPixels(pixels, 0, smallW, 0, 0, smallW, smallH)
        small.recycle()
        repeat(passes) { pixels = boxBlurPass(pixels, smallW, smallH, radius) }
        val blurredSmall = Bitmap.createBitmap(pixels, smallW, smallH, Bitmap.Config.ARGB_8888)
        val out = Bitmap.createScaledBitmap(blurredSmall, src.width, src.height, true)
            .copy(Bitmap.Config.ARGB_8888, true)
        blurredSmall.recycle()
        return out
    }

    /** The scrims push toward the ink's own background: dark ink wants a light calm, light ink a
     *  dark one. */
    private fun scrimColor(fgDark: Boolean): Int =
        if (fgDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or (((alpha * 255).roundToInt().coerceIn(0, 255)) shl 24)

    private fun decodeScaled(context: Context, uri: Uri, reqW: Int, reqH: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // The stream nullability and the decode result must be judged separately: a bounds-only
        // decode returns null Bitmap BY DESIGN, so `use { decode } ?: return` would bail on every
        // image (the v1.1 "picking a photo does nothing" bug). The real verdict is in [bounds].
        val boundsStream = resolver.openInputStream(uri) ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        return decoded?.let { applyExif(resolver, uri, it) }
    }

    /** BitmapFactory ignores the EXIF orientation tag, so a camera photo can decode in raw sensor
     *  orientation — sideways against how every gallery shows it, which read as the launcher
     *  "auto-orienting" the pick (round 9). Apply the tag so the photo arrives the way its owner
     *  knows it. An unreadable tag is treated as already upright. */
    private fun applyExif(resolver: ContentResolver, uri: Uri, bitmap: Bitmap): Bitmap {
        val tag = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val m = Matrix()
        when (tag) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bitmap
        }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        if (oriented !== bitmap) bitmap.recycle()
        return oriented
    }

    /** Write-then-rename so a mid-write kill never leaves a half image as the live bake. */
    private fun save(context: Context, name: String, bitmap: Bitmap) {
        val tmp = context.filesDir.resolve("$name.tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        tmp.renameTo(context.filesDir.resolve(name))
        bitmap.recycle()
    }
}
