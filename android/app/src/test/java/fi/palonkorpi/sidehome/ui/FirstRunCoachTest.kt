package fi.palonkorpi.sidehome.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import fi.palonkorpi.sidehome.pane.LeftPane

/**
 * Unit tests for the one-time first-run coach's whole contract (SID-27, SPEC §7): appears exactly
 * once on a fresh install, is dismissible, never returns.
 *
 * Every one of those is a statement about a persisted flag and nothing else, which is why
 * [CoachState] takes the flag as a value and the write as a lambda instead of taking the store. The
 * failure modes here are all silent and all unrepeatable — a coach that shows twice, a coach that
 * never shows, a coach that spends its single pass on an install that never saw it — and none of
 * them can be caught by looking at a fresh install once.
 */
class FirstRunCoachTest {

    /** A `SettingsStore.coachSeen` stand-in: the flag, and a count of how often it was written. */
    private class FakeFlag(seen: Boolean = false) {
        var seen = seen
            private set
        var writes = 0
            private set

        fun coach() = CoachState(seen) { seen = true; writes += 1 }
    }

    @Test fun `a fresh install is coached`() {
        // An install with no prefs file reads false, and false means never coached.
        assertTrue(FakeFlag(seen = false).coach().visible)
    }

    @Test fun `an install that has already been coached never sees it again`() {
        val flag = FakeFlag(seen = true)
        assertFalse(flag.coach().visible)
        assertEquals(0, flag.writes)   // nothing to re-persist
    }

    @Test fun `dismissing takes it down and persists that it happened`() {
        val flag = FakeFlag()
        val coach = flag.coach()
        coach.dismiss()
        assertFalse(coach.visible)
        assertTrue(flag.seen)
        assertEquals(1, flag.writes)
    }

    @Test fun `once dismissed, it is gone for the next launch too`() {
        // The acceptance criterion end to end: dismiss, then re-read the flag the way a cold start
        // would. This is the assertion that a show-time write would also pass — and the next one is
        // the one it would fail.
        val flag = FakeFlag()
        flag.coach().dismiss()
        assertFalse(flag.coach().visible)
    }

    @Test fun `showing the coach persists nothing — only being dismissed does`() {
        // The single pass belongs to a user who actually saw it. Raised and then interrupted (a call
        // lands, the screen sleeps) is not "coached", so the flag must still be unwritten and the
        // next launch must coach again. Writing at show-time would spend the pass on nobody.
        val flag = FakeFlag()
        val coach = flag.coach()
        assertTrue(coach.visible)
        assertFalse(flag.seen)
        assertEquals(0, flag.writes)
        assertTrue(flag.coach().visible)   // the interrupted install, launching again
    }

    @Test fun `a second dismissal is not a second write`() {
        // Any touch dismisses, and touches arrive in streams; the coach is torn down on the first
        // one, but a queued second must not re-enter the store.
        val flag = FakeFlag()
        val coach = flag.coach()
        coach.dismiss()
        coach.dismiss()
        assertEquals(1, flag.writes)
        assertFalse(coach.visible)
    }

    // ---- the line set (SID-181): teach only gestures that do something ----------------------

    @Test fun `with weather on the coach teaches apps and weather`() {
        assertEquals(listOf("apps", "weather"), coachLines(weatherEnabled = true).map { it.label })
    }

    @Test fun `with weather off the weather line goes — no coaching a swipe to nowhere`() {
        assertEquals(listOf("apps"), coachLines(weatherEnabled = false).map { it.label })
    }

    @Test fun `a left page adds its own swipe, named for what it holds`() {
        assertEquals(listOf("apps", "now"), coachLines(weatherEnabled = false, leftPane = LeftPane.NOW).map { it.label })
        assertEquals(listOf("apps", "weather", "notes"), coachLines(weatherEnabled = true, leftPane = LeftPane.NOTES).map { it.label })
        assertEquals(listOf("apps"), coachLines(weatherEnabled = false, leftPane = LeftPane.OFF).map { it.label })
        assertEquals(listOf("apps", "now & notes"), coachLines(weatherEnabled = false, leftPane = LeftPane.BOTH).map { it.label })
    }
}
