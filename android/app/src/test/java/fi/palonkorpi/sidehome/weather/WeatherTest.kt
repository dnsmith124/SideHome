package fi.palonkorpi.sidehome.weather

import fi.palonkorpi.sidehome.settings.ClockFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Future pane's decisions (SID-35). This is where the feature is actually verified: the socket,
 * the JSON envelope and the composables are all unreachable from a JVM unit test on this project's
 * setup, so `Weather.kt` deliberately holds everything that could be wrong, and this holds the proof.
 *
 * Times are built off one fixed instant rather than the clock — 2026-07-15T00:00:00Z, a Wednesday —
 * so every assertion here means the same thing in June as in December, and in Helsinki as in UTC.
 */
private const val WED_MIDNIGHT_UTC = 1_784_073_600L // 2026-07-15T00:00:00Z
private const val HOUR = 3600L

/** An instant on the reference Wednesday, in UTC. */
private fun at(hour: Int): Long = WED_MIDNIGHT_UTC + hour * HOUR

private fun hour(hour: Int, code: Int, prob: Int = 0, tempC: Double = 10.0) =
    HourPoint(epochSec = at(hour), tempC = tempC, code = code, precipProbPct = prob)

class WeatherTest {

    // ---- temperature --------------------------------------------------------------------------

    @Test fun temp_roundsToWholeDegrees() {
        assertEquals("12°", formatTemp(12.4, TempUnit.CELSIUS))
        assertEquals("13°", formatTemp(12.5, TempUnit.CELSIUS))
        assertEquals("-4°", formatTemp(-3.6, TempUnit.CELSIUS))
    }

    @Test fun temp_neverRendersMinusZero() {
        // a rounded-away negative is 0°, not -0° — the pane would look broken for a whole degree
        assertEquals("0°", formatTemp(-0.4, TempUnit.CELSIUS))
    }

    @Test fun temp_convertsToFahrenheit() {
        assertEquals("32°", formatTemp(0.0, TempUnit.FAHRENHEIT))
        assertEquals("212°", formatTemp(100.0, TempUnit.FAHRENHEIT))
        assertEquals("54°", formatTemp(12.0, TempUnit.FAHRENHEIT))
    }

    @Test fun temp_nonFiniteIsADashNotACrash() {
        // a missing column in the feed decodes to NaN, and roundToInt throws on NaN
        assertEquals("—", formatTemp(Double.NaN, TempUnit.CELSIUS))
        assertEquals("—", formatTemp(Double.POSITIVE_INFINITY, TempUnit.FAHRENHEIT))
    }

    // ---- time ---------------------------------------------------------------------------------

    @Test fun localHour_appliesThePlacesOffset() {
        assertEquals(12, localHourOfDay(at(12), 0))
        assertEquals(15, localHourOfDay(at(12), 3 * 3600))   // Helsinki, summer
        assertEquals(8, localHourOfDay(at(12), -4 * 3600))   // New York, summer
    }

    @Test fun localHour_wrapsAcrossMidnight() {
        assertEquals(1, localHourOfDay(at(22), 3 * 3600))
        assertEquals(21, localHourOfDay(at(1), -4 * 3600))
    }

    @Test fun localMinute_readsTheMinuteField() {
        assertEquals(0, localMinute(at(12), 0))
        assertEquals(30, localMinute(at(12) + 1800, 0))
    }

    @Test fun weekday_labelsTheReferenceWednesday() {
        assertEquals("We", weekdayLabel(at(12), 0))
        assertEquals("Th", weekdayLabel(at(36), 0))
        assertEquals("Tu", weekdayLabel(at(-12), 0))
    }

    @Test fun weekday_isTheLocalDayNotTheUtcOne() {
        // 23:00 UTC on Wednesday is already Thursday in Helsinki (+3)
        assertEquals("Th", weekdayLabel(at(23), 3 * 3600))
        assertEquals("We", weekdayLabel(at(23), 0))
    }

    @Test fun friendlyHour_namesNoonAndMidnight() {
        assertEquals("noon", friendlyHour(at(12), 0, ClockFormat.H24))
        assertEquals("midnight", friendlyHour(at(24), 0, ClockFormat.H24))
    }

