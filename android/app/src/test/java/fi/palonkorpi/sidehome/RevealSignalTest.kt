package fi.palonkorpi.sidehome

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the launcher-level reveal-trigger table (SID-33 HOME intents, SID-34 unlock). These
 * pin *which* signal each trigger resolves to and that both consume the same covered flag, so the
 * one entrance cascade replays on every real reveal and on nothing else. The Activity wiring
 * (receiver registration scope, the actual bumps) is not covered here — only the decision.
 */
class RevealSignalTest {

    // ---- HOME/MAIN intent ------------------------------------------------------------------

    @Test fun `a HOME intent while covered by an app is a reveal`() {
        assertEquals(RevealSignal.APP_REVEAL, homeIntentSignal(wasBackgrounded = true))
    }

    @Test fun `a HOME intent on the live launcher steps the overlay stack back`() {
        assertEquals(RevealSignal.HOME_RECALL, homeIntentSignal(wasBackgrounded = false))
    }

    // ---- unlock (ACTION_USER_PRESENT) ------------------------------------------------------

    @Test fun `unlock from behind the keyguard is a reveal`() {
        // Keyguard-up counts as fully covered, so the unlock is a plain reveal — the same cascade as
        // returning from an app, not a variant of its own.
        assertEquals(RevealSignal.APP_REVEAL, userPresentSignal())
    }

    @Test fun `unlock is a reveal even though nothing ever set the covered flag`() {
        // The regression this file previously enshrined. The keyguard does not stop us — on the
        // SP-01, MainActivity is still RESUMED with the screen asleep — so `wasBackgrounded` is false
        // across an entire lock/unlock cycle. An unlock signal conditioned on that flag resolves to
        // NONE every single time and the cascade never plays. Registration scope is the gate here,
        // not the flag, so this must stay unconditional: no argument to condition on.
        assertEquals(RevealSignal.APP_REVEAL, userPresentSignal())
    }

    @Test fun `unlock never steps the overlay stack back`() {
        // USER_PRESENT is not a HOME press: it must never resolve to HOME_RECALL — that is the one
        // way it differs from a HOME intent.
        assertEquals(false, userPresentSignal() == RevealSignal.HOME_RECALL)
    }

    // ---- both triggers agree on the reveal ------------------------------------------------

    @Test fun `every trigger resolves a covered home to the same reveal signal`() {
        // One cascade, one signal: unlock and return-from-app are not distinguishable downstream.
        assertEquals(homeIntentSignal(wasBackgrounded = true), userPresentSignal())
    }
}
