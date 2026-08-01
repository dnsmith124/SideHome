package fi.palonkorpi.sidehome.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fi.palonkorpi.sidehome.settings.ClockFormat
import fi.palonkorpi.sidehome.settings.SettingsStore
import fi.palonkorpi.sidehome.settings.clockFormatAsState
import fi.palonkorpi.sidehome.settings.tempUnitAsState
import fi.palonkorpi.sidehome.settings.weatherPlaceAsState
import fi.palonkorpi.sidehome.ui.HomeMetrics
import fi.palonkorpi.sidehome.ui.Inter
import fi.palonkorpi.sidehome.ui.LineIcon
import java.util.TimeZone

/**
 * **The weather glance** (SPEC.md §4a, SID-35) — the right-hand page of the home. It ships **unnamed
 * and weather-only**: no "Future" title, no beta tag, no "coming soon" — Calendar/reminders are
 * parked, and until they land this page is simply the weather, so it says nothing it isn't.
 *
 * Calm tiers and not a widget: **Now** (a big temperature, the condition and today's high/low), **one
 * change-line** that is *omitted whenever there is nothing to say*, an **hourly strip** (the next six
 * hours), and a **week strip**. Wind, humidity, pressure, UV, sunrise and feels-like are all excluded
 * — at 480px the space to say them is the space that makes the rest readable.
 *
 * No text on this pane is dimmed. It is an ambient surface — content on the untouched sky — and
 * DECISIONS is explicit that those carry no muted copy: hierarchy here is size and weight, and the
 * only translucent thing is the week strip's rule.
 *
 * All the thinking is in `Weather.kt`, which is testable; this file is layout, which is not.
 */
@Composable
fun FuturePane(
    settings: SettingsStore,
    fg: Color,
    accent: Color,
    visible: Boolean,
    store: WeatherStore = rememberWeatherStore(),
    onComposerOpenChange: (Boolean) -> Unit = {},
    // Owned by the host: the gesture arbiter routes a vertical drag on this pane to it (the arbiter
    // owns every touch on the scene, so the pane can't detect its own drag — see enabled=false below).
    scrollState: ScrollState = rememberScrollState(),
    modifier: Modifier = Modifier,
) {
    val place by settings.weatherPlaceAsState()
    val unit by settings.tempUnitAsState()
    val clockFormat by settings.clockFormatAsState()
    val forecast by store.forecast.collectAsState()
    var composerOpen by remember { mutableStateOf(false) }

    // Tell the host the composer is up, so it can detach the pager the way it already does for the
    // drawer and the strip. A pane cannot reach `gesturesActive` itself, and without this a stray
    // horizontal drag pages Future away from under a field the user is typing into. The state stays
    // here — this reports it, it does not hand it over — and `onDispose(false)` is what keeps a host
    // from being told "open" for ever by a pane that went away while the composer was still up.
    DisposableEffect(composerOpen) {
        onComposerOpenChange(composerOpen)
        onDispose { if (composerOpen) onComposerOpenChange(false) }
    }

    // Fetch on view, and *only* on view (SPEC §4a): no WorkManager, no poll. [visible] is the pane
    // settling at Future, so this fires on arrival and again on the next arrival — which is exactly
    // when a stale cache matters and never while the phone is in a pocket. The store no-ops if the
    // cache is young enough, so most arrivals cost nothing.
    LaunchedEffect(visible, place) {
        if (visible) store.refreshIfNeeded(place)
    }

    // Read once per arrival rather than ticking: the only thing "now" decides here is whether the
    // forecast is old enough to admit its age, and a clock that re-runs this every minute would buy
    // a recomposition of the pane to change nothing. The change-line uses the *feed's* own clock
    // (Forecast.nowTimeSec), so it needs no device time at all.
    val nowMillis = remember(forecast, visible) { System.currentTimeMillis() }

    // The cache may still hold the city the user just replaced. Render it under the new name and the
    // pane is simply lying; hold the tiers back for the one fetch instead.
    val shown = forecast.takeIf { it.matches(place) }

    // The composer *replaces* the pane rather than floating over it. DECISIONS forbids an overlay
    // that dims or tints the sky, and the recede-by-blur that real overlays get is HomeScreen's
    // machinery, which a pane cannot reach into — so an unbacked sheet here would be two layers of
    // text on top of each other. Swapping the content is the same rule honoured with what a pane has.
    if (composerOpen) {
        CityComposer(
            settings = settings,
            store = store,
            fg = fg,
            accent = accent,
            onDismiss = { composerOpen = false },
            modifier = modifier,
        )
        return
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // Centred when the forecast is short, scrolling when it isn't. The tiers (now, hourly, week)
        // can add up past the 640px screen, so a fixed centred Box would clip a tall day; but a bare
        // verticalScroll hands the column an unbounded height, which Arrangement.Center has nothing to
        // centre within. BoxWithConstraints gives the real viewport height, so we floor the column at
        // it (heightIn min = maxHeight): a short forecast centres within one screenful, a tall one
        // grows past it and scrolls. The frame padding stays as the minimum inset for the scroll case.
        //
        // enabled = false: this measures/offsets as a scroll container but does NOT claim the drag —
        // the arbiter owns every touch on the scene and drives this via dispatchRawDelta, so a live
        // scrollable here would double-handle the same gesture against it.
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState, enabled = false)
                .heightIn(min = maxHeight)
                .padding(start = 32.dp, end = 32.dp, top = 40.dp, bottom = 44.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            if (place == null) {
                Text(
                    "Set your location",
                    color = accent,
                    fontFamily = Inter,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { composerOpen = true }
                        .padding(vertical = 6.dp, horizontal = 2.dp),
                )
            } else {
                PlaceLine(name = place?.name.orEmpty(), fg = fg, onEdit = { composerOpen = true })
                if (shown != null) {
                    Spacer(Modifier.height(10.dp))
                    NowLine(forecast = shown, unit = unit, fg = fg)
                    ChangeLine(forecast = shown, format = clockFormat, fg = fg)
                    Spacer(Modifier.height(20.dp))
                    HourlyStrip(forecast = shown, unit = unit, format = clockFormat, fg = fg)
                    Spacer(Modifier.height(16.dp))
                    WeekStrip(days = shown.days, offsetSec = shown.utcOffsetSeconds, unit = unit, fg = fg)
                    AsOfLine(forecast = shown, nowMillis = nowMillis, format = clockFormat, fg = fg)
                }
            }
        }
    }
}