    @Test fun friendlyHour_otherwiseFollowsTheUsersClock() {
        assertEquals("16:00", friendlyHour(at(16), 0, ClockFormat.H24))
        assertEquals("4:00 pm", friendlyHour(at(16), 0, ClockFormat.H12))
    }

    @Test fun shortHourLabel_isTheBareHourIn24h() {
        assertEquals("16", shortHourLabel(at(16), 0, ClockFormat.H24))
        assertEquals("0", shortHourLabel(at(24), 0, ClockFormat.H24)) // local midnight
        assertEquals("15", shortHourLabel(at(12), 3 * 3600, ClockFormat.H24)) // Helsinki
    }

    @Test fun shortHourLabel_isACompactAmPmSuffixIn12h() {
        assertEquals("4p", shortHourLabel(at(16), 0, ClockFormat.H12))
        assertEquals("12a", shortHourLabel(at(24), 0, ClockFormat.H12)) // midnight
        assertEquals("12p", shortHourLabel(at(12), 0, ClockFormat.H12)) // noon
        assertEquals("9a", shortHourLabel(at(9), 0, ClockFormat.H12))
    }

    @Test fun upcomingHours_takesTheNextNStrictlyAfterNow() {
        val hrs = (0..10).map { hour(it, code = 0) }
        val next = upcomingHours(hrs, at(3), 4)
        assertEquals(4, next.size)
        assertEquals(at(4), next.first().epochSec) // strictly after 3, not 3 itself
        assertEquals(at(7), next.last().epochSec)
    }

    @Test fun upcomingHours_returnsFewerNearTheEndRatherThanPadding() {
        val hrs = (0..5).map { hour(it, code = 0) }
        val next = upcomingHours(hrs, at(4), 6)
        assertEquals(1, next.size) // only hour 5 is after 4
        assertEquals(at(5), next.first().epochSec)
    }

    // ---- conditions ----------------------------------------------------------------------------

    @Test fun conditions_groupByFamily() {
        assertTrue(isWet(63))
        assertTrue(isWet(80))
        assertTrue(isWet(73))
        assertTrue(isWet(95))
        assertFalse(isWet(3))
        assertFalse(isWet(45))
        assertTrue(isClear(0))
        assertTrue(isClear(1))
        assertFalse(isClear(2))
        assertTrue(isDull(3))
        assertTrue(isDull(48))
        assertFalse(isDull(0))
    }

    @Test fun conditions_unknownCodeIsADashNotAGuess() {
        assertEquals("—", conditionWord(4242))
    }

    @Test fun conditions_nameTheCommonSkies() {
        assertEquals("Clear", conditionWord(0))
        assertEquals("Cloudy", conditionWord(3))
        assertEquals("Rain", conditionWord(63))
        assertEquals("Thunderstorm", conditionWord(95))
    }

    @Test fun precipNoun_saysWhatIsFalling() {
        assertEquals("rain", precipNoun(63))
        assertEquals("rain", precipNoun(80))
        assertEquals("snow", precipNoun(73))
        assertEquals("drizzle", precipNoun(53))
        assertEquals("storms", precipNoun(95))
    }

    @Test fun glyphs_mapToKeysTheIconSetActuallyHas() {
        // these are keys into ui/Glyphs.kt; a typo here is a blank cell on the device
        assertEquals("sun", conditionGlyph(0))
        assertEquals("cloud", conditionGlyph(3))
        assertEquals("rain", conditionGlyph(63))
        assertEquals("snow", conditionGlyph(73))
        assertEquals("storm", conditionGlyph(95))
    }

    @Test fun glyphs_everyWetFamilyHasItsOwnShapeNotACloud() {
        // rain, storm and snow read by silhouette now — no colour tint distinguishes them, so a wet
        // code must never fall back to the dry "cloud" glyph
        assertEquals("rain", conditionGlyph(51)) // drizzle
        assertEquals("rain", conditionGlyph(80)) // showers
        assertEquals("storm", conditionGlyph(99)) // thunderstorm with hail
        assertEquals("snow", conditionGlyph(85)) // snow showers
    }

