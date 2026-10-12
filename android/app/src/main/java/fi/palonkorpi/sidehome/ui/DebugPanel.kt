package fi.palonkorpi.sidehome.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The developer knobs, kept out of `HomeScreen.kt` — none of this ships as a user surface, and it
 * is edited on a different rhythm from the launcher itself (a switch per prototype in flight).
 * Raised by long-pressing the clock.
 */
@Composable
internal fun DebugDayPanel(
    hour: Float,
    isLive: Boolean,
    fg: Color,
    accent: Color,
    panelColor: Color,
    arbiterEnabled: Boolean,
    hudVisible: Boolean,
    sandbox: Boolean,
    onToggleArbiter: () -> Unit,
    onToggleHud: () -> Unit,
    onToggleSandbox: () -> Unit,
    onResetSandbox: () -> Unit,
    onOpenThemes: () -> Unit,
    onScrub: (Float) -> Unit,
    onLive: () -> Unit,
    onClose: () -> Unit,
) {
    val h = hour.toInt().coerceIn(0, 23)
    val m = ((hour - hour.toInt()) * 60).toInt().coerceIn(0, 59)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(panelColor)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                LineIcon(glyph = "clock", color = fg, size = 18.dp, strokeWidthUnits = 1.6f)
                Spacer(Modifier.width(8.dp))
                Text("%02d:%02d".format(h, m), color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Text(if (isLive) "live" else "preview", color = fg.copy(alpha = 0.5f), fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "Live", color = if (isLive) fg.copy(alpha = 0.4f) else accent, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onLive() }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Text(
                    "Done", color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClose() }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Slider(
                value = hour.coerceIn(0f, 24f),
                onValueChange = onScrub,
                valueRange = 0f..24f,
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = fg.copy(alpha = 0.2f),
                ),
            )
            // Scratch home for trying the §1 placement gestures. "Reset" only appears while it's on:
            // it throws away the scratch tiles and deals a fresh set, and out of the sandbox there
            // is nothing it could mean — offering it there would read as "reset my real home".
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                DebugToggle("Sandbox", sandbox, fg, accent, onToggleSandbox)
                if (sandbox) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Reset", color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onResetSandbox() }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (sandbox) "scratch home" else "real home",
                    color = fg.copy(alpha = 0.5f), fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(6.dp))
            // SID-24 gesture-arbiter switches: enable the routing prototype and its HUD.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                DebugToggle("Gesture arbiter", arbiterEnabled, fg, accent, onToggleArbiter)
                Spacer(Modifier.width(10.dp))
                DebugToggle("HUD", hudVisible, fg, accent, onToggleHud)
                Spacer(Modifier.weight(1f))
                // SID-42: open the zoom-out theme chooser (debug entry until SID-26's long-press).
                Text(
                    "Themes", color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOpenThemes() }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun DebugToggle(
    label: String,
    on: Boolean,
    fg: Color,
    accent: Color,
    onToggle: () -> Unit,
) {
    Text(
        text = (if (on) "● " else "○ ") + label,
        color = if (on) accent else fg.copy(alpha = 0.6f),
        fontSize = 13.sp,
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onToggle() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}
