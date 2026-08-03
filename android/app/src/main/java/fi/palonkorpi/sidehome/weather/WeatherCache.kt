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
// v2 (SID-184): the header grew the three wind fields; v3: hour rows grew wind speed + direction;
// v4: feels-like in the header, sun/rain-odds/UV in the day rows, and a sixth line of quarters.
// Older blobs decode to null = one refetch.
private const val VERSION = "sidehome-weather-4"

/** Serialise a forecast. Newlines in a place name (impossible from geocoding, cheap to rule out). */
fun encodeForecast(f: Forecast): String {
    val header = listOf(
        f.lat, f.lon, f.utcOffsetSeconds, f.nowTimeSec, f.nowTempC, f.nowCode, f.fetchedAtMillis,
        f.nowWindKmh, f.nowGustKmh, f.nowWindDirDeg, f.nowFeelsC,
    ).joinToString(";")
    val hours = f.hours.joinToString("|") {
        "${it.epochSec},${it.tempC},${it.code},${it.precipProbPct},${it.windKmh},${it.windDirDeg}"
    }
    val days = f.days.joinToString("|") {
        "${it.epochSec},${it.code},${it.highC},${it.lowC}" +
            ",${it.sunriseSec},${it.sunsetSec},${it.precipProbMaxPct},${it.uvMax}"
    }
    val quarters = f.quarters.joinToString("|") { "${it.epochSec},${it.precipMm}" }
    return listOf(
        VERSION,
        f.placeName.replace('\n', ' ').replace('\r', ' '),
        header,
        hours,
        days,
        quarters,
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
        if (lines.size < 6 || lines[0] != VERSION) return null
        val head = lines[2].split(";")
        if (head.size < 11) return null
        Forecast(
            placeName = lines[1],
            lat = head[0].toDouble(),
            lon = head[1].toDouble(),
            utcOffsetSeconds = head[2].toInt(),
            nowTimeSec = head[3].toLong(),
            nowTempC = head[4].toDouble(),
            nowCode = head[5].toInt(),
            hours = decodeRows(lines[3], minCols = 6) { c ->
                HourPoint(
                    c[0].toLong(), c[1].toDouble(), c[2].toInt(), c[3].toInt(),
                    windKmh = c[4].toDouble(), windDirDeg = c[5].toInt(),
                )
            },
            days = decodeRows(lines[4], minCols = 8) { c ->
                DayPoint(
                    c[0].toLong(), c[1].toInt(), c[2].toDouble(), c[3].toDouble(),
                    sunriseSec = c[4].toLong(), sunsetSec = c[5].toLong(),
                    precipProbMaxPct = c[6].toInt(), uvMax = c[7].toDouble(),
                )
            },
            fetchedAtMillis = head[6].toLong(),
            // "NaN".toDouble() round-trips, so an absent wind survives the cache as an absence.
            nowWindKmh = head[7].toDouble(),
            nowGustKmh = head[8].toDouble(),
            nowWindDirDeg = head[9].toInt(),
            nowFeelsC = head[10].toDouble(),
            quarters = decodeRows(lines[5], minCols = 2) { c ->
                QuarterPoint(c[0].toLong(), c[1].toDouble())
            },
        )
    }.getOrNull()
}

/** `a,b,c,…|a,b,c,…` → rows. A row with fewer than [minCols] columns is dropped, not fatal. */
private fun <T> decodeRows(line: String, minCols: Int, row: (List<String>) -> T): List<T> {
    if (line.isBlank()) return emptyList()
    return line.split("|").mapNotNull { cells ->
        val c = cells.split(",")
        if (c.size < minCols) null else row(c)
    }
}