    // ---- the change-line -----------------------------------------------------------------------

    @Test fun changeLine_announcesRainStarting() {
        val line = changeLine(
            nowEpochSec = at(12),
            nowCode = 3,
            hours = listOf(hour(13, 3), hour(14, 3), hour(16, 63, prob = 80), hour(17, 63, prob = 90)),
            utcOffsetSeconds = 0,
            format = ClockFormat.H24,
        )
        assertEquals("rain starts ~16:00", line)
    }

    @Test fun changeLine_ignoresRainItIsNotConfidentAbout() {
        // a 30%-chance hour is a rumour; the line only promises what the feed is fairly sure of
        val line = changeLine(
            nowEpochSec = at(12),
            nowCode = 3,
            hours = listOf(hour(13, 61, prob = 30), hour(16, 63, prob = 80)),
            utcOffsetSeconds = 0,
            format = ClockFormat.H24,
        )
        assertEquals("rain starts ~16:00", line)
    }

    @Test fun changeLine_saysWhatIsFalling() {
        val line = changeLine(
            nowEpochSec = at(12),
            nowCode = 3,
            hours = listOf(hour(15, 73, prob = 80)),
            utcOffsetSeconds = 0,
            format = ClockFormat.H24,
        )
        assertEquals("snow starts ~15:00", line)
    }

    @Test fun changeLine_usesThePlacesOwnClock() {
        // 16:00 UTC is 19:00 where the forecast is — the line is about there, not about here
        val line = changeLine(
            nowEpochSec = at(12),
            nowCode = 3,
            hours = listOf(hour(16, 63, prob = 80)),
            utcOffsetSeconds = 3 * 3600,
            format = ClockFormat.H24,
        )
        assertEquals("rain starts ~19:00", line)
    }

    @Test fun changeLine_followsTheUsersClockFormat() {
        val line = changeLine(
            nowEpochSec = at(12),
            nowCode = 3,
            hours = listOf(hour(16, 63, prob = 80)),
            utcOffsetSeconds = 0,
            format = ClockFormat.H12,
        )
        assertEquals("rain starts ~4:00 pm", line)
    }

    @Test fun changeLine_announcesRainStopping() {
        val line = changeLine(
            nowEpochSec = at(12),
            nowCode = 63,
            hours = listOf(hour(13, 63, prob = 90), hour(14, 3), hour(15, 3)),
            utcOffsetSeconds = 0,
            format = ClockFormat.H24,
        )
        assertEquals("rain stops ~14:00", line)
    }

    @Test fun changeLine_doesNotCallAOneHourGapTheEndOfTheRain() {
        // dry at 14:00, raining again at 15:00 — saying "rain stops" here is worse than silence
        val line = changeLine(
            nowEpochSec = at(12),
            nowCode = 63,
            hours = listOf(hour(13, 63, prob = 90), hour(14, 3), hour(15, 63, prob = 90)),
            utcOffsetSeconds = 0,
            format = ClockFormat.H24,
        )
        assertNull(line)
    }

    @Test fun changeLine_announcesClearing() {
        val line = changeLine(
            nowEpochSec = at(9),
            nowCode = 3,
            hours = listOf(hour(10, 3), hour(12, 0), hour(13, 1)),
            utcOffsetSeconds = 0,
            format = ClockFormat.H24,
        )
        assertEquals("clearing by noon", line)
    }

    @Test fun changeLine_doesNotCallAOneHourBreakClearing() {
        val line = changeLine(
            nowEpochSec = at(9),
            nowCode = 3,
            hours = listOf(hour(10, 0), hour(11, 3), hour(12, 3)),
            utcOffsetSeconds = 0,
            format = ClockFormat.H24,
        )
        assertNull(line)
    }

