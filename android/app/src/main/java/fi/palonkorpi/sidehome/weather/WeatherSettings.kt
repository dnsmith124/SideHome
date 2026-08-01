package fi.palonkorpi.sidehome.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.palonkorpi.sidehome.settings.SettingsStore
import fi.palonkorpi.sidehome.settings.tempUnitAsState
import fi.palonkorpi.sidehome.settings.weatherPlaceAsState

/**
 * The §6 settings mirror of the Future pane's two weather settings (SID-35).
 *
 * SPEC §4a asks for the location to be editable **two ways**: from the forecast itself, where you
 * noticed it was wrong, and from the settings screen, where you'd go looking if you didn't think to
 * tap the pane. Both write the same `SettingsStore.weatherPlace`, and the shared-prefs listener is
 * what makes a change made here show up on a pane that is already composed.
 *
 * These rows are deliberately shaped like `SettingsActivity`'s own — a label on the left, the state
 * on the right — rather than exported from it, because that file belongs to the settings screen and
 * this is a weather feature that happens to be visible there.
 */
@Composable
fun WeatherSettingsSection(
    settings: SettingsStore,
    fg: Color,
    accent: Color,
    onEditLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val place by settings.weatherPlaceAsState()
    val unit by settings.tempUnitAsState()

    Column(modifier) {
        Text("Weather", color = fg, fontWeight = FontWeight.Light, fontSize = 22.sp)
        Spacer(Modifier.height(14.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onEditLocation)
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Location", color = fg, fontSize = 17.sp)
            // Accent when set, plain when not: the same "this is live" mark the notification-dots
            // row uses for its granted state, so the settings screen has one vocabulary and not two.
            Text(
                place?.name ?: "Not set — tap to choose",
                color = if (place != null) accent else fg.copy(alpha = 0.7f),
                fontSize = 15.sp,
                fontWeight = if (place != null) FontWeight.SemiBold else FontWeight.Normal,
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "Typed, not sensed: SideHome asks Open-Meteo for the coordinates of a place you name, " +
                "and never asks Android where you are.",
            color = fg.copy(alpha = 0.85f),
            fontSize = 13.sp,
            lineHeight = 17.sp,
        )

        Spacer(Modifier.height(20.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Temperature", color = fg, fontSize = 17.sp)
            UnitToggle(selected = unit, fg = fg, accent = accent, onSelect = { settings.tempUnit = it })
        }
    }
}

/**
 * °C / °F, shaped like `SettingsActivity`'s private `Segmented` — same pill, same accent cushion on
 * the chosen half. Duplicated rather than shared: the alternative was widening that file's private
 * helper into an API, which is a bigger change to a file this feature has no business owning.
 */
@Composable
private fun UnitToggle(
    selected: TempUnit,
    fg: Color,
    accent: Color,
    onSelect: (TempUnit) -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(fg.copy(alpha = 0.08f)),
    ) {
        listOf("°C" to TempUnit.CELSIUS, "°F" to TempUnit.FAHRENHEIT).forEach { (text, value) ->
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
