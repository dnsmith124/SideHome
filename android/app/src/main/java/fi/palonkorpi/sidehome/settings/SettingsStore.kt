package fi.palonkorpi.sidehome.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import fi.palonkorpi.sidehome.ambient.DEFAULT_CUSTOM_DRIFT
import fi.palonkorpi.sidehome.ambient.DEFAULT_CUSTOM_HUE_DEG
import fi.palonkorpi.sidehome.ambient.DEFAULT_CUSTOM_SAT
import fi.palonkorpi.sidehome.ambient.Theme
import fi.palonkorpi.sidehome.weather.Place
import fi.palonkorpi.sidehome.weather.TempUnit

/**
 * The launcher's few genuinely-real settings, persisted in `SharedPreferences`.
 *
 * There is no settings *screen* on the home (see SPEC.md §1 "no settings chrome"):
 * the home (`MainActivity`) only reads this, and the settings screen
 * (`SettingsActivity`) writes it. Both run in the same process, so a change made in
 * the settings screen reaches the live home via the shared-prefs change listener.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Clock format. Defaults to 24h. */
    var clockFormat: ClockFormat
        get() = if (prefs.getBoolean(KEY_CLOCK_24H, true)) ClockFormat.H24 else ClockFormat.H12
        set(value) = prefs.edit().putBoolean(KEY_CLOCK_24H, value == ClockFormat.H24).apply()

    /**
     * Home theme (SPEC.md §6). Persisted by enum *name* so reordering the enum can't silently
     * repoint a saved choice. Defaults to [Theme.AMBIENT]; an unknown/absent value falls back to it.
     */
    var theme: Theme
        get() = themeFromPref(prefs.getString(KEY_THEME, null))
        set(value) = prefs.edit().putString(KEY_THEME, value.toPref()).apply()

    /**
     * The Custom theme's seed hue in degrees (0–360) — the seed-hue variant of the §6 themes
     * (SID-15). Stored as a plain float; the theme engine turns it into an OKLCH hue and derives
     * lightness / ink / accent from it, so this is only ever a *hue* choice, never a free colour.
     * Defaults to [DEFAULT_CUSTOM_HUE_DEG].
     */
    var customHue: Float
        get() = prefs.getFloat(KEY_CUSTOM_HUE, DEFAULT_CUSTOM_HUE_DEG)
        set(value) = prefs.edit().putFloat(KEY_CUSTOM_HUE, value).apply()

    /**
     * The Custom theme's saturation (SID-15) — a multiplier on the base chroma, [DEFAULT_CUSTOM_SAT]
     * by default. Paired with [customHue]; see the theme engine's Custom case.
     */
    var customSat: Float
        get() = prefs.getFloat(KEY_CUSTOM_SAT, DEFAULT_CUSTOM_SAT)
        set(value) = prefs.edit().putFloat(KEY_CUSTOM_SAT, value).apply()

    /**
     * Whether the Custom colour follows the time of day (SID-177). Off by default — Custom is a fixed
     * colour, Ambient is the theme that tracks the sun. When on, the chosen colour is dimmed by the
     * day cycle; see the theme engine's Custom case.
     */
    var customDrift: Boolean
        get() = prefs.getBoolean(KEY_CUSTOM_DRIFT, DEFAULT_CUSTOM_DRIFT)
        set(value) = prefs.edit().putBoolean(KEY_CUSTOM_DRIFT, value).apply()

    /**
     * Drawer browse mode (SID-23), stored by `BrowseMode` enum *name*. Kept as a raw string so the
     * settings layer needn't depend on the `ui` package where `BrowseMode` lives; the ui side resolves
     * the name and falls back to List. Null until the user has switched away from the default.
     */
    var browseModeName: String?
        get() = prefs.getString(KEY_BROWSE_MODE, null)
        set(value) = prefs.edit().putString(KEY_BROWSE_MODE, value).apply()

    /**
     * Whether the first-run gesture coach has been shown (SPEC.md §7, SID-27). Write-once, and
     * one-way on purpose: the coach is a single quiet pass on a fresh install, so nothing in the
     * launcher ever sets this back to false. Clearing app data is the only way back, which is the
     * same thing a fresh install means.
     *
     * Defaults to false, which is what makes a fresh install show it: an install with no prefs file
     * is indistinguishable from one that has never coached, and both want the coach.
     */
    var coachSeen: Boolean
        get() = prefs.getBoolean(KEY_COACH_SEEN, false)
        set(value) = prefs.edit().putBoolean(KEY_COACH_SEEN, value).apply()

    /**
     * Where the weather comes from (SPEC.md §4a, SID-35). Null until the user has typed one — there
     * is no default city, because a launcher that opens on the weather in Berlin has told the user
     * something false, and "Set your location" has told them something true.
     *
     * **Typed, never sensed.** No location permission is requested and none is wanted: on a
     * de-Googled phone `NETWORK_PROVIDER` is Google's and simply isn't there, so the only automatic
     * option left is GPS — slow to fix, expensive to hold, and pointless for a city-scale forecast.
     * Coordinates come from geocoding a name the user chose. This app's only permission is INTERNET.
     */
    var weatherPlace: Place?
        get() = placeFromPref(prefs.getString(KEY_WEATHER_PLACE, null))
        set(value) = prefs.edit().putString(KEY_WEATHER_PLACE, value?.toPref()).apply()

    /**
     * Temperature units (SPEC.md §4a). Defaults to Celsius.
     *
     * SPEC says this "reuses §1's existing temperature-units setting" — there was no such setting;
     * §1 shipped a clock format and a theme and nothing else. This is it, and it is a *display*
     * preference only: the forecast is fetched and cached in Celsius whatever this says, so
     * flipping it re-renders and never refetches.
     */
    var tempUnit: TempUnit
        get() = tempUnitFromPref(prefs.getString(KEY_TEMP_UNIT, null))
        set(value) = prefs.edit().putString(KEY_TEMP_UNIT, value.toPref()).apply()

    /**
     * Hardware key-navigation on/off (SID-97). The **one** shipped key-nav setting: everything else
     * (per-view search auto-focus, the number→D-pad map) is a strong default, not a knob. Defaults to
     * **on** — the SP-01 is a hardware-keypad phone, so it navigates by key out of the box.
     *
     * OFF must be a true no-op, not a swallow: `MainActivity.dispatchKeyEvent` passes every key through
     * untouched (`decideKeyDispatch` returns PassThrough when this is false), so the launcher never
     * fights an external Key Mapper app the user has bound the tile to.
     */
    var keyNavEnabled: Boolean
        get() = prefs.getBoolean(KEY_KEYNAV_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_KEYNAV_ENABLED, value).apply()

    /**
     * Register [onChange] for any settings change; returns a handle whose [AutoCloseable.close]
     * unregisters it. Fires on the thread the write happens on.
     */
    fun addChangeListener(onChange: () -> Unit): AutoCloseable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private companion object {
        const val PREFS = "sidehome_settings"
        const val KEY_CLOCK_24H = "clock_24h"
        const val KEY_THEME = "theme"
        const val KEY_CUSTOM_HUE = "custom_hue"
        const val KEY_CUSTOM_SAT = "custom_sat"
        const val KEY_CUSTOM_DRIFT = "custom_drift"
        const val KEY_BROWSE_MODE = "browse_mode"
        const val KEY_COACH_SEEN = "coach_seen"
        const val KEY_WEATHER_PLACE = "weather_place"
        const val KEY_TEMP_UNIT = "temp_unit"
        const val KEY_KEYNAV_ENABLED = "keynav_enabled"
    }
}