    @Test fun changeLine_prefersRainToClearing() {
        // both are true; the one you'd want told is the one that gets you wet
        val line = changeLine(
            nowEpochSec = at(9),
            nowCode = 3,
            hours = listOf(hour(11, 63, prob = 80), hour(12, 63, prob = 80), hour(15, 0), hour(16, 0)),
            utcOffsetSeconds = 0,
            format = ClockFormat.H24,
        )
        assertEquals("rain starts ~11:00", line)
    }

    @Test fun changeLine_isNullWhenThereIsNothingToSay() {
        // SPEC §4a: omitted, not padded. A settled clear day says nothing at all.
        assertNull(
            changeLine(at(12), 0, listOf(hour(13, 0), hour(14, 1), hour(15, 0)), 0, ClockFormat.H24),
        )
    }

    @Test fun changeLine_isNullWithNoHoursAhead() {
        assertNull(changeLine(at(12), 3, emptyList(), 0, ClockFormat.H24))
        // every sample is in the past — a stale feed must not announce this morning's rain
        assertNull(changeLine(at(12), 3, listOf(hour(8, 63, prob = 90)), 0, ClockFormat.H24))
    }

    @Test fun changeLine_looksTwelveHoursAheadAndNoFurther() {
        assertEquals(
            "rain starts ~23:00",
            changeLine(at(12), 3, listOf(hour(23, 63, prob = 90)), 0, ClockFormat.H24),
        )
        // +13h: true, but not news you'd act on now, and it would crowd out tomorrow's own line
        assertNull(changeLine(at(12), 3, listOf(hour(25, 63, prob = 90)), 0, ClockFormat.H24))
    }

    @Test fun changeLine_readsHoursInTimeOrderNotFeedOrder() {
        val line = changeLine(
            nowEpochSec = at(12),
            nowCode = 3,
            hours = listOf(hour(17, 63, prob = 90), hour(15, 63, prob = 90)),
            utcOffsetSeconds = 0,
            format = ClockFormat.H24,
        )
        assertEquals("rain starts ~15:00", line)
    }

    // ---- cache staleness -----------------------------------------------------------------------

    private val helsinki = Place("Helsinki", 60.1695, 24.9354)
    private val berlin = Place("Berlin", 52.52, 13.405)

    private fun forecastFor(place: Place, fetchedAtMillis: Long) = Forecast(
        placeName = place.name,
        lat = place.lat,
        lon = place.lon,
        utcOffsetSeconds = 3 * 3600,
        nowTimeSec = at(12),
        nowTempC = 12.0,
        nowCode = 3,
        hours = emptyList(),
        days = emptyList(),
        fetchedAtMillis = fetchedAtMillis,
    )

    @Test fun refresh_isNeededWithNoCache() {
        assertTrue(needsRefresh(null, helsinki, 1_000_000L))
    }

    @Test fun refresh_isNotNeededForAYoungCache() {
        val f = forecastFor(helsinki, 1_000_000L)
        assertFalse(needsRefresh(f, helsinki, 1_000_000L + 60_000L))
    }

    @Test fun refresh_isNeededOnceTheCacheIsTwentyMinutesOld() {
        val f = forecastFor(helsinki, 1_000_000L)
        assertFalse(needsRefresh(f, helsinki, 1_000_000L + CACHE_MAX_AGE_MS - 1))
        assertTrue(needsRefresh(f, helsinki, 1_000_000L + CACHE_MAX_AGE_MS))
    }

    @Test fun refresh_isNeededWhenTheClockWentBackwards() {
        // a manual time change must not grant the cache freshness until the clock catches up
        val f = forecastFor(helsinki, 1_000_000L)
        assertTrue(needsRefresh(f, helsinki, 500_000L))
    }

    @Test fun refresh_isNeededWhenThePlaceChanged() {
        // the cache is minutes old and completely wrong
        val f = forecastFor(helsinki, 1_000_000L)
        assertTrue(needsRefresh(f, berlin, 1_000_000L))
    }

    @Test fun refresh_isNeverNeededWithNoPlaceSet() {
        // nothing to fetch, and nothing to fetch it about
        assertFalse(needsRefresh(null, null, 1_000_000L))
    }

