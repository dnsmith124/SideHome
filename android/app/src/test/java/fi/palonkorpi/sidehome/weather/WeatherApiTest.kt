package fi.palonkorpi.sidehome.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two URLs (SID-35). Everything else in [WeatherApi] needs a socket or `org.json` and is out of
 * reach here — but the URLs are worth pinning precisely *because* they fail quietly: Open-Meteo
 * answers `200` to a request with a misspelled field and simply omits it, so a typo doesn't throw,
 * it just makes the week strip empty forever.
 *
 * These assert the exact strings on purpose. The point isn't that the URL is *a* URL; it's that
 * these specific parameter names are the ones the parser downstream reads back.
 */
class WeatherApiTest {

    @Test fun forecast_asksForEveryFieldThePaneRenders() {
        val url = WeatherApi.forecastUrl(60.1695, 24.9354)
        assertEquals(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=60.1695&longitude=24.9354" +
                "&current=temperature_2m,weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m" +
                ",apparent_temperature" +
                "&hourly=temperature_2m,weather_code,precipitation_probability,wind_speed_10m,wind_direction_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                ",sunrise,sunset,precipitation_probability_max,uv_index_max" +
                "&minutely_15=precipitation" +
                "&timezone=auto&timeformat=unixtime&forecast_days=7",
            url,
        )
    }

    @Test fun forecast_asksForUnixTimesAndTheLocalZone() {
        // the pair that lets Weather.kt do all its time arithmetic on epochs plus one offset —
        // no date strings, no parsing, no locale
        val url = WeatherApi.forecastUrl(0.0, 0.0)
        assertTrue(url.contains("timeformat=unixtime"))
        assertTrue(url.contains("timezone=auto"))
    }

    @Test fun forecast_neverAsksForAUnit() {
        // Celsius is Open-Meteo's default and the cache's only unit; °F happens at render time, so
        // flipping the setting must never be able to invalidate a cache
        val url = WeatherApi.forecastUrl(60.1695, 24.9354)
        assertTrue(!url.contains("temperature_unit"))
        assertTrue(!url.contains("fahrenheit"))
    }

    @Test fun forecast_writesCoordsLocaleIndependently() {
        // String.format would write "60,1695" on a Finnish device and Open-Meteo would reject it
        assertTrue(WeatherApi.forecastUrl(-33.8688, 151.2093).contains("latitude=-33.8688&longitude=151.2093"))
    }

    @Test fun geocoding_escapesTheTypedName() {
        assertEquals(
            "https://geocoding-api.open-meteo.com/v1/search?name=New+York&count=6&language=en&format=json",
            WeatherApi.geocodeUrl("New York"),
        )
    }

    @Test fun geocoding_escapesNonAsciiAndPunctuation() {
        // a launcher for a Finnish phone will be asked for Jyväskylä on day one
        assertTrue(WeatherApi.geocodeUrl("Jyväskylä").contains("name=Jyv%C3%A4skyl%C3%A4"))
        assertTrue(WeatherApi.geocodeUrl("A&B").contains("name=A%26B"))
    }
}