/**
 * Decode a persisted place — pure, like [themeFromPref], and for the same reason: the round-trip is
 * the part that can be wrong, and it is the part a test can reach.
 *
 * The encoding is `lat;lon;name`, coords first so that the name — the one field that can contain
 * anything a geocoder returns, semicolons included — is whatever is left after the second separator.
 * Anything unparseable reads as "no location set", which is a state the pane already has to render
 * beautifully (SPEC §4a's "Set your location"), so a corrupt pref lands somewhere safe rather than
 * throwing inside the launcher.
 */
fun placeFromPref(raw: String?): Place? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.split(";", limit = 3)
    if (parts.size < 3) return null
    val lat = parts[0].toDoubleOrNull() ?: return null
    val lon = parts[1].toDoubleOrNull() ?: return null
    val name = parts[2]
    if (name.isBlank() || !lat.isFinite() || !lon.isFinite()) return null
    return Place(name = name, lat = lat, lon = lon)
}

/** Encode a place for persistence. `Double.toString` is locale-independent; `String.format` isn't. */
fun Place.toPref(): String = "$lat;$lon;$name"

/** Decode a persisted temperature unit; by enum name, defaulting to Celsius. See [themeFromPref]. */
fun tempUnitFromPref(raw: String?): TempUnit =
    raw?.let { name -> TempUnit.entries.firstOrNull { it.name == name } } ?: TempUnit.CELSIUS

/** Encode a temperature unit for persistence (stable enum name, not ordinal). */
fun TempUnit.toPref(): String = name

