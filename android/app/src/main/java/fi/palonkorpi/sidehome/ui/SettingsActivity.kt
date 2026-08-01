package fi.palonkorpi.sidehome.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import fi.palonkorpi.sidehome.ambient.accentColor
import fi.palonkorpi.sidehome.ambient.DEFAULT_LATITUDE
import fi.palonkorpi.sidehome.ambient.computeMood
import fi.palonkorpi.sidehome.ambient.foregroundInk
import fi.palonkorpi.sidehome.ambient.skyTone
import fi.palonkorpi.sidehome.notifications.NotificationBadges
import fi.palonkorpi.sidehome.settings.ClockFormat
import fi.palonkorpi.sidehome.settings.SettingsStore
import fi.palonkorpi.sidehome.settings.clockFormatAsState
import fi.palonkorpi.sidehome.settings.keyNavEnabledAsState
import fi.palonkorpi.sidehome.settings.notificationAccessStatus
import fi.palonkorpi.sidehome.weather.CityComposer
import fi.palonkorpi.sidehome.weather.WeatherSettingsSection
import fi.palonkorpi.sidehome.weather.rememberWeatherStore
import java.time.LocalDateTime

/**
 * The launcher's settings surface. There is deliberately no settings screen on the home
 * (SPEC.md §1 "no settings chrome"); this Activity is reached from the OS app-info
 * "Settings" deep-link (registered via APPLICATION_PREFERENCES in the manifest), and — once
 * §6 lands — from the zoom-out "customize your home" gear. It paints on the same ambient sky
 * as the home so it reads as part of the launcher, not a stock Android settings page.
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val settings = SettingsStore(this)
        setContent { SettingsScreen(settings) }
    }
}

@Composable
private fun SettingsScreen(settings: SettingsStore) {
    // A one-shot mood: the settings screen is transient, so we don't tick the clock here.
    val now = remember { LocalDateTime.now() }
    val hour = now.hour + now.minute / 60.0
    // Same location-cast sky as the home (below): the user's latitude when a weather place is set.
    val mood = remember(hour) {
        computeMood(hour, settings.weatherPlace?.lat ?: DEFAULT_LATITUDE, now.dayOfYear)
    }
    val fgDark = mood.l > 0.55
    val fg = foregroundInk(fgDark)
    val accent = accentColor(mood, fgDark)

    val clockFormat by settings.clockFormatAsState()
    val keyNavEnabled by settings.keyNavEnabledAsState()

    // SID-35's §4a location mirror. The composer *replaces* the settings column rather than floating
    // over it: this screen has no recede-by-blur machinery of its own, and a modal that just sat on
    // top of a live list would be the one overlay in the launcher that doesn't push its host back.
    var cityComposerOpen by remember { mutableStateOf(false) }
    val weatherStore = rememberWeatherStore()

    // This Activity is its own composition, outside the launcher root that sets these — so without
    // re-providing them the rows ripple (default indication) and render in the platform font instead
    // of the launcher's Inter. Match the home so Settings reads as part of the launcher.
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = Inter),
        LocalIndication provides NoIndication,
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .background(skyTone(mood)),
    ) {
        AmbientBackground(mood = mood, modifier = Modifier.fillMaxSize())

        if (cityComposerOpen) {
            CityComposer(
                settings = settings,
                store = weatherStore,
                fg = fg,
                accent = accent,
                onDismiss = { cityComposerOpen = false },
            )
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    // The rows (Clock, the notification-dots explainer, the weather section, About)
                    // add up past 640px, so without this the tail is simply unreachable on the SP-01.
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
            ) {
                Text(
                    "Settings",
                    color = fg,
                    fontWeight = FontWeight.Light,
                    fontSize = 30.sp,
                )
                Spacer(Modifier.height(28.dp))

                SettingRow(label = "Clock", fg = fg) {
                    Segmented(
                        options = listOf("12h" to ClockFormat.H12, "24h" to ClockFormat.H24),
                        selected = clockFormat,
                        fg = fg,
                        accent = accent,
                        onSelect = { settings.clockFormat = it },
                    )
                }

                Spacer(Modifier.height(28.dp))
                // SID-97: the one shipped key-nav knob. Off makes dispatchKeyEvent a full passthrough,
                // so the hardware tile drives whatever the user has bound it to (e.g. an external Key
                // Mapper app) instead of the launcher. The profile switcher (SID-89) is post-MVP.
                SettingRow(label = "Key navigation", fg = fg) {
                    Segmented(
                        options = listOf("Off" to false, "On" to true),
                        selected = keyNavEnabled,
                        fg = fg,
                        accent = accent,
                        onSelect = { settings.keyNavEnabled = it },
                    )
                }

                Spacer(Modifier.height(28.dp))
                NotificationDotsRow(fg = fg, accent = accent)

                Spacer(Modifier.height(28.dp))
                WeatherSettingsSection(
                    settings = settings,
                    fg = fg,
                    accent = accent,
                    onEditLocation = { cityComposerOpen = true },
                )

                Spacer(Modifier.height(28.dp))
                AboutSection(fg = fg)
            }
        }
    }
    }
}

@Composable
private fun SettingRow(label: String, fg: Color, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = fg, fontSize = 17.sp)
        control()
    }
}

/**
 * Notification dots (SPEC.md §8, SID-47). Deliberately **not** a [Segmented] or a switch: the row
 * has no app-side state to toggle. Notification access can only be granted on a system page, and a
 * `NotificationListenerService` listens whenever it is granted — so the system grant *is* the
 * switch, and tapping here just hands the user to the page that owns it. That keeps "Off" honest:
 * it means access revoked and nothing listening, not "granted, but promising not to look."
 *
 * This row is also the whole in-app footprint of the feature — no home nag, no first-run
 * interstitial (the fuller telling lives in `README.md` and the announcement draft), which is why
 * it carries an explainer where the Clock row carries only a label.
 */
