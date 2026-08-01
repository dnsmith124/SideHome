package fi.palonkorpi.sidehome.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * A quick, lively entrance for overlays: a fast fade plus a small spring-settled rise, so
 * opening the drawer / edit sheet / a folder feels immediate but alive — not a slow fade.
 * Each call site owns its own animation (it flips on first composition, which is when the
 * overlay enters composition).
 */
@Composable
fun rememberOverlayEnter(riseDp: Float = 18f): Modifier {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(110),
        label = "overlayAlpha",
    )
    val rise by animateDpAsState(
        targetValue = if (shown) 0.dp else riseDp.dp,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
        label = "overlayRise",
    )
    // CRITICAL: once the entrance settles, return a plain Modifier with NO graphicsLayer. A
    // persistent compositing layer forces the whole (scrolling) overlay to render offscreen
    // every frame, which is what was tanking drawer scrolling on the A22. The layer only
    // exists for the ~150ms of the animation.
    val settled = shown && alpha > 0.995f && rise <= 0.75.dp
    return if (settled) {
        Modifier
    } else {
        Modifier.graphicsLayer {
            this.alpha = alpha
            translationY = rise.toPx()
        }
    }
}

/**
 * A staggered variant of [rememberOverlayEnter] for a small, fixed set of items that should
 * ripple into view one after another (e.g. the rows inside an opened folder). Each item waits
 * `index * stepMs` before it begins its own rise+fade, so the group cascades in.
 *
 * Only safe for SHORT, non-scrolling lists: like the base enter, each item carries a compositing
 * `graphicsLayer` for the duration of its animation, then drops to a plain Modifier once settled.
 * On a scrolling list that persistent-per-frame layer would tank fling; on a handful of folder
 * rows shown once it's free.
 */
@Composable
fun rememberStaggeredEnter(index: Int, stepMs: Int = 34, riseDp: Float = 13f, leadMs: Int = 0): Modifier {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val wait = leadMs + index * stepMs
        if (wait > 0) delay(wait.toLong())
        shown = true
    }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(150),
        label = "staggerAlpha",
    )
    val rise by animateDpAsState(
        targetValue = if (shown) 0.dp else riseDp.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "staggerRise",
    )
    val settled = shown && alpha > 0.995f && rise <= 0.75.dp
    return if (settled) {
        Modifier
    } else {
        Modifier.graphicsLayer {
            this.alpha = alpha
            translationY = rise.toPx()
        }
    }
}
