package fi.palonkorpi.sidehome.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode

/**
 * A no-op indication: taps produce no ripple. Provided via LocalIndication at the root so
 * every clickable in the launcher is ripple-free — the glyph/label is the affordance, and
 * ripple is a per-tap saveLayer this GPU is better off without. The calm look wants no
 * splash anyway.
 */
object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = -1
}