@Composable
private fun NotificationDotsRow(fg: Color, accent: Color) {
    val context = LocalContext.current
    val granted by notificationAccessGrantedAsState()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable {
                // A de-Googled build could conceivably ship without the Settings page this
                // resolves to; a launcher that no-ops beats a launcher that crashes on a tap.
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            }
            .padding(vertical = 4.dp),
    ) {
        SettingRow(label = "Notification dots", fg = fg) {
            Text(
                notificationAccessStatus(granted),
                color = if (granted) accent else fg.copy(alpha = 0.7f),
                fontSize = 15.sp,
                fontWeight = if (granted) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "A dot on apps with a notification you haven't dealt with. Needs Android's " +
                "notification access — SideHome counts them on the phone and never stores " +
                "or sends anything.",
            color = fg.copy(alpha = 0.85f),
            fontSize = 13.sp,
            lineHeight = 17.sp,
        )
    }
}

/**
 * Whether notification access is granted right now, as Compose [State] that survives the round trip
 * to the system page.
 *
 * The grant is changed *outside* this process, on a screen we don't own, so there is nothing to
 * observe while we're away and no callback on the way back — a `remember { isAccessGranted() }`
 * would show the value from before the trip, forever. Re-reading on `ON_RESUME` is the honest fix:
 * that is exactly the moment the system page hands us back, and it needs no receiver (a system
 * broadcast would have to be registered `RECEIVER_EXPORTED` to arrive at all — the SID-34 trap).
 *
 * Cheap and idempotent by design: the read is a set lookup, and [mutableStateOf] only recomposes on
 * an actual change, so the spurious resumes this device is prone to (the launcher stays `RESUMED`
 * behind the keyguard) cost nothing and can't flicker the row. We seed the state as well as observe
 * it, since composition can happen before the first `ON_RESUME` is dispatched.
 */
@Composable
private fun notificationAccessGrantedAsState(): State<Boolean> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember(context) { mutableStateOf(NotificationBadges.isAccessGranted(context)) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.value = NotificationBadges.isAccessGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

/** A two-or-more-option pill selector: the chosen option sits on an accent cushion. */
@Composable
private fun <T> Segmented(
    options: List<Pair<String, T>>,
    selected: T,
    fg: Color,
    accent: Color,
    onSelect: (T) -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(fg.copy(alpha = 0.08f)),
    ) {
        options.forEach { (text, value) ->
            val isSelected = value == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) accent.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text,
                    color = fg,
                    fontSize = 15.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun AboutSection(fg: Color) {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
    }
    Column {
        Text("About", color = fg, fontWeight = FontWeight.Light, fontSize = 22.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            if (version.isBlank()) "SideHome" else "SideHome $version",
            color = fg,
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Open-source licenses",
            color = fg,
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Iconoir icon set — MIT License.",
            color = fg.copy(alpha = 0.85f),
            fontSize = 13.sp,
        )
    }
}
