package fi.palonkorpi.sidehome.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import fi.palonkorpi.sidehome.apps.AppEntry

/**
 * SID-80 — the launch-feedback overlay.
 *
 * Launching a slow app used to read as a failed tap: from the home the launcher sat on the home for
 * a beat before the app appeared; from the library, the tap closed the library, flashed the bare
 * home, *then* the app opened. This overlay is the acknowledgement — raised the instant a launch
 * fires and held until the launched app takes the foreground (then cleared on our return, see
 * [launchOverlayAction] and the lifecycle wiring in HomeScreen).
 *
 * It follows DECISIONS' overlay model at its simplest end: no dim, no live blur (the A22 forbids it)
 * — the glyph floats over a solid, sky-derived [backdrop], the same cheap opaque fill the folder and
 * drawer backdrops use. Because it is opaque it *covers* whatever is receding beneath it, which is
 * what kills the library-close → bare-home flash: setting the state before the library closes means
 * this is already up over the transition.
 */

/** How long the overlay may hang before the backstop tears it down (see the note on the timeout in
 *  HomeScreen). A launch that fails never foregrounds an app, so [Lifecycle.Event.ON_RESUME] never
 *  comes to clear it — this caps the wait. Five seconds: long enough to never clip a genuinely slow
 *  cold start on this hardware, short enough that a wedged launch doesn't strand the overlay. */
const val LAUNCH_FEEDBACK_TIMEOUT_MS = 5_000L

/** What an observed lifecycle event means for the launch overlay — the pure core of the clear logic,
 *  factored out of the composable so it can be unit-tested without an Activity or a Compose harness. */
enum class LaunchOverlayAction {
    /** Nothing to do — this event doesn't bear on the overlay. */
    HOLD,

    /** Our window left the foreground (the launched app took over). Remember it, so the *next*
     *  resume counts as a real return. */
    MARK_LEFT,

    /** We are back in the foreground after having left — the overlay has done its job, clear it. */
    CLEAR,
}

/**
 * Decide what an [event] means for the launch overlay, given whether our window has already left the
 * foreground since the launch ([hasLeftForeground]).
 *
 * The gate on [hasLeftForeground] is the whole subtlety. A `LifecycleEventObserver` registered while
 * the owner is already RESUMED — which is exactly our case, the launcher is foreground when it fires
 * a launch — is replayed the current state synthetically, i.e. it receives an immediate
 * [Lifecycle.Event.ON_RESUME] at registration. Clearing on *any* resume would therefore tear the
 * overlay down the instant it appeared. So a resume only clears once we've actually seen the window
 * leave (a real launch always pauses us before the app resumes it), and that first synthetic resume
 * — with nothing having left yet — is a no-op [HOLD].
 */
fun launchOverlayAction(event: Lifecycle.Event, hasLeftForeground: Boolean): LaunchOverlayAction =
    when (event) {
        Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> LaunchOverlayAction.MARK_LEFT
        Lifecycle.Event.ON_RESUME ->
            if (hasLeftForeground) LaunchOverlayAction.CLEAR else LaunchOverlayAction.HOLD
        else -> LaunchOverlayAction.HOLD
    }

/**
 * The overlay itself: [app]'s glyph (and label) centred on a solid [backdrop], with a calm entrance
 * — a gentle fade plus a slight scale-up, one cheap animation, no per-frame recomposition (the
 * progress is read only inside a `graphicsLayer` draw lambda).
 */
@Composable
fun LaunchFeedback(
    app: AppEntry,
    fg: Color,
    accent: Color,
    backdrop: Color,
    modifier: Modifier = Modifier,
) {
    // One-shot entrance. Animatable rather than animateFloatAsState so it runs once on appear and is
    // never re-triggered by recomposition; its value drives alpha + scale in the draw phase only.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        enter.animateTo(1f, animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backdrop)
            // Read enter.value here, in the layer's draw lambda, so the entrance animates without
            // recomposing the tree each frame (the drawerFrac / chooserZoom convention).
            .graphicsLayer {
                val p = enter.value
                alpha = p
                val s = 0.88f + 0.12f * p
                scaleX = s
                scaleY = s
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LineIcon(glyph = app.glyph, color = fg, size = 46.dp)
        Spacer(Modifier.height(14.dp))
        Text(text = app.label, color = fg, fontSize = 15.sp)
        Spacer(Modifier.height(20.dp))
        // A quiet indeterminate spinner: the tap is acknowledged (glyph + label), this says the app
        // is on its way. Accent-tinted like every other active mark in the launcher, thin and small
        // so it stays calm on the ambient.
        CircularProgressIndicator(
            color = accent,
            strokeWidth = 2.dp,
            modifier = Modifier.size(22.dp),
        )
    }
}
