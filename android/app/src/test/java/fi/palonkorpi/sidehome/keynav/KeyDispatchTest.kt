package fi.palonkorpi.sidehome.keynav

import android.view.KeyEvent
import fi.palonkorpi.sidehome.gesture.DEFAULT_HOLD_TIMEOUT_MS
import fi.palonkorpi.sidehome.gesture.GestureTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three-way dispatch fork (SID-88): OFF passes everything, a focused field reserves only the
 * uniform set and types the rest, and a bare surface maps through the active profile. These pin the
 * table so the on-device behaviour can be reasoned about rather than only felt.
 */
class KeyDispatchTest {

    private val profile = KeyProfiles.UNIFIED

    // ---- fork 1: OFF is a true no-op -------------------------------------------------------

    @Test fun `disabled passes every key through, even a bound one`() {
        assertEquals(
            KeyDispatch.PassThrough,
            decideKeyDispatch(KeyEvent.KEYCODE_ENTER, navEnabled = false, textFieldFocused = false, profile = profile),
        )
        assertEquals(
            KeyDispatch.PassThrough,
            decideKeyDispatch(KeyEvent.KEYCODE_DPAD_DOWN, navEnabled = false, textFieldFocused = true, profile = profile),
        )
    }

    // ---- fork 2: a focused field reserves only the uniform set -----------------------------

    @Test fun `focused field types an ordinary letter`() {
        // A QWERTY letter that navigates on the bare home must type into a focused search field.
        assertEquals(
            KeyDispatch.PassThrough,
            decideKeyDispatch(KeyEvent.KEYCODE_T, navEnabled = true, textFieldFocused = true, profile = profile),
        )
    }

    @Test fun `focused field still reserves Enter as Select — the double-Enter fix`() {
        // SID-91: Enter is handled here, before the predictive IME can eat the first as a commit.
        assertEquals(
            KeyDispatch.Handle(KeyIntent.Select),
            decideKeyDispatch(KeyEvent.KEYCODE_ENTER, navEnabled = true, textFieldFocused = true, profile = profile),
        )
    }

    @Test fun `focused field reserves the D-pad ring for the Sundial`() {
        assertEquals(
            KeyDispatch.Handle(KeyIntent.NavDown),
            decideKeyDispatch(KeyEvent.KEYCODE_DPAD_DOWN, navEnabled = true, textFieldFocused = true, profile = profile),
        )
        assertEquals(
            KeyDispatch.Handle(KeyIntent.Back),
            decideKeyDispatch(KeyEvent.KEYCODE_BACK, navEnabled = true, textFieldFocused = true, profile = profile),
        )
    }

    @Test fun `the rocker is never reserved while typing`() {
        // ▲▼ rocker (CTRL_LEFT) belongs to the IME's predictive toggle — never bound (SID-85).
        assertEquals(
            KeyDispatch.PassThrough,
            decideKeyDispatch(KeyEvent.KEYCODE_CTRL_LEFT, navEnabled = true, textFieldFocused = true, profile = profile),
        )
    }

    // ---- fork 3: a bare surface maps through the profile -----------------------------------

    @Test fun `bare surface maps a grid key to direct-index, on both number tiles`() {
        // The QWERTY letter the marking "2" is printed on, and the T9 digit, both resolve to index-2/1.
        assertEquals(
            KeyDispatch.Handle(KeyIntent.QuickIndex(2)),
            decideKeyDispatch(KeyEvent.KEYCODE_T, navEnabled = true, textFieldFocused = false, profile = profile),
        )
        assertEquals(
            KeyDispatch.Handle(KeyIntent.QuickIndex(1)),
            decideKeyDispatch(KeyEvent.KEYCODE_1, navEnabled = true, textFieldFocused = false, profile = profile),
        )
    }

    @Test fun `bare surface passes an unmapped key through`() {
        assertEquals(
            KeyDispatch.PassThrough,
            decideKeyDispatch(KeyEvent.KEYCODE_VOLUME_UP, navEnabled = true, textFieldFocused = false, profile = profile),
        )
    }

    @Test fun `the rocker is inert on a bare surface too`() {
        assertEquals(
            KeyDispatch.PassThrough,
            decideKeyDispatch(KeyEvent.KEYCODE_CTRL_LEFT, navEnabled = true, textFieldFocused = false, profile = profile),
        )
    }

    // ---- hold classification ---------------------------------------------------------------

    @Test fun `a quick press is a tap, a long one is a hold`() {
        assertFalse(isKeyHold(downTimeMs = 1_000, upTimeMs = 1_100))
        assertTrue(isKeyHold(downTimeMs = 1_000, upTimeMs = 1_000 + KEY_HOLD_THRESHOLD_MS))
        assertTrue(isKeyHold(downTimeMs = 1_000, upTimeMs = 2_000))
    }

    // ---- SID-111: one hold threshold for finger and key -----------------------------------

    @Test fun `the key hold threshold is the shared touch hold, never a second number`() {
        // The 400-vs-450 drift SID-111 removed: the key default, the shared constant, and the touch
        // GestureTuning default must all be the one number, or a hold means something different to a
        // finger than to a key again.
        assertEquals(DEFAULT_HOLD_TIMEOUT_MS, KEY_HOLD_THRESHOLD_MS)
        assertEquals(GestureTuning().holdTimeoutMs, KEY_HOLD_THRESHOLD_MS)
    }

    @Test fun `the controller applies its live hold threshold to isKeyHold`() {
        // The controller reads its own holdTimeoutMs (fed the live GestureTuning value), so a press
        // just over that live threshold is a hold and one just under it is a tap.
        val live = GestureTuning().holdTimeoutMs
        assertTrue(isKeyHold(downTimeMs = 0, upTimeMs = live + 1, thresholdMs = live))
        assertFalse(isKeyHold(downTimeMs = 0, upTimeMs = live - 1, thresholdMs = live))
    }
}
