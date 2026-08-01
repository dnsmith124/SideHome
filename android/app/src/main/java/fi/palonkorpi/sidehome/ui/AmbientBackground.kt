package fi.palonkorpi.sidehome.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import fi.palonkorpi.sidehome.ambient.baseFill
import fi.palonkorpi.sidehome.ambient.fieldColor
import fi.palonkorpi.sidehome.ambient.vignetteAlpha
import fi.palonkorpi.sidehome.color.Lch

/** One abstract colour field: an analogous-hue blob at a fixed position. */
private data class Field(
    val dl: Double, val dc: Double, val dh: Double,
    val x: Float, val y: Float, val radius: Float,
)

// Fixed, pleasing arrangement (positions the old drift settled around). No motion:
// a calm launcher shouldn't burn the battery repainting a full screen 60×/second, and
// the A22 pays a high fixed cost per full-screen frame. The background is STATIC and only
// repaints when the time-of-day mood colour actually shifts (a few times a minute).
private val FIELDS = listOf(
    Field(0.06, 1.15, -0.35, 0.32f, 0.26f, 0.66f),
    Field(-0.05, 1.30, 0.50, 0.72f, 0.68f, 0.66f),
    Field(0.03, 1.45, 0.14, 0.60f, 0.16f, 0.66f),
    Field(-0.04, 1.10, -0.62, 0.16f, 0.80f, 0.66f),
    Field(0.05, 1.20, 0.92, 0.88f, 0.36f, 0.66f),
)

// Theme 4's rim geometry, all as fractions of width so the rim is identical on the 480x640 screen
// and in a chooser tile. RIM_INSET sets how far in the black round-rect starts, RIM_BLOOM how far
// its edge feathers; with INSET a touch larger than BLOOM the outermost pixels stay solid sky, so
// the rim reads as a bold band of colour that then fades to black by ~(INSET + BLOOM) inward. Kept
// well under the danger zone — at ~0.085 each the band ate a sixth of the screen per side and
// inverted the effect into "a black blob floating on the sky". RIM_CORNER rounds the rim's corners.
private const val RIM_INSET = 0.03f
private const val RIM_BLOOM = 0.035f
private const val RIM_CORNER = 0.10f

/**
 * How far the sky travels for a page of pane travel (SPEC §7: "the sky continuous across all three
 * so it feels like one window panning"). The sky is the most distant thing in the scene, so it is
 * the thing that should move *least* — that is what makes a swipe read as a viewport crossing one
 * field rather than as panes sliding over a decal, and it is also what makes the field affordable:
 * the canvas has to be overscanned by exactly this much per side, so 0.18 buys a 1.36×-wide
 * surface where a 1:1 sky would need 3× — 1440px of gradient mesh on a PowerVR GE8300, bought for
 * motion nobody asked for. On-device knob: this is a *feel* number, tune it here.
 */
const val SKY_PARALLAX = 0.18f

/** A 128px monochrome noise tile, generated once, to dither 8-bit banding. */
private fun buildGrainBitmap(): Bitmap {
    val n = 128
    val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    val px = IntArray(n * n)
    val rnd = kotlin.random.Random(0x51DE)
    for (i in px.indices) {
        val v = rnd.nextInt(256)
        px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    bmp.setPixels(px, 0, n, 0, 0, n, n)
    return bmp
}

@Composable
private fun rememberGrainBrush(): Brush = remember {
    ShaderBrush(BitmapShader(buildGrainBitmap(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT))
}

/**
 * The sky itself: the base fill and the colour blobs, and nothing else that belongs to the frame.
 *
 * Its geometry is anchored to [viewportW]/[originX] rather than to the canvas, which is what lets
 * [ContinuousSky] overscan the canvas for the pan and still render the *same* sky at rest — the
 * margins are these same fields continuing outward, not a mesh re-proportioned to a wider surface.
 * Sizing the blobs off `size.width` instead would silently re-tune the home's look the day the
 * parallax changed.
 */
private fun DrawScope.drawSkyField(mood: Lch, viewportW: Float, originX: Float, hueSpread: Float) {
    drawRect(color = baseFill(mood))

    for (f in FIELDS) {
        val bx = originX + f.x * viewportW
        val by = f.y * size.height
        val r = viewportW * f.radius
        // hueSpread scales each field's hue rotation: 1 = the ambient's analogous-hue sky, 0 = every
        // field locked to the mood's own hue (Custom), so the blobs vary only in lightness/chroma.
        val col = fieldColor(mood, f.dl, f.dc, f.dh * hueSpread, 1f)
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to col.copy(alpha = 0.55f),
                0.55f to col.copy(alpha = 0.22f),
                1.0f to col.copy(alpha = 0.0f),
                center = Offset(bx, by),
                radius = r,
            ),
            radius = r,
            center = Offset(bx, by),
        )
    }
}

/**
 * The lens the sky is seen through: vignette, theme-4 rim, grain. All three are properties of the
 * *frame*, not of the field — a vignette that panned would drag a dark corner into the middle of
 * the screen, and theme 4's rim hugs the screen's border by definition. So they are drawn on the
 * viewport and never translate, which is also why they can stay out of the overscanned canvas.
 */
