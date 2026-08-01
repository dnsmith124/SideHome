package fi.palonkorpi.sidehome.weather

import fi.palonkorpi.sidehome.settings.ClockFormat
import fi.palonkorpi.sidehome.settings.formatClock
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Future pane's weather model and every decision it makes — all of it pure (SPEC.md §4a,
 * SID-35). Nothing here touches Android, the network, or the clock: times arrive as epoch seconds
 * and a UTC offset, "now" arrives as a parameter. That is deliberate and load-bearing, because it is
 * the only part of this feature that can be unit-tested — the JSON, the socket, and the composables
 * cannot be, on this project's test setup (JVM unit tests, no Robolectric, and `org.json` is an
 * Android stub that throws). So the rule for this package is: the logic lives here, and the layers
 * around it stay thin enough to audit by reading.
 *
 * Everything is stored and cached in **Celsius**, always, and converted at the moment of display.
 * The unit is a rendering preference; making it part of the fetch would mean a cache that a settings
 * toggle silently invalidates, and a 20-minute-old forecast thrown away for a change of glyph.
 */

/** A place the user typed and picked. Coords-only — no location permission is ever requested. */
data class Place(val name: String, val lat: Double, val lon: Double)

/** A geocoding hit: the [place] we'd store, plus the "Uusimaa, Finland" line that disambiguates it. */
data class GeoResult(val place: Place, val region: String)

/** How temperatures render. Persisted in [fi.palonkorpi.sidehome.settings.SettingsStore]. */
enum class TempUnit { CELSIUS, FAHRENHEIT }

/** One hourly sample. [precipProbPct] is 0..100; Open-Meteo sends null for it, which decodes to 0. */
data class HourPoint(
    val epochSec: Long,
    val tempC: Double,
    val code: Int,
    val precipProbPct: Int,
)

/** One day. [epochSec] is that day's local midnight. */
data class DayPoint(
    val epochSec: Long,
    val code: Int,
    val highC: Double,
    val lowC: Double,
)

/**
 * A whole forecast, exactly as cached. It carries its own [placeName] and coords so a cache written
 * for Helsinki can never be rendered under the heading "Berlin" — see [matches].
 *
 * [utcOffsetSeconds] is the *place's* offset (Open-Meteo's `timezone=auto`), which is what makes
 * "rain starts ~16:00" mean 16:00 there. The "as of" stamp is the one time we deliberately use the
 * phone's own zone instead: it says when *you* last had a signal.
 */
data class Forecast(
    val placeName: String,
    val lat: Double,
    val lon: Double,
    val utcOffsetSeconds: Int,
    val nowTimeSec: Long,
    val nowTempC: Double,
    val nowCode: Int,
    val hours: List<HourPoint>,
    val days: List<DayPoint>,
    val fetchedAtMillis: Long,
) {
    /** Today's high/low, if the feed gave us a today at all. */
    val today: DayPoint? get() = days.firstOrNull()

    /** Tomorrow's, likewise. */
    val tomorrow: DayPoint? get() = days.getOrNull(1)
}

// ---- staleness ---------------------------------------------------------------------------------

/** How old a cached forecast may get before a *viewed* pane refetches it (SPEC §4a: "~20 min"). */
const val CACHE_MAX_AGE_MS: Long = 20 * 60 * 1000L

/**
 * How old it must get before the pane admits it, with a quiet "as of HH:MM". Deliberately much
 * larger than [CACHE_MAX_AGE_MS]: every refetch spends a moment being 20 minutes old and about to
 * be replaced, and stamping that instant would flash "as of" onto a pane that is fine. The stamp is
 * for the case that actually matters — no network, data from this morning — so it appears only once
 * the age is something a person would want to know about.
 */
const val AS_OF_AFTER_MS: Long = 90 * 60 * 1000L

/** Coordinates this close are the same place; below it, float noise from a prefs round-trip. */
private const val COORD_EPSILON = 0.0005

/** Is this cached forecast for [place]? A cache for another city must never render under its name. */
fun Forecast?.matches(place: Place?): Boolean {
    if (this == null || place == null) return false
    return abs(lat - place.lat) < COORD_EPSILON && abs(lon - place.lon) < COORD_EPSILON
}

/**
 * Should a pane that has just come into view fetch? True when there is no cache, when the cache is
 * for somewhere else, or when it is older than [CACHE_MAX_AGE_MS]. A clock that has gone *backwards*
 * (a manual time change) also counts as stale rather than as freshness that lasts until it catches
 * up.
 */