    @Test fun cache_matchesOnlyItsOwnCoords() {
        val f = forecastFor(helsinki, 1_000_000L)
        assertTrue(f.matches(helsinki))
        assertFalse(f.matches(berlin))
        assertFalse(f.matches(null))
        assertFalse((null as Forecast?).matches(helsinki))
    }

    @Test fun cache_toleratesFloatNoiseFromAPrefsRoundTrip() {
        val f = forecastFor(helsinki, 1_000_000L)
        assertTrue(f.matches(helsinki.copy(lat = helsinki.lat + 0.0001)))
        // a renamed place at the same coords is the same place; only the coords decide
        assertTrue(f.matches(helsinki.copy(name = "Helsingfors")))
    }

    // ---- the "as of" stamp ----------------------------------------------------------------------

    @Test fun asOf_isHiddenForAForecastThatIsMerelyDueARefresh() {
        // every refetch spends a moment being 20 min old; stamping that would flash "as of" for
        // nothing
        assertFalse(showAsOf(0L, CACHE_MAX_AGE_MS + 1))
    }

    @Test fun asOf_appearsOnceTheDataIsGenuinelyOld() {
        assertFalse(showAsOf(0L, AS_OF_AFTER_MS - 1))
        assertTrue(showAsOf(0L, AS_OF_AFTER_MS))
    }

    @Test fun asOf_readsAsAStatementNotAnError() {
        assertEquals("as of 09:05", asOfText(9, 5, ClockFormat.H24))
        assertEquals("as of 9:05 am", asOfText(9, 5, ClockFormat.H12))
    }

    // ---- assembling a forecast ------------------------------------------------------------------

    @Test fun build_zipsTheFeedsColumnsIntoRows() {
        val f = buildForecast(
            place = helsinki,
            utcOffsetSeconds = 3 * 3600,
            nowTimeSec = at(12),
            nowTempC = 12.5,
            nowCode = 3,
            hourlyTime = listOf(at(12), at(13)),
            hourlyTempC = listOf(12.5, 13.0),
            hourlyCode = listOf(3, 63),
            hourlyPrecipProb = listOf(10, 80),
            dailyTime = listOf(at(0), at(24)),
            dailyCode = listOf(3, 0),
            dailyMaxC = listOf(15.0, 18.0),
            dailyMinC = listOf(8.0, 9.0),
            fetchedAtMillis = 1_000_000L,
        )
        assertEquals("Helsinki", f.placeName)
        assertEquals(listOf(HourPoint(at(12), 12.5, 3, 10), HourPoint(at(13), 13.0, 63, 80)), f.hours)
        assertEquals(DayPoint(at(0), 3, 15.0, 8.0), f.today)
        assertEquals(DayPoint(at(24), 0, 18.0, 9.0), f.tomorrow)
    }

    @Test fun build_truncatesRaggedColumnsRatherThanFailing() {
        // a feed that sends one fewer code than temps should cost an hour, not the pane
        val f = buildForecast(
            place = helsinki,
            utcOffsetSeconds = 0,
            nowTimeSec = at(12),
            nowTempC = 12.0,
            nowCode = 3,
            hourlyTime = listOf(at(12), at(13), at(14)),
            hourlyTempC = listOf(12.0, 13.0, 14.0),
            hourlyCode = listOf(3, 3),
            hourlyPrecipProb = listOf(0, 0, 0),
            dailyTime = emptyList(),
            dailyCode = listOf(3),
            dailyMaxC = listOf(15.0),
            dailyMinC = listOf(8.0),
            fetchedAtMillis = 1_000_000L,
        )
        assertEquals(2, f.hours.size)
        assertEquals(0, f.days.size)
        assertNull(f.today)
        assertNull(f.tomorrow)
    }

    // ---- geocoding labels -----------------------------------------------------------------------

    @Test fun region_joinsWhatItHas() {
        assertEquals("Uusimaa, Finland", regionLabel("Uusimaa", "Finland"))
        assertEquals("Finland", regionLabel(null, "Finland"))
        assertEquals("Finland", regionLabel("", "Finland"))
        assertEquals("Uusimaa", regionLabel("Uusimaa", null))
        assertEquals("", regionLabel(null, null))
    }

