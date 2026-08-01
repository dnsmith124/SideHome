package fi.palonkorpi.sidehome.weather

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * The forecast the Future pane shows, and the only thing that ever fetches one (SID-35, SPEC §4a).
 *
 * The refresh model is the spec's, and all of it is "no": no `WorkManager`, no periodic poll, no
 * background job, no fetch the user didn't cause. The pane calls [refreshIfNeeded] when it comes
 * into view; if the cache is under [CACHE_MAX_AGE_MS] old and for the right place, that call does
 * nothing at all. The last-known forecast is on screen before the request is made and stays there if
 * the request fails, so there is never a spinner, never an error, and never an empty pane where
 * yesterday's weather would have done.
 *
 * **Its own prefs file**, not `SettingsStore`'s, and that is not tidiness: `SettingsStore` fans every
 * write out to a change listener that the live home is subscribed to. Writing a forecast blob into
 * that file would re-read the clock format and the theme, and recompose the launcher, every twenty
 * minutes — for a string the home does not read.
 */
class WeatherStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _forecast = MutableStateFlow(decodeForecast(prefs.getString(KEY_CACHE, null)))

    /**
     * The last-known forecast — seeded from disk at construction, so the very first composition of
     * the pane already has something to draw. It may be for a *different* place than the one now
     * chosen (the user just changed cities); [Forecast.matches] is how the pane refuses to render it
     * under the wrong name, and [needsRefresh] is how it gets replaced.
     */
    val forecast: StateFlow<Forecast?> = _forecast.asStateFlow()

    /** One fetch at a time. A second caller doesn't queue — it returns; the first is fetching. */
    private val fetching = Mutex()

    /**
     * Fetch, but only if there's a reason to ([needsRefresh]). Suspends; safe to call from a
     * `LaunchedEffect` and safe to cancel — a cancelled fetch just leaves the cache alone.
     *
     * A failed fetch is silent by design. The network on a phone in a lift is not an event the user
     * needs told about, and the pane behind this call is already showing the right thing.
     */
    suspend fun refreshIfNeeded(place: Place?, nowMillis: Long = System.currentTimeMillis()) {
        if (place == null || !needsRefresh(_forecast.value, place, nowMillis)) return
        if (!fetching.tryLock()) return
        try {
            val fresh = withContext(Dispatchers.IO) {
                WeatherApi.fetchForecast(place, System.currentTimeMillis())
            } ?: return
            _forecast.value = fresh
            prefs.edit().putString(KEY_CACHE, encodeForecast(fresh)).apply()
        } finally {
            fetching.unlock()
        }
    }

    /** Geocode a typed place name. Empty list covers both "no such place" and "no network". */
    suspend fun search(query: String): List<GeoResult> =
        withContext(Dispatchers.IO) { WeatherApi.search(query) }

    companion object {
        private const val PREFS = "sidehome_weather"
        private const val KEY_CACHE = "forecast_v1"

        @Volatile private var instance: WeatherStore? = null

        /**
         * The one process-wide store. The Future pane (the only thing that fetches) and the home
         * glance (which only *reads* the cache, SID-64) must observe the **same** [forecast] flow —
         * a separate instance per call site would leave the home frozen on the disk seed it read at
         * construction while the pane's fetch updated only its own copy, so a temperature that just
         * refreshed on the Future pane would never reach the home. Double-checked locking so the two
         * surfaces composing at once still share one.
         */
        fun get(context: Context): WeatherStore =
            instance ?: synchronized(this) {
                instance ?: WeatherStore(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * The process-wide [WeatherStore]. Every call site — the Future pane, the settings screen, the home
 * glance — resolves to the same instance, so a fetch on one is seen by all. Its in-memory cache
 * survives every trip back to the home (panes are never unmounted by the pager; see `PanePager`).
 */
@Composable
fun rememberWeatherStore(): WeatherStore {
    val context = LocalContext.current
    return remember(context.applicationContext) { WeatherStore.get(context) }
}
