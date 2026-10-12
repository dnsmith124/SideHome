package fi.palonkorpi.sidehome.pane

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import fi.palonkorpi.sidehome.settings.SettingsStore
import fi.palonkorpi.sidehome.settings.clockFormatAsState
import fi.palonkorpi.sidehome.settings.weatherEnabledAsState
import fi.palonkorpi.sidehome.settings.weatherPlaceAsState
import fi.palonkorpi.sidehome.ui.Inter
import fi.palonkorpi.sidehome.weather.matches
import fi.palonkorpi.sidehome.weather.rememberWeatherStore
import fi.palonkorpi.sidehome.weather.sunLine
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * **The Now page** — the left-hand page when the user picks it. One big word for the hour, and a
 * few short lines about the present: how much light is left and when the sun turns (from the
 * weather feed, when there is one), the battery, and the next alarm. Everything here is read off
 * the phone itself with no permission: the battery is a sticky broadcast, the alarm is public to
 * every app, the sun times ride on the forecast the weather page already fetched.
 *
 * It holds the restraint rule the weather page set: a line is **absent unless it would tell you
 * something** — no "No alarm", no sun line without a forecast. Nothing on it is dimmed.
 */
@Composable
fun NowPane(
    settings: SettingsStore,
    fg: Color,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(start = 32.dp, end = 32.dp, top = 40.dp, bottom = 44.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        NowBlock(settings = settings, fg = fg, visible = visible)
    }
}

/**
 * The Now page's content without its page: the hour's word and the lines. Shared by [NowPane] and
 * the combined page, which stacks it over the notes.
 */
@Composable
fun NowBlock(settings: SettingsStore, fg: Color, visible: Boolean) {
    val context = LocalContext.current
    val clockFormat by settings.clockFormatAsState()
    val weatherEnabled by settings.weatherEnabledAsState()
    val place by settings.weatherPlaceAsState()
    val store = rememberWeatherStore()
    val forecast by store.forecast.collectAsState()

    // Re-read on each arrival, then once a minute while the page is on screen: the lines move on
    // the scale of minutes, and a page that is a swipe away costs nothing while it is not shown.
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var battery by remember { mutableStateOf<String?>(null) }
    var alarm by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            nowMillis = System.currentTimeMillis()
            battery = readBattery(context)
            alarm = readAlarm(context, nowMillis, clockFormat)
            delay(60_000L)
        }
    }

    val local = remember(nowMillis) { ZonedDateTime.now(ZoneId.systemDefault()) }
    val sun = forecast.takeIf { weatherEnabled && it.matches(place) }
    val today = sun?.days?.firstOrNull()
    val tomorrow = sun?.days?.getOrNull(1)
    val daylight = today?.let {
        daylightLine(nowMillis / 1000L, it.sunriseSec, it.sunsetSec, tomorrow?.sunriseSec ?: 0L)
    }
    val sunTimes = if (sun != null && today != null) {
        sunLine(today.sunriseSec, today.sunsetSec, sun.utcOffsetSeconds, clockFormat)
    } else null

    Text(
        dayPhase(local.hour),
        color = fg,
        fontFamily = Inter,
        fontSize = 44.sp,
        fontWeight = FontWeight.Light,
    )
    Spacer(Modifier.height(18.dp))
    listOfNotNull(daylight, sunTimes, battery, alarm).forEach { line ->
        Text(line, color = fg, fontFamily = Inter, fontSize = 19.sp)
        Spacer(Modifier.height(10.dp))
    }
}

private fun readBattery(context: Context): String? {
    val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val state = status.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
    val charging = state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL
    return batteryLine(pct, charging)
}

private fun readAlarm(context: Context, nowMillis: Long, format: fi.palonkorpi.sidehome.settings.ClockFormat): String? {
    val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return null
    val next = runCatching { am.nextAlarmClock?.triggerTime }.getOrNull()
    return alarmLine(next, nowMillis, ZoneId.systemDefault(), format)
}