/**
 * The place, and the first of SPEC §4a's two ways to change it: the name in the forecast is the
 * button. The pin glyph is there to say so — without it a line of text that happens to be tappable
 * is a secret.
 */
@Composable
private fun PlaceLine(name: String, fg: Color, onEdit: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onEdit)
            .padding(vertical = 4.dp, horizontal = 2.dp),
    ) {
        LineIcon(glyph = "pin", color = fg, size = 14.dp, strokeWidthUnits = 1.7f)
        Spacer(Modifier.width(7.dp))
        Text(name, color = fg, fontFamily = Inter, fontSize = 14.sp, fontWeight = FontWeight.Normal)
    }
}

/**
 * Tier 1 — Now. The temperature is the pane's clock: one big Light number, same idiom as the home.
 * Beside it, bottom-aligned, the condition (its glyph tinted accent when wet, the launcher's "worth
 * noticing" idiom) and today's own high/low — the one number pair you actually plan a day around,
 * promoted out of the old Today/Tomorrow table so the eye gets it without reading a second tier.
 */
@Composable
private fun NowLine(forecast: Forecast, unit: TempUnit, fg: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        // The temperature IS the pane's clock — the home's own 78sp Light, tight-tracked idiom
        // (HomeMetrics.ClockSp), so Future opens with a real hero and reads with the home's
        // hierarchy instead of as a flat stack of similar-sized rows.
        Text(
            formatTemp(forecast.nowTempC, unit),
            color = fg,
            fontFamily = Inter,
            fontSize = HomeMetrics.ClockSp.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = (-0.03).em,
        )
        // Condition + today's H/L, centred in the space to the right of the hero so it sits with
        // equal air on both sides — detached from the number, balanced against the right padding —
        // rather than crammed against the degree with dead space to its right.
        Box(Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.padding(bottom = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LineIcon(
                        glyph = conditionGlyph(forecast.nowCode),
                        color = fg,
                        size = 20.dp,
                        strokeWidthUnits = 1.7f,
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        conditionWord(forecast.nowCode),
                        color = fg,
                        fontFamily = Inter,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Normal,
                    )
                }
                forecast.today?.let { day ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "H ${formatTemp(day.highC, unit)}   L ${formatTemp(day.lowC, unit)}",
                        color = fg,
                        fontFamily = Inter,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Light,
                    )
                }
            }
        }
    }
}

