package fi.palonkorpi.sidehome.ui

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SID-80 — the pure clear-decision behind the launch-feedback overlay ([launchOverlayAction]). These
 * pin the one subtlety that makes the overlay usable: a `LifecycleEventObserver` is replayed the
 * current state at registration, and the launcher registers it while already RESUMED, so it fires an
 * immediate synthetic ON_RESUME. That first resume must NOT clear the overlay (nothing has left the
 * foreground yet) — only a resume that follows the window actually leaving does. The Compose hosting
 * and the timeout coroutine are not covered here — only the decision.
 */
class LaunchFeedbackTest {

    @Test fun `the synthetic resume at registration does not clear the overlay`() {
        // Observer added while RESUMED → immediate ON_RESUME, hasLeftForeground still false. If this
        // cleared, the overlay would vanish the instant it appeared — the whole point of the gate.
        assertEquals(LaunchOverlayAction.HOLD, launchOverlayAction(Lifecycle.Event.ON_RESUME, hasLeftForeground = false))
    }

    @Test fun `a resume after leaving the foreground clears the overlay`() {
        // The real return: the launched app took over (we paused/stopped), now we're back.
        assertEquals(LaunchOverlayAction.CLEAR, launchOverlayAction(Lifecycle.Event.ON_RESUME, hasLeftForeground = true))
    }

    @Test fun `pause marks that we left the foreground`() {
        assertEquals(LaunchOverlayAction.MARK_LEFT, launchOverlayAction(Lifecycle.Event.ON_PAUSE, hasLeftForeground = false))
    }

    @Test fun `stop also marks that we left the foreground`() {
        // A launched app can take us all the way to onStop; either edge means the window is gone.
        assertEquals(LaunchOverlayAction.MARK_LEFT, launchOverlayAction(Lifecycle.Event.ON_STOP, hasLeftForeground = false))
    }

    @Test fun `create and start are inert`() {
        // The synthetic replay walks INITIALIZED → RESUMED as ON_CREATE, ON_START, ON_RESUME; the
        // first two must do nothing, or a fresh observer would mark-left before any launch.
        assertEquals(LaunchOverlayAction.HOLD, launchOverlayAction(Lifecycle.Event.ON_CREATE, hasLeftForeground = false))
        assertEquals(LaunchOverlayAction.HOLD, launchOverlayAction(Lifecycle.Event.ON_START, hasLeftForeground = false))
    }

    @Test fun `destroy is inert`() {
        assertEquals(LaunchOverlayAction.HOLD, launchOverlayAction(Lifecycle.Event.ON_DESTROY, hasLeftForeground = true))
    }

    @Test fun `the full launch-and-return cycle clears exactly once`() {
        // Drive the latch the way the observer does: pause sets it, the following resume clears.
        var left = false
        val onPause = launchOverlayAction(Lifecycle.Event.ON_PAUSE, left)
        assertEquals(LaunchOverlayAction.MARK_LEFT, onPause)
        left = true
        val onResume = launchOverlayAction(Lifecycle.Event.ON_RESUME, left)
        assertEquals(LaunchOverlayAction.CLEAR, onResume)
    }

    @Test fun `the timeout backstop is a sane, bounded cap`() {
        // Documented ~5s: long enough not to clip a slow cold start on the A22, short enough that a
        // wedged launch can't strand the overlay for good.
        assertEquals(5_000L, LAUNCH_FEEDBACK_TIMEOUT_MS)
        assertTrue(LAUNCH_FEEDBACK_TIMEOUT_MS in 1_000L..10_000L)
    }
}
