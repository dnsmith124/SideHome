package fi.palonkorpi.sidehome.pane

import fi.palonkorpi.sidehome.settings.ClockFormat
import fi.palonkorpi.sidehome.settings.formatClock
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * What the left-hand page holds (Pixel Edition). Upstream shipped it disabled; here the user picks:
 *  - [NOW] — a quiet page about the present moment: the light, the battery, the next alarm.
 *  - [NOTES] — a short list you keep: a line of journal, a reminder to tick off.
 *  - [OFF] — Home is the left end, exactly as upstream.
 */
enum class LeftPane { OFF, NOW, NOTES }

/** Decode a persisted value; unknown or absent falls back to [LeftPane.NOW]. */
fun leftPaneFromPref(raw: String?): LeftPane =
    raw?.let { name -> LeftPane.entries.firstOrNull { it.name == name } } ?: LeftPane.NOW

/** Encode for persistence (stable enum name, not ordinal). */
fun LeftPane.toPref(): String = name

// ---- the Now page's lines: pure, so what the page says is unit-testable --------------------

/** The hour's name, the one big word on the Now page. */
fun dayPhase(hour: Int): String = when (((hour % 24) + 24) % 24) {
    in 0..4 -> "Night"
    in 5..8 -> "Early morning"
    in 9..11 -> "Morning"
    12 -> "Midday"
    in 13..16 -> "Afternoon"
    in 17..20 -> "Evening"
    else -> "Night"
}

/**
 * How much of the day's light is left, from today's sun times (and tomorrow's sunrise once the sun
 * is down). Null when the feed carried no sun times — the line is absent, never a guess.
 */
fun daylightLine(nowSec: Long, sunriseSec: Long, sunsetSec: Long, nextSunriseSec: Long = 0L): String? {
    if (sunriseSec <= 0L || sunsetSec <= 0L) return null
    return when {
        nowSec < sunriseSec -> "Sunrise in ${span(sunriseSec - nowSec)}"
        nowSec < sunsetSec -> "${span(sunsetSec - nowSec)} of daylight left"
        nextSunriseSec > nowSec -> "Sunrise in ${span(nextSunriseSec - nowSec)}"
        else -> "The sun is down"
    }
}

private fun span(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/** "83% · charging" / "83% battery", or null when the level is unknown. */
fun batteryLine(pct: Int, charging: Boolean): String? = when {
    pct < 0 -> null
    charging -> "$pct% · charging"
    else -> "$pct% battery"
}

/**
 * The next alarm as the page says it — "Alarm 06:30" within the next day, "Alarm Tue 06:30" beyond
 * — or null when none is set. Absent, not "No alarm": the rule is that a line is there only when it
 * would tell you something.
 */
fun alarmLine(triggerMillis: Long?, nowMillis: Long, zone: ZoneId, format: ClockFormat): String? {
    if (triggerMillis == null || triggerMillis <= nowMillis) return null
    val at = Instant.ofEpochMilli(triggerMillis).atZone(zone)
    val time = formatClock(at.hour, at.minute, format)
    val withinDay = triggerMillis - nowMillis < 24L * 3600L * 1000L
    return if (withinDay) "Alarm $time" else "Alarm ${at.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} $time"
}