/**
 * Tier 2b — the next six hours: label, glyph, temperature, evenly spread. This is the detail the old
 * Today/Tomorrow table couldn't give — what the afternoon actually does — using the same hourly data
 * the change-line already reads. No per-hour precip %: at this size it would turn a glance into a
 * read, which is the line this pane holds (SPEC §4a). A wet hour reads by its glyph's shape (the rain
 * silhouette), not by a colour.
 */
@Composable
private fun HourlyStrip(
    forecast: Forecast,
    unit: TempUnit,
    format: ClockFormat,
    fg: Color,
) {
    val hours = remember(forecast) { upcomingHours(forecast.hours, forecast.nowTimeSec, 6) }
    if (hours.isEmpty()) return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        hours.forEach { h ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    shortHourLabel(h.epochSec, forecast.utcOffsetSeconds, format),
                    color = fg,
                    fontFamily = Inter,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Light,
                )
                Spacer(Modifier.height(6.dp))
                LineIcon(
                    glyph = conditionGlyph(h.code),
                    color = fg,
                    size = 16.dp,
                    strokeWidthUnits = 1.6f,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    formatTemp(h.tempC, unit),
                    color = fg,
                    fontFamily = Inter,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * Tier 2 — the one smart change-line, and **nothing at all** when [changeLine] has nothing to say.
 * No placeholder, no reserved height: the composable emits zero nodes, so a settled day closes the
 * gap rather than showing a hole where news would go.
 */
@Composable
private fun ChangeLine(forecast: Forecast, format: ClockFormat, fg: Color) {
    val line = remember(forecast, format) {
        changeLine(
            nowEpochSec = forecast.nowTimeSec,
            nowCode = forecast.nowCode,
            hours = forecast.hours,
            utcOffsetSeconds = forecast.utcOffsetSeconds,
            format = format,
        )
    } ?: return
    Spacer(Modifier.height(4.dp))
    Text(line, color = fg, fontFamily = Inter, fontSize = 16.sp, fontWeight = FontWeight.Normal)
}

/**
 * Tier 3 — the week. Seven cells, each a day, a glyph, a high and a lighter low; no percentages,
 * nothing that turns a glance into a read. The low is a size-and-weight step down, never a dimmed
 * colour (DECISIONS forbids muted text here). Each day's condition reads by its glyph's shape — the
 * rain and storm silhouettes are their own — so no cell is tinted.
 */
@Composable
private fun WeekStrip(
    days: List<DayPoint>,
    offsetSec: Int,
    unit: TempUnit,
    fg: Color,
) {
    val week = days.take(7)
    if (week.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(fg.copy(alpha = 0.14f)),
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            week.forEach { day ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        weekdayLabel(day.epochSec, offsetSec),
                        color = fg,
                        fontFamily = Inter,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Light,
                    )
                    Spacer(Modifier.height(5.dp))
                    LineIcon(
                        glyph = conditionGlyph(day.code),
                        color = fg,
                        size = 18.dp,
                        strokeWidthUnits = 1.6f,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        formatTemp(day.highC, unit),
                        color = fg,
                        fontFamily = Inter,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        formatTemp(day.lowC, unit),
                        color = fg,
                        fontFamily = Inter,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Light,
                    )
                }
            }
        }
    }
}

/**
 * The offline tell (SPEC §4a): a quiet "as of HH:MM" when the forecast on screen is genuinely old,
 * and **never** an error. Stamped in the *phone's* zone, not the place's — this line is about when
 * you last had a signal, which happened where you are.
 */
@Composable
private fun AsOfLine(forecast: Forecast, nowMillis: Long, format: ClockFormat, fg: Color) {
    if (!showAsOf(forecast.fetchedAtMillis, nowMillis)) return
    val text = remember(forecast.fetchedAtMillis, format) {
        val offsetSec = TimeZone.getDefault().getOffset(forecast.fetchedAtMillis) / 1000
        val sec = forecast.fetchedAtMillis / 1000
        asOfText(localHourOfDay(sec, offsetSec), localMinute(sec, offsetSec), format)
    }
    Spacer(Modifier.height(14.dp))
    Text(text, color = fg, fontFamily = Inter, fontSize = 12.sp, fontWeight = FontWeight.Light)
}
