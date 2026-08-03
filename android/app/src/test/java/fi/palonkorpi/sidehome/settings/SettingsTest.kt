package fi.palonkorpi.sidehome.settings

import fi.palonkorpi.sidehome.ambient.Theme
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {

    // ---- theme persistence seam (SID-42, SPEC.md §6) -------------------------------------------
    // The SharedPreferences round-trip runs through these pure encode/decode helpers, so the
    // default = Ambient contract and the name-stable persistence are unit-testable without Android.

    @Test fun theme_defaultsToAmbient() {
        assertEquals(Theme.AMBIENT, themeFromPref(null))
    }

    @Test fun theme_unknownValueFallsBackToAmbient() {
        // a value written by a future build (or a corrupt pref) must not throw — it defaults.
        // ("PHOTO" used to sit here as the future-name example; it became a real theme in v1.1.)
        assertEquals(Theme.AMBIENT, themeFromPref("DISCO"))
        assertEquals(Theme.AMBIENT, themeFromPref(""))
    }

    @Test fun theme_survivesAPersistReadRoundTrip() {
        for (t in Theme.entries) {
            assertEquals(t, themeFromPref(t.toPref()))
        }
    }


    @Test fun h24_padsBothFields() {
        assertEquals("00:00", formatClock(0, 0, ClockFormat.H24))
        assertEquals("09:05", formatClock(9, 5, ClockFormat.H24))
        assertEquals("23:59", formatClock(23, 59, ClockFormat.H24))
    }

    @Test fun h12_midnightIsTwelveAm() {
        assertEquals("12:00 am", formatClock(0, 0, ClockFormat.H12))
    }

    @Test fun h12_noonIsTwelvePm() {
        assertEquals("12:00 pm", formatClock(12, 0, ClockFormat.H12))
    }

    @Test fun h12_morningHasNoLeadingZeroOnHour() {
        assertEquals("9:05 am", formatClock(9, 5, ClockFormat.H12))
    }

    @Test fun h12_afternoonWrapsPastNoon() {
        assertEquals("1:30 pm", formatClock(13, 30, ClockFormat.H12))
        assertEquals("11:59 pm", formatClock(23, 59, ClockFormat.H12))
    }

    @Test fun outOfRangeInputsAreNormalised() {
        // defensive: negative / overflow values wrap rather than throw
        assertEquals("23:00", formatClock(-1, 0, ClockFormat.H24))
        assertEquals("00:00", formatClock(24, 60, ClockFormat.H24))
    }
}
