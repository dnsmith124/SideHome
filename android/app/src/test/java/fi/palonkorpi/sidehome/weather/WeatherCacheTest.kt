package fi.palonkorpi.sidehome.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cache round-trip (SID-35). This test is the reason the cache isn't JSON: `org.json` is an
 * Android stub in a JVM unit test and throws on every call, so a JSON blob would be a serialisation
 * format that nothing could exercise until it was on a phone — where a silent decode failure looks
 * exactly like a pane that never loads, with `Log.d` suppressed to help.
 */
class WeatherCacheTest {

    private val sample = Forecast(
        placeName = "Helsinki",
        lat = 60.1695,
        lon = 24.9354,
        utcOffsetSeconds = 10800,
        nowTimeSec = 1_784_116_800L,
        nowTempC = 12.5,
        nowCode = 3,
        hours = listOf(
            HourPoint(1_784_116_800L, 12.5, 3, 10),
            HourPoint(1_784_120_400L, 13.25, 63, 80),
        ),
        days = listOf(
            DayPoint(1_784_073_600L, 3, 15.0, 8.5),
            DayPoint(1_784_160_000L, 0, 18.0, 9.0),
        ),
        fetchedAtMillis = 1_784_116_800_000L,
    )

    @Test fun survivesAWriteReadRoundTrip() {
        assertEquals(sample, decodeForecast(encodeForecast(sample)))
    }

    @Test fun survivesAnEmptyForecast() {
        // a feed that gave us a current reading and no columns still caches
        val bare = sample.copy(hours = emptyList(), days = emptyList())
        assertEquals(bare, decodeForecast(encodeForecast(bare)))
    }

    @Test fun keepsNegativeAndFractionalTemperatures() {
        val winter = sample.copy(
            nowTempC = -12.75,
            hours = listOf(HourPoint(1_784_116_800L, -0.5, 73, 90)),
            days = listOf(DayPoint(1_784_073_600L, 73, -2.5, -14.25)),
        )
        assertEquals(winter, decodeForecast(encodeForecast(winter)))
    }

    @Test fun keepsAPlaceNameWithSeparatorsInIt() {
        // the name is a whole line of its own, so the row/field separators can't reach it
        val awkward = sample.copy(placeName = "Sant Julià de Lòria; the one, with|everything")
        assertEquals(awkward, decodeForecast(encodeForecast(awkward)))
    }

    @Test fun flattensNewlinesInAPlaceName() {
        // the only character the format genuinely can't carry — ruled out on the way in
        val decoded = decodeForecast(encodeForecast(sample.copy(placeName = "Foo\nBar")))
        assertEquals("Foo Bar", decoded?.placeName)
    }

    @Test fun readsNothingAsNoCache() {
        assertNull(decodeForecast(null))
        assertNull(decodeForecast(""))
        assertNull(decodeForecast("   "))
    }

    @Test fun rejectsAnotherVersionsBlob() {
        // a future build bumps the version and every old blob decodes to "no cache" = one refetch
        val other = encodeForecast(sample).replaceFirst("sidehome-weather-1", "sidehome-weather-9")
        assertNull(decodeForecast(other))
    }

    @Test fun rejectsGarbageRatherThanThrowing() {
        // this decodes while the launcher is drawing; a corrupt pref must cost a fetch, not a crash
        assertNull(decodeForecast("nonsense"))
        assertNull(decodeForecast("sidehome-weather-1\nHelsinki"))
        assertNull(decodeForecast("sidehome-weather-1\nHelsinki\nnot;a;number;at;all;x;y\n\n"))
    }

    @Test fun dropsATruncatedRowRatherThanTheWholeBlob() {
        val encoded = encodeForecast(sample)
        val hoursLine = "1784116800,12.5,3,10|1784120400,13.25"
        val damaged = encoded.replaceFirst("1784116800,12.5,3,10|1784120400,13.25,63,80", hoursLine)
        val decoded = decodeForecast(damaged)
        assertEquals(1, decoded?.hours?.size)
        assertEquals(2, decoded?.days?.size)
    }
}