private fun DrawScope.drawSkyLens(mood: Lch, edgeOnly: Boolean, grain: Brush, grainAlpha: Float) {
    val w = size.width
    val h = size.height

    // The outer vignette darkens the frame's edge — which is precisely where theme 4 wants its
    // glow, so the two cannot coexist. Theme 4 does its own edge shaping below.
    if (!edgeOnly) {
        drawRect(
            brush = Brush.radialGradient(
                0.23f to Color.Transparent,
                1.0f to Color.Black.copy(alpha = vignetteAlpha(mood)),
                center = Offset(w * 0.5f, h * 0.52f),
                radius = h * 0.78f,
            )
        )
    }

    // Theme 4 (edge / dark-centre): the ambient survives only as a rim hugging the screen's
    // rounded edge, blooming inward to black. Shaped by one blurred black round-rect laid over
    // the sky: its blurred boundary feathers the sky out over ~RIM_BLOOM, and everything
    // further in goes flat black. A radial gradient cannot do this — on a 3:4 frame a circle
    // leaves the corners lit and the mid-edges dark, so the rim wouldn't track the border.
    // All three constants are fractions of width, so a chooser tile renders the same rim as
    // the full screen. Inset ≈ bloom keeps the outermost pixels at ~zero black, i.e. full sky.
    if (edgeOnly) {
        val bloom = w * RIM_BLOOM
        val inset = w * RIM_INSET
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.BLACK
                maskFilter = BlurMaskFilter(bloom, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.nativeCanvas.drawRoundRect(
                inset, inset, w - inset, h - inset,
                w * RIM_CORNER, w * RIM_CORNER,
                paint,
            )
        }
    }

    if (grainAlpha > 0.001f) {
        drawRect(brush = grain, alpha = grainAlpha)
    }
}

/**
 * The living background — a static OKLCH mesh: a base fill, soft overlapping colour
 * fields, a vignette, and SrcOver grain (never BlendMode.Overlay — that forces a
 * per-frame saveLayer). It only repaints when [mood] changes, so an idle home costs
 * ~nothing: no animation loop, no continuous redraw, no battery drain. Time of day still
 * lives here — the whole palette crossfades as the sun moves, just gradually.
 *
 * [edgeOnly] is the theme-4 variant (SPEC.md §6): the same ambient is painted, then an inverse
 * vignette sinks the centre to near-black so the sky survives only as an edge glow. One render
 * path — themes differ only by their [mood] (resolved upstream) and this flag.
 *
 * This is the fixed, one-surface renderer, for anything that doesn't pan: a chooser tile, the
 * settings screen. The paged home uses [ContinuousSky], which paints the same field through the
 * same two passes.
 */
@Composable
fun AmbientBackground(
    mood: Lch,
    grainAlpha: Float = 0.035f,
    edgeOnly: Boolean = false,
    hueSpread: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val grainBrush = rememberGrainBrush()
    Canvas(modifier = modifier) {
        drawSkyField(mood, size.width, originX = 0f, hueSpread = hueSpread)
        drawSkyLens(mood, edgeOnly, grainBrush, grainAlpha)
    }
}

/**
 * The ambient under the three panes (SPEC §7 / §4): **one** sky field that the viewport pans
 * across, never three panes each deriving their own — that is what has no seam to cut and no
 * moment where the sky re-renders under a moving finger.
 *
 * It is one field canvas, overscanned by [SKY_PARALLAX] of a page per side and translated inside a
 * `graphicsLayer`, plus the viewport-fixed lens on top. What that buys, and what it costs per frame
 * on the GE8300:
 *  - [panPx] is read **only** inside the layer's draw lambda, so a pan re-runs neither composition
 *    nor layout nor the field's `Canvas` block. The mesh is recorded once per [mood] change, exactly
 *    as the static background always was; a swipe replays it under a new matrix.
 *  - The overscan is off-viewport, so the rasteriser scissors it away: the *shaded* area during a
 *    pan is the same 480×640 of gradient an idle frame already pays for. The wide surface costs
 *    geometry setup, not fill — which is the whole reason it can be wide at all here.
 *  - Two canvases rather than one is two render nodes and the same draw ops in the same order, so
 *    at rest this is pixel-for-pixel the old single-canvas background.
 *
 * [containerW] is the viewport width in px (the pages' width); [panPx] is how far right of the
 * centre home the viewport has travelled.
 */
@Composable
fun ContinuousSky(
    mood: Lch,
    containerW: Int,
    panPx: () -> Float,
    grainAlpha: Float = 0.035f,
    edgeOnly: Boolean = false,
    hueSpread: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val grainBrush = rememberGrainBrush()
    // Exactly the overscan the parallax can consume over a full page each way, and not a pixel of
    // sky more. The +2px is slack for the px→dp→px round-trip the layout below has to make: losing
    // half a pixel here would show as a black hairline at the far edge of a committed swipe.
    val margin = containerW * SKY_PARALLAX + 2f
    val skyWidth = with(LocalDensity.current) { (containerW + 2f * margin).toDp() }

    Box(modifier) {
        Canvas(
            Modifier
                .requiredWidth(skyWidth)
                .fillMaxHeight()
                // Only the pan. The overscan does *not* need centring here: a child that overflows
                // its constraints reports the constrained width to its parent, and Compose puts the
                // overflow back by centring it at placement (Placeable.apparentToRealOffset). So the
                // canvas already sits `margin` left of the viewport before this runs, and taking
                // another `margin` off here spent the whole right-hand overscan before the first
                // swipe — at rest the sky's right edge landed within a fraction of a pixel of the
                // screen's, which looks perfect and is why only a committed page showed the gap.
                // Sign: the viewport moving right is the sky moving left.
                .graphicsLayer {
                    translationX = -SKY_PARALLAX * panPx()
                    compositingStrategy = CompositingStrategy.Offscreen
                },
        ) {
            drawSkyField(mood, containerW.toFloat(), originX = margin, hueSpread = hueSpread)
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            drawSkyLens(mood, edgeOnly, grainBrush, grainAlpha)
        }
    }
}
