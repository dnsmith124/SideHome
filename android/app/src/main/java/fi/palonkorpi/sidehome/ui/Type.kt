package fi.palonkorpi.sidehome.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import fi.palonkorpi.sidehome.R

/**
 * Inter — bundled (all weights) so the launcher looks the same everywhere instead of
 * falling back to the platform Roboto. The type scale leans on Inter's clear weight
 * separation: a light, airy clock against regular body and a semibold for labels.
 */
val Inter = FontFamily(
    Font(R.font.inter_thin, FontWeight.Thin),
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)
