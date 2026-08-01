package fi.palonkorpi.sidehome.weather

/**
 * How a [Forecast] is written to and read back from `SharedPreferences` (SID-35).
 *
 * **Why not JSON**, when the thing we cache arrived as JSON: because `org.json` is an Android
 * framework class, and on this project's test setup (plain JVM unit tests, no Robolectric, and no
 * room to add a dependency) every one of its methods throws `Stub!`. A JSON cache would therefore be
 * a serialisation round-trip that no test can execute — and a cache that silently fails to decode is
 * a pane that is blank forever, on a device, with `Log.d` suppressed. This format is a few lines of
 * `split`, it is *ordinary Kotlin*, and [WeatherCacheTest] round-trips it. That is the whole
 * argument: testability bought with a format, at the cost of it being ours.
 *
 * It is versioned by its first line. A future build that changes the shape bumps [VERSION] and every
 * old blob decodes to `null` — which the store treats as "no cache", i.e. one refetch. There is no
 * migration path here on purpose: this is a 20-minute cache of a free public feed, and the cheapest
 * correct answer to "I can't read this" is to ask the sky again.
 *
 * Every number goes through Kotlin's own `toString`/`toDouble`, which are locale-independent.
 * `String.format` is not, and a Finnish locale would write `12,5` and fail to read it back.
 */
private const val VERSION = "sidehome-weather-1"

/** Serialise a forecast. Newlines in a place name (impossible from geocoding, cheap to rule out). */
fun encodeForecast(f: Forecast): String {
    val header = listOf(
        f.lat, f.lon, f.utcOffsetSeconds, f.nowTimeSec, f.nowTempC, f.nowCode, f.fetchedAtMillis,
    ).joinToString(";")
    val hours = f.hours.joinToString("|") { "${it.epochSec},${it.tempC},${it.code},${it.precipProbPct}" }
    val days = f.days.joinToString("|") { "${it.epochSec},${it.code},${it.highC},${it.lowC}" }
    return listOf(
        VERSION,
        f.placeName.replace('\n', ' ').replace('\r', ' '),
        header,
        hours,
        days,
    ).joinToString("\n")
}

/**
 * Read one back. Anything at all wrong — wrong version, truncated blob, a field that isn't a number
 * — is `null`, never an exception: this runs while the launcher is drawing the pane, and a corrupt
 * pref must cost a refetch rather than the home process.
 */
fun decodeForecast(raw: String?): Forecast? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val lines = raw.split("\n")
        if (lines.size < 5 || lines[0] != VERSION) return null
        val head = lines[2].split(";")
        if (head.size < 7) return null
        Forecast(
            placeName = lines[1],
            lat = head[0].toDouble(),
            lon = head[1].toDouble(),
            utcOffsetSeconds = head[2].toInt(),
            nowTimeSec = head[3].toLong(),
            nowTempC = head[4].toDouble(),
            nowCode = head[5].toInt(),
            hours = decodeRows(lines[3]) { c ->
                HourPoint(c[0].toLong(), c[1].toDouble(), c[2].toInt(), c[3].toInt())
            },
            days = decodeRows(lines[4]) { c ->
                DayPoint(c[0].toLong(), c[1].toInt(), c[2].toDouble(), c[3].toDouble())
            },
            fetchedAtMillis = head[6].toLong(),
        )
    }.getOrNull()
}

/** `a,b,c,d|a,b,c,d|…` → rows. A row with too few columns is dropped, not fatal. */
private fun <T> decodeRows(line: String, row: (List<String>) -> T): List<T> {
    if (line.isBlank()) return emptyList()
    return line.split("|").mapNotNull { cells ->
        val c = cells.split(",")
        if (c.size < 4) null else row(c)
    }
}