fun needsRefresh(cached: Forecast?, place: Place?, nowMillis: Long): Boolean {
    if (place == null) return false
    if (!cached.matches(place)) return true
    val age = nowMillis - cached!!.fetchedAtMillis
    return age >= CACHE_MAX_AGE_MS || age < 0
}

/** Whether to show the quiet "as of" stamp — see [AS_OF_AFTER_MS]. */
fun showAsOf(fetchedAtMillis: Long, nowMillis: Long): Boolean =
    nowMillis - fetchedAtMillis >= AS_OF_AFTER_MS

/** The stamp itself. Never "error", never a dialog — just when this was true (SPEC §4a). */
fun asOfText(hour: Int, minute: Int, format: ClockFormat): String =
    "as of ${formatClock(hour, minute, format)}"

// ---- time ---------------------------------------------------------------------------------------

/** Hour-of-day (0..23) at [utcOffsetSeconds] for an instant given in epoch seconds. */
fun localHourOfDay(epochSec: Long, utcOffsetSeconds: Int): Int =
    (epochSec + utcOffsetSeconds).floorDiv(3600L).mod(24L).toInt()

/** Minute-of-hour (0..59), same terms. */
fun localMinute(epochSec: Long, utcOffsetSeconds: Int): Int =
    (epochSec + utcOffsetSeconds).floorDiv(60L).mod(60L).toInt()

/** Days since the epoch, at [utcOffsetSeconds]. 1970-01-01 = 0. */
fun localEpochDay(epochSec: Long, utcOffsetSeconds: Int): Long =
    (epochSec + utcOffsetSeconds).floorDiv(86_400L)

private val WEEKDAYS = listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")

/**
 * The week strip's two-letter day label. 1970-01-01 was a Thursday, which is where the `+4` comes
 * from; the strip starts at today and runs forward, so no locale's first-day-of-week is involved.
 */
fun weekdayLabel(epochSec: Long, utcOffsetSeconds: Int): String =
    WEEKDAYS[(localEpochDay(epochSec, utcOffsetSeconds) + 4).mod(7L).toInt()]

/**
 * An hour said the way a person would. Noon and midnight get their names — "clearing by noon" is
 * the line SPEC §4a actually asks for — and everything else defers to the user's own clock setting,
 * so the pane never contradicts the home's 12h/24h choice.
 */
fun friendlyHour(epochSec: Long, utcOffsetSeconds: Int, format: ClockFormat): String {
    val h = localHourOfDay(epochSec, utcOffsetSeconds)
    val m = localMinute(epochSec, utcOffsetSeconds)
    if (m == 0 && h == 12) return "noon"
    if (m == 0 && h == 0) return "midnight"
    return formatClock(h, m, format)
}

/**
 * The hourly strip's cell label — the tightest an hour can be said and still be read. Six cells share
 * a 480px row, so [friendlyHour]'s "4:00 pm" is four times too wide; this is the hour alone, honouring
 * the clock setting: "15" in 24h, "3p" / "12a" in 12h (a single a/p suffix, no minutes, no space).
 */
fun shortHourLabel(epochSec: Long, utcOffsetSeconds: Int, format: ClockFormat): String {
    val h = localHourOfDay(epochSec, utcOffsetSeconds)
    if (format == ClockFormat.H24) return h.toString()
    val suffix = if (h < 12) "a" else "p"
    val twelve = if (h % 12 == 0) 12 else h % 12
    return "$twelve$suffix"
}

/**
 * The next [count] hourly samples strictly after [nowEpochSec], in time order. Strictly after, so the
 * strip looks *ahead* rather than repeating the Now tier's current hour; fewer than [count] near the
 * end of the feed is returned as-is (the strip renders what it has rather than padding to a fixed
 * width with holes).
 */
fun upcomingHours(hours: List<HourPoint>, nowEpochSec: Long, count: Int): List<HourPoint> =
    hours.asSequence()
        .filter { it.epochSec > nowEpochSec }
        .sortedBy { it.epochSec }
        .take(count)
        .toList()

// ---- temperature --------------------------------------------------------------------------------

/** °C → °F. */
fun toFahrenheit(celsius: Double): Double = celsius * 9.0 / 5.0 + 32.0

