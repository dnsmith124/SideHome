package fi.palonkorpi.sidehome.ui

import fi.palonkorpi.sidehome.weather.Forecast
import fi.palonkorpi.sidehome.weather.Place
import fi.palonkorpi.sidehome.weather.TempUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The home glance's present/absent decision (SID-64). All of it is `glanceTemp`, which is pure by
 * design — the composable around it is layout, which this project's JVM test setup can't reach.
 *
 * The rule under test is DECISIONS' "one glanceable line" plus SPEC §4a's cache discipline: show a
 * last-known temperature when it's for the right place and recent enough, and otherwise show
 * *nothing at all* — never a placeholder, never a stale number the quiet home can't caveat.
 */
class GlanceLineTest {

    private val helsinki = Place(name = "Helsinki", lat = 60.1695, lon = 24.9354)
    private val berlin = Place(name = "Berlin", lat = 52.52, lon = 13.405)
    private val fetchedAt = 1_784_116_800_000L

    private fun forecast(tempC: Double = 12.0, fetchedAtMillis: Long = fetchedAt) = Forecast(
        placeName = helsinki.name,
        lat = helsinki.lat,
        lon = helsinki.lon,
        utcOffsetSeconds = 10800,
        nowTimeSec = fetchedAtMillis / 1000,
        nowTempC = tempC,
        nowCode = 3,
        hours = emptyList(),
        days = emptyList(),
        fetchedAtMillis = fetchedAtMillis,
    )

    @Test fun showsAFreshReadingForTheChosenPlace() {
        assertEquals("12°", glanceTemp(forecast(), helsinki, TempUnit.CELSIUS, fetchedAt + 1_000))
    }

    @Test fun rendersInTheChosenUnit() {
        // 12°C rounds to 54°F — the glance is a display of the same Celsius cache the pane holds.
        assertEquals("54°", glanceTemp(forecast(), helsinki, TempUnit.FAHRENHEIT, fetchedAt + 1_000))
    }

    @Test fun absentWhenNoPlaceIsSet() {
        // no location → the line isn't in the layout at all, even with a cache in hand
        assertNull(glanceTemp(forecast(), null, TempUnit.CELSIUS, fetchedAt + 1_000))
    }

    @Test fun absentWhenThereIsNoCache() {
        assertNull(glanceTemp(null, helsinki, TempUnit.CELSIUS, fetchedAt + 1_000))
    }

    @Test fun absentWhenTheCacheIsForAnotherCity() {
        // a Helsinki cache must never render under Berlin — matches() is coords, not the name
        assertNull(glanceTemp(forecast(), berlin, TempUnit.CELSIUS, fetchedAt + 1_000))
    }

    @Test fun absentWhenTheReadingIsNotFinite() {
        // a missing feed column decodes to NaN; a dash belongs on the pane, never on the home
        assertNull(glanceTemp(forecast(tempC = Double.NaN), helsinki, TempUnit.CELSIUS, fetchedAt + 1_000))
    }

    @Test fun showsUpToTheStalenessCap() {
        val justUnder = fetchedAt + GLANCE_MAX_AGE_MS - 1
        assertEquals("12°", glanceTemp(forecast(), helsinki, TempUnit.CELSIUS, justUnder))
    }

    @Test fun absentAtAndBeyondTheStalenessCap() {
        // the cap is inclusive-hide: a reading exactly GLANCE_MAX_AGE_MS old is already gone
        assertNull(glanceTemp(forecast(), helsinki, TempUnit.CELSIUS, fetchedAt + GLANCE_MAX_AGE_MS))
        assertNull(glanceTemp(forecast(), helsinki, TempUnit.CELSIUS, fetchedAt + GLANCE_MAX_AGE_MS * 4))
    }

    @Test fun absentWhenTheClockWentBackwards() {
        // a manual time change puts the reading "in the future"; freshness can't be vouched for, hide
        assertNull(glanceTemp(forecast(), helsinki, TempUnit.CELSIUS, fetchedAt - 5_000))
    }

    @Test fun roundsHalfUpAndNeverShowsNegativeZero() {
        assertEquals("13°", glanceTemp(forecast(tempC = 12.5), helsinki, TempUnit.CELSIUS, fetchedAt + 1_000))
        assertEquals("0°", glanceTemp(forecast(tempC = -0.3), helsinki, TempUnit.CELSIUS, fetchedAt + 1_000))
    }
}