/**
 * Decode a persisted theme value — pure, so the default/round-trip contract is unit-testable
 * without an Android [SharedPreferences]. `null`, an empty string, or a name from a future build
 * all fall back to the default [Theme.AMBIENT].
 */
fun themeFromPref(raw: String?): Theme =
    raw?.let { name -> Theme.entries.firstOrNull { it.name == name } } ?: Theme.AMBIENT

/** Encode a theme for persistence (stored by stable enum name, not ordinal). */
fun Theme.toPref(): String = name

/**
 * Observe [SettingsStore.clockFormat] as Compose [State]: re-reads on every settings
 * change (including writes made in another activity in this process) and recomposes.
 */
@Composable
fun SettingsStore.clockFormatAsState(): State<ClockFormat> {
    val state = remember(this) { mutableStateOf(clockFormat) }
    DisposableEffect(this) {
        state.value = clockFormat
        val handle = addChangeListener { state.value = clockFormat }
        onDispose { handle.close() }
    }
    return state
}

/**
 * Observe [SettingsStore.theme] as Compose [State]: re-reads on every settings change (including a
 * write made by the zoom-out chooser in this process) and recomposes, exactly like
 * [clockFormatAsState].
 */
@Composable
fun SettingsStore.themeAsState(): State<Theme> {
    val state = remember(this) { mutableStateOf(theme) }
    DisposableEffect(this) {
        state.value = theme
        val handle = addChangeListener { state.value = theme }
        onDispose { handle.close() }
    }
    return state
}

/**
 * Observe [SettingsStore.customHue] as Compose [State] (SID-15). The zoom-out chooser writes it live
 * while the home reads it — exactly like [themeAsState] — so dragging the hue re-tints the home now.
 */
@Composable
fun SettingsStore.customHueAsState(): State<Float> {
    val state = remember(this) { mutableStateOf(customHue) }
    DisposableEffect(this) {
        state.value = customHue
        val handle = addChangeListener { state.value = customHue }
        onDispose { handle.close() }
    }
    return state
}

/** Observe [SettingsStore.customSat] as Compose [State] (SID-15), like [customHueAsState]. */
@Composable
fun SettingsStore.customSatAsState(): State<Float> {
    val state = remember(this) { mutableStateOf(customSat) }
    DisposableEffect(this) {
        state.value = customSat
        val handle = addChangeListener { state.value = customSat }
        onDispose { handle.close() }
    }
    return state
}

/** Observe [SettingsStore.customDrift] as Compose [State] (SID-177), like [customHueAsState]. */
@Composable
fun SettingsStore.customDriftAsState(): State<Boolean> {
    val state = remember(this) { mutableStateOf(customDrift) }
    DisposableEffect(this) {
        state.value = customDrift
        val handle = addChangeListener { state.value = customDrift }
        onDispose { handle.close() }
    }
    return state
}

/**
 * Observe [SettingsStore.weatherPlace] as Compose [State], exactly like [themeAsState].
 *
 * This one has to observe rather than just read, and both writers are why: the place is editable
 * from the forecast itself *and* from the settings screen (SPEC §4a: "editable two ways"), the
 * second of which is a different Activity in this process. The shared-prefs listener is what makes
 * changing your city in Settings and swiping straight to Future show the new city.
 */
@Composable
fun SettingsStore.weatherPlaceAsState(): State<Place?> {
    val state = remember(this) { mutableStateOf(weatherPlace) }
    DisposableEffect(this) {
        state.value = weatherPlace
        val handle = addChangeListener { state.value = weatherPlace }
        onDispose { handle.close() }
    }
    return state
}

/** Observe [SettingsStore.tempUnit] as Compose [State]. See [clockFormatAsState]. */
@Composable
fun SettingsStore.tempUnitAsState(): State<TempUnit> {
    val state = remember(this) { mutableStateOf(tempUnit) }
    DisposableEffect(this) {
        state.value = tempUnit
        val handle = addChangeListener { state.value = tempUnit }
        onDispose { handle.close() }
    }
    return state
}

/**
 * Observe [SettingsStore.keyNavEnabled] as Compose [State] (SID-97). Flipping it in the settings
 * screen reaches the live home the same way theme does — the shared-prefs listener — so key-nav turns
 * on or off without a relaunch.
 */
@Composable
fun SettingsStore.keyNavEnabledAsState(): State<Boolean> {
    val state = remember(this) { mutableStateOf(keyNavEnabled) }
    DisposableEffect(this) {
        state.value = keyNavEnabled
        val handle = addChangeListener { state.value = keyNavEnabled }
        onDispose { handle.close() }
    }
    return state
}