/**
 * A temperature as the pane says it: whole degrees and a ring, no unit letter. The screen is 480px
 * wide and every number on it is the same unit — a "°C" on each would be six characters of saying
 * so. [roundToInt] rounds half-up and lands a small negative on `0`, never `-0`.
 *
 * NaN is a real input, not a paranoid one: a missing column in the feed decodes to `Double.NaN`, and
 * [roundToInt] *throws* on NaN. A dash on the pane; never an exception inside the launcher's draw.
 */
fun formatTemp(celsius: Double, unit: TempUnit): String {
    if (!celsius.isFinite()) return "—"
    val v = if (unit == TempUnit.FAHRENHEIT) toFahrenheit(celsius) else celsius
    return "${v.roundToInt()}°"
}

// ---- conditions ---------------------------------------------------------------------------------

/**
 * WMO weather-interpretation codes, the vocabulary Open-Meteo speaks. Grouped rather than listed
 * one-by-one because every question this pane asks is about the *family* ("is it wet?", "is it
 * clear?"), and a code we've never seen should answer those sensibly instead of falling off the end
 * of a `when`.
 */
private val DRIZZLE = setOf(51, 53, 55, 56, 57)
private val RAIN = setOf(61, 63, 65, 66, 67, 80, 81, 82)
private val SNOW = setOf(71, 73, 75, 77, 85, 86)
private val STORM = setOf(95, 96, 99)

/** Is something falling out of the sky? */
fun isWet(code: Int): Boolean = code in DRIZZLE || code in RAIN || code in SNOW || code in STORM

/** Clear or nearly so — the state "clearing by noon" is about arriving at. */
fun isClear(code: Int): Boolean = code == 0 || code == 1

/** Cloud, overcast or fog: dry, but the thing clearing would clear. */
fun isDull(code: Int): Boolean = code == 2 || code == 3 || code == 45 || code == 48

/** The word the Now line uses. Unknown codes get an em dash rather than a lie. */
fun conditionWord(code: Int): String = when (code) {
    0 -> "Clear"
    1 -> "Mostly clear"
    2 -> "Partly cloudy"
    3 -> "Cloudy"
    45, 48 -> "Fog"
    51, 53 -> "Drizzle"
    55 -> "Heavy drizzle"
    56, 57 -> "Freezing drizzle"
    61 -> "Light rain"
    63 -> "Rain"
    65 -> "Heavy rain"
    66, 67 -> "Freezing rain"
    71 -> "Light snow"
    73, 77 -> "Snow"
    75 -> "Heavy snow"
    80, 81 -> "Showers"
    82 -> "Heavy showers"
    85 -> "Snow showers"
    86 -> "Heavy snow showers"
    95 -> "Thunderstorm"
    96, 99 -> "Thunderstorm, hail"
    else -> "—"
}

/** The noun the change-line uses for what's falling: "rain starts ~16:00", "snow starts ~16:00". */
fun precipNoun(code: Int): String = when {
    code in SNOW -> "snow"
    code in STORM -> "storms"
    code in DRIZZLE -> "drizzle"
    else -> "rain"
}

/**
 * Which glyph a condition wears — keys into `ui/Glyphs.kt`. Every family now has its **own shape**
 * (rain and storm are real cloud glyphs added to the set), so a condition reads by silhouette alone
 * and nothing here relies on colour: the old accent-tinted-cloud stand-in for rain is gone, and with
 * it the highlight that made half the cells glow. Drizzle draws as rain — at glyph size the two
 * silhouettes are the same, and the word tier already carries the difference.
 */
fun conditionGlyph(code: Int): String = when {
    code in SNOW -> "snow"
    code in STORM -> "storm"
    code in DRIZZLE || code in RAIN -> "rain"
    isClear(code) -> "sun"
    else -> "cloud"
}

// ---- the one smart change-line -------------------------------------------------------------------

/** How likely rain must be before we promise it. Below this, "rain starts ~16:00" is a rumour. */
private const val RAIN_PROB_THRESHOLD = 55

/** How far ahead the change-line looks. Past this, "later today" stops being a thing you'd act on. */
private const val HORIZON_HOURS = 12

/**
 * The single line under Now — "rain starts ~16:00", "clearing by noon" — or **null when there is
 * nothing to say** (SPEC §4a). The null case is the whole point: a calm pane earns its calm by
 * leaving the space empty, not by filling it with "conditions remain cloudy".
 *
 * Three rules, in the order a person would care:
 *  1. dry now, wet within the horizon → when it starts;
 *  2. wet now, dry within the horizon → when it stops;
 *  3. dull now, clear within the horizon → when it clears.
 *
 * Rules 2 and 3 require the new state to *hold* for the following sample as well. A single dry hour
 * inside an afternoon of rain is not the rain stopping, and announcing it at 14:00 only to be raining
 * again at 16:00 is worse than having said nothing — which is the bar this line has to clear.
 */
