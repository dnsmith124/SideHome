package fi.palonkorpi.sidehome.pane

import fi.palonkorpi.sidehome.settings.ClockFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

class LeftPaneTest {

    @Test fun `the pref decodes by name and falls back to Now`() {
        assertEquals(LeftPane.NOTES, leftPaneFromPref("NOTES"))
        assertEquals(LeftPane.OFF, leftPaneFromPref("OFF"))
        assertEquals(LeftPane.BOTH, leftPaneFromPref("BOTH"))
        assertEquals(LeftPane.NOW, leftPaneFromPref(null))
        assertEquals(LeftPane.NOW, leftPaneFromPref("garbage"))
        assertEquals("NOTES", LeftPane.NOTES.toPref())
    }

    @Test fun `every hour has a phase, and the day reads in order`() {
        assertEquals("Night", dayPhase(2))
        assertEquals("Early morning", dayPhase(6))
        assertEquals("Morning", dayPhase(10))
        assertEquals("Midday", dayPhase(12))
        assertEquals("Afternoon", dayPhase(15))
        assertEquals("Evening", dayPhase(19))
        assertEquals("Night", dayPhase(23))
        assertEquals("Night", dayPhase(-1))   // wrapped, never thrown
    }

    @Test fun `daylight counts down to sunset, then to the next sunrise`() {
        val rise = 6 * 3600L; val set = 18 * 3600L; val nextRise = 30 * 3600L
        assertEquals("Sunrise in 1h 30m", daylightLine(nowSec = rise - 5400, sunriseSec = rise, sunsetSec = set))
        assertEquals("2h 15m of daylight left", daylightLine(nowSec = set - 8100, sunriseSec = rise, sunsetSec = set))
        assertEquals("45m of daylight left", daylightLine(nowSec = set - 2700, sunriseSec = rise, sunsetSec = set))
        assertEquals("Sunrise in 10h 0m", daylightLine(nowSec = set + 7200, sunriseSec = rise, sunsetSec = set, nextSunriseSec = nextRise))
        assertEquals("The sun is down", daylightLine(nowSec = set + 7200, sunriseSec = rise, sunsetSec = set))
    }

    @Test fun `no sun times means no daylight line, never a guess`() {
        assertNull(daylightLine(nowSec = 1000L, sunriseSec = 0L, sunsetSec = 0L))
    }

    @Test fun `the battery line says charging only while it is`() {
        assertEquals("83% battery", batteryLine(83, charging = false))
        assertEquals("83% · charging", batteryLine(83, charging = true))
        assertNull(batteryLine(-1, charging = false))
    }

    @Test fun `the alarm line is absent with no alarm, bare within a day, dated beyond`() {
        val now = 1_700_000_000_000L   // 2023-11-14 22:13 UTC, a Tuesday
        assertNull(alarmLine(null, now, ZoneOffset.UTC, ClockFormat.H24))
        assertNull(alarmLine(now - 1, now, ZoneOffset.UTC, ClockFormat.H24))
        val soon = now + 8 * 3600_000L   // Wednesday 06:13 UTC
        assertEquals("Alarm 06:13", alarmLine(soon, now, ZoneOffset.UTC, ClockFormat.H24))
        assertEquals("Alarm 6:13 am", alarmLine(soon, now, ZoneOffset.UTC, ClockFormat.H12))
        val later = now + 3 * 24 * 3600_000L   // Friday, same time
        val line = alarmLine(later, now, ZoneOffset.UTC, ClockFormat.H24)!!
        assert(line.startsWith("Alarm ") && line.endsWith(" 22:13") && line.length > "Alarm 22:13".length) { line }
    }
}