    // ---- wind (SID-184) --------------------------------------------------------------------

    @Test fun `the compass rose reads whence the wind blows, in eight winds`() {
        assertEquals("N", compassLabel(0))
        assertEquals("N", compassLabel(360))
        assertEquals("NE", compassLabel(45))
        assertEquals("E", compassLabel(90))
        assertEquals("SE", compassLabel(135))
        assertEquals("S", compassLabel(180))
        assertEquals("SW", compassLabel(225))
        assertEquals("W", compassLabel(270))
        assertEquals("NW", compassLabel(315))
        // sector edges: 337.5° is where NW hands over to N
        assertEquals("NW", compassLabel(337))
        assertEquals("N", compassLabel(338))
        assertNull(compassLabel(-1))
    }

    @Test fun `a plain wind says direction and speed, and nothing about gusts`() {
        assertEquals("NW 24 km/h", windLine(speedKmh = 24.3, gustKmh = 30.0, dirDeg = 315))
    }

    @Test fun `gusts appear only when they would surprise you`() {
        // the Wellington case: the gust figure earns its place when it stands well above the
        // sustained speed, and stays quiet when it is the same number said twice
        assertEquals(
            "NW 32 km/h, gusts 58",
            windLine(speedKmh = 32.0, gustKmh = 58.0, dirDeg = 315),
        )
        assertEquals(
            "NW 32 km/h",
            windLine(speedKmh = 32.0, gustKmh = 32.0 + GUST_MENTION_DELTA_KMH - 1, dirDeg = 315),
        )
    }

    @Test fun `an unknown direction is omitted, not guessed`() {
        assertEquals("12 km/h", windLine(speedKmh = 12.0, gustKmh = Double.NaN, dirDeg = -1))
    }

    @Test fun `no wind in the feed means no line at all`() {
        // NaN speed = the column was missing; a pane that says "— km/h" would be worse than silence
        assertNull(windLine(speedKmh = Double.NaN, gustKmh = 40.0, dirDeg = 90))
    }

    @Test fun `an hourly cell says its wind at the tightest — letter and number, no unit`() {
        assertEquals("NW 18", windCellLabel(windKmh = 18.2, dirDeg = 315))
        assertEquals("7", windCellLabel(windKmh = 7.0, dirDeg = -1))   // unknown direction: number alone
        assertNull(windCellLabel(windKmh = Double.NaN, dirDeg = 90))   // no hourly wind: no line
    }

    // ---- feels-like, sun, odds, UV (the v1.1 second pass) ------------------------------------

    @Test fun `feels-like speaks only when it would surprise you`() {
        assertEquals("feels -12°", feelsLine(nowC = -7.0, feelsC = -12.4, unit = TempUnit.CELSIUS))
        assertNull(feelsLine(nowC = 20.0, feelsC = 21.0, unit = TempUnit.CELSIUS))   // same number twice
        assertNull(feelsLine(nowC = 20.0, feelsC = Double.NaN, unit = TempUnit.CELSIUS))
    }

    @Test fun `the sun line says rise and set in the user's clock`() {
        // 05:12 and 21:48 UTC at offset 0
        assertEquals(
            "↑ 05:12   ↓ 21:48",
            sunLine(sunriseSec = 18_720L, sunsetSec = 78_480L, utcOffsetSeconds = 0, format = ClockFormat.H24),
        )
        assertNull(sunLine(sunriseSec = 0L, sunsetSec = 78_480L, utcOffsetSeconds = 0, format = ClockFormat.H24))
    }

    @Test fun `the week strip says a rain chance only when it is worth planning around`() {
        assertEquals("60%", weekProbLabel(60))
        assertNull(weekProbLabel(WEEK_PROB_THRESHOLD - 1))
        assertNull(weekProbLabel(-1))
    }

    @Test fun `uv is mentioned from moderate up`() {
        assertEquals("UV 6", uvLabel(5.7))
        assertNull(uvLabel(2.9))
        assertNull(uvLabel(Double.NaN))
    }