fun changeLine(
    nowEpochSec: Long,
    nowCode: Int,
    hours: List<HourPoint>,
    utcOffsetSeconds: Int,
    format: ClockFormat,
): String? {
    val ahead = hours
        .filter { it.epochSec > nowEpochSec && it.epochSec <= nowEpochSec + HORIZON_HOURS * 3600L }
        .sortedBy { it.epochSec }
    if (ahead.isEmpty()) return null

    if (!isWet(nowCode)) {
        val onset = ahead.firstOrNull { isWet(it.code) && it.precipProbPct >= RAIN_PROB_THRESHOLD }
        if (onset != null) {
            return "${precipNoun(onset.code)} starts ~${friendlyHour(onset.epochSec, utcOffsetSeconds, format)}"
        }
    } else {
        val stops = ahead.indexOfFirst { !isWet(it.code) }
        if (stops >= 0 && holds(ahead, stops) { !isWet(it.code) }) {
            return "${precipNoun(nowCode)} stops ~${friendlyHour(ahead[stops].epochSec, utcOffsetSeconds, format)}"
        }
    }

    if (isDull(nowCode)) {
        val clears = ahead.indexOfFirst { isClear(it.code) }
        if (clears >= 0 && holds(ahead, clears) { isClear(it.code) }) {
            return "clearing by ${friendlyHour(ahead[clears].epochSec, utcOffsetSeconds, format)}"
        }
    }
    return null
}

/** Does the state at [index] still hold at the next sample? (The last sample holds by default.) */
private inline fun holds(ahead: List<HourPoint>, index: Int, predicate: (HourPoint) -> Boolean): Boolean {
    val next = ahead.getOrNull(index + 1) ?: return true
    return predicate(next)
}

// ---- assembling a forecast from decoded feed columns ---------------------------------------------

/**
 * Build a [Forecast] from Open-Meteo's column-per-field arrays. Pure, and separated from the JSON
 * on purpose: this is where the zipping, the truncation and the ordering can go wrong, and this is
 * the half of parsing that a unit test can actually reach. [WeatherApi] only pulls arrays out of the
 * envelope and hands them here.
 *
 * Ragged columns are truncated to the shortest rather than rejected — a feed that sends 24 temps and
 * 23 codes should cost us one hour, not the whole pane.
 */
fun buildForecast(
    place: Place,
    utcOffsetSeconds: Int,
    nowTimeSec: Long,
    nowTempC: Double,
    nowCode: Int,
    hourlyTime: List<Long>,
    hourlyTempC: List<Double>,
    hourlyCode: List<Int>,
    hourlyPrecipProb: List<Int>,
    dailyTime: List<Long>,
    dailyCode: List<Int>,
    dailyMaxC: List<Double>,
    dailyMinC: List<Double>,
    fetchedAtMillis: Long,
): Forecast {
    val hn = minOf(hourlyTime.size, hourlyTempC.size, hourlyCode.size, hourlyPrecipProb.size)
    val dn = minOf(dailyTime.size, dailyCode.size, dailyMaxC.size, dailyMinC.size)
    return Forecast(
        placeName = place.name,
        lat = place.lat,
        lon = place.lon,
        utcOffsetSeconds = utcOffsetSeconds,
        nowTimeSec = nowTimeSec,
        nowTempC = nowTempC,
        nowCode = nowCode,
        hours = (0 until hn).map {
            HourPoint(hourlyTime[it], hourlyTempC[it], hourlyCode[it], hourlyPrecipProb[it])
        },
        days = (0 until dn).map {
            DayPoint(dailyTime[it], dailyCode[it], dailyMaxC[it], dailyMinC[it])
        },
        fetchedAtMillis = fetchedAtMillis,
    )
}

/** The "Uusimaa, Finland" line under a geocoding hit. Either half may be missing; neither is fatal. */
fun regionLabel(admin1: String?, country: String?): String =
    listOfNotNull(admin1?.takeIf { it.isNotBlank() }, country?.takeIf { it.isNotBlank() })
        .joinToString(", ")
