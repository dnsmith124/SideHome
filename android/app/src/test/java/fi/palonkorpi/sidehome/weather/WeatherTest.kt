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
}