    @Test fun `the expanded day says what it knows and nothing it does not`() {
        val day = DayPoint(0L, 3, 18.0, 9.0, precipProbMaxPct = 60, uvMax = 5.7)
        assertEquals("rain 60% · UV 6", dayFactsLine(day))
        // The detail answers any known chance — opening the day is asking — but stays silent on
        // the unknown.
        assertEquals("rain 10%", dayFactsLine(day.copy(precipProbMaxPct = 10, uvMax = Double.NaN)))
        assertNull(dayFactsLine(DayPoint(0L, 3, 18.0, 9.0)))
    }

    // ---- the next hour at quarter grain ------------------------------------------------------

    private fun quarters(vararg mm: Double): List<QuarterPoint> =
        mm.mapIndexed { i, v -> QuarterPoint(1000L + (i + 1) * 900L, v) }

    @Test fun `dry now with rain coming names the minutes`() {
        // quarters at +15/+30/+45/+60 min; rain begins on the third
        assertEquals("rain in ~45 min", nextHourLine(quarters(0.0, 0.0, 0.8, 1.2), nowEpochSec = 1000L))
    }

    @Test fun `raining with an end in sight names the easing`() {
        // wet at +15/+30, dry from +45 on
        assertEquals("rain easing in ~45 min", nextHourLine(quarters(1.2, 0.8, 0.0, 0.0), nowEpochSec = 1000L))
    }

    @Test fun `a dry quarter inside a shower is not the rain ending`() {
        assertEquals("rain for the next hour", nextHourLine(quarters(1.2, 0.0, 0.8, 1.0), nowEpochSec = 1000L))
    }

    @Test fun `a calm dry hour says nothing at all`() {
        assertNull(nextHourLine(quarters(0.0, 0.0, 0.0, 0.0), nowEpochSec = 1000L))
        assertNull(nextHourLine(emptyList(), nowEpochSec = 1000L))
    }

    // ---- the expanded day's mini strip -------------------------------------------------------

    @Test fun `the day strip walks the waking hours at three-hour steps`() {
        val day = 86_400L * 100   // some local midnight at offset 0
        val hours = (0 until 24).map { HourPoint(day + it * 3600L, it.toDouble(), 1, 0) }
        val strip = dayStrip(hours, day, utcOffsetSeconds = 0)
        assertEquals(listOf(6.0, 9.0, 12.0, 15.0, 18.0, 21.0), strip.map { it.tempC })
    }

    @Test fun `the day strip never borrows a neighbouring day's hours`() {
        val day = 86_400L * 100
        val hours = listOf(
            HourPoint(day - 3 * 3600L, -1.0, 1, 0),        // yesterday evening
            HourPoint(day + 12 * 3600L, 12.0, 1, 0),       // this noon
            HourPoint(day + 86_400L + 12 * 3600L, 99.0, 1, 0),   // tomorrow noon
        )
        assertEquals(listOf(12.0), dayStrip(hours, day, 0).map { it.tempC })
    }

    // ---- the quarters' cache diet ------------------------------------------------------------

    @Test fun `buildForecast keeps only the near quarters`() {
        val now = 1_000_000L
        val f = buildForecast(
            place = Place("X", 0.0, 0.0),
            utcOffsetSeconds = 0,
            nowTimeSec = now,
            nowTempC = 10.0,
            nowCode = 1,
            hourlyTime = emptyList(), hourlyTempC = emptyList(),
            hourlyCode = emptyList(), hourlyPrecipProb = emptyList(),
            dailyTime = emptyList(), dailyCode = emptyList(),
            dailyMaxC = emptyList(), dailyMinC = emptyList(),
            fetchedAtMillis = 0L,
            quarterTime = listOf(now - 3600L, now + 900L, now + QUARTER_HORIZON_SEC, now + QUARTER_HORIZON_SEC + 900L),
            quarterPrecip = listOf(1.0, 2.0, 3.0, 4.0),
        )
        // An hour ago and past the horizon both fall away; the near ones survive.
        assertEquals(listOf(2.0, 3.0), f.quarters.map { it.precipMm })
    }
}
