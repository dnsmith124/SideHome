package fi.palonkorpi.sidehome.weather

import fi.palonkorpi.sidehome.settings.placeFromPref
import fi.palonkorpi.sidehome.settings.tempUnitFromPref
import fi.palonkorpi.sidehome.settings.toPref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two settings this feature added, at their persistence seam (SID-35) — the same pure
 * encode/decode split `SettingsTest` already uses for the theme, and for the same reason: the
 * `SharedPreferences` round-trip is the part that can be wrong, and it's the part a JVM test can
 * reach.
 *
 * The test lives with the weather because the *types* do: SPEC promised these settings already
 * existed ("reuses §1's existing temperature-units setting") and they did not — §1 shipped a clock
 * format and a theme and nothing else.
 */
class WeatherSettingsTest {

    private val helsinki = Place("Helsinki", 60.1695, 24.9354)

    @Test fun place_defaultsToNothingSet() {
        // no default city, ever: a launcher that opens on Berlin's weather has told the user
        // something false, where "Set your location" tells them something true
        assertNull(placeFromPref(null))
        assertNull(placeFromPref(""))
    }

    @Test fun place_survivesARoundTrip() {
        assertEquals(helsinki, placeFromPref(helsinki.toPref()))
    }

    @Test fun place_keepsNegativeAndFractionalCoords() {
        val sydney = Place("Sydney", -33.8688, 151.2093)
        assertEquals(sydney, placeFromPref(sydney.toPref()))
    }

    @Test fun place_keepsANameWithASeparatorInIt() {
        // coords first, name last and unsplit — a geocoder can return anything in that field
        val awkward = Place("Sant Julià de Lòria; the one", 42.46, 1.49)
        assertEquals(awkward, placeFromPref(awkward.toPref()))
    }

    @Test fun place_readsACorruptValueAsNothingSet() {
        // lands on a state the pane already renders beautifully, rather than throwing in the home
        assertNull(placeFromPref("Helsinki"))
        assertNull(placeFromPref("60.1695;24.9354"))
        assertNull(placeFromPref("north;east;Helsinki"))
        assertNull(placeFromPref("60.1695;24.9354;"))
        assertNull(placeFromPref("NaN;24.9354;Helsinki"))
    }

    @Test fun unit_defaultsToCelsius() {
        assertEquals(TempUnit.CELSIUS, tempUnitFromPref(null))
    }

    @Test fun unit_unknownValueFallsBackToCelsius() {
        assertEquals(TempUnit.CELSIUS, tempUnitFromPref("KELVIN"))
        assertEquals(TempUnit.CELSIUS, tempUnitFromPref(""))
    }

    @Test fun unit_survivesARoundTrip() {
        for (u in TempUnit.entries) {
            assertEquals(u, tempUnitFromPref(u.toPref()))
        }
    }
}
