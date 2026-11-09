package dev.graft.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.graft.app.ui.theme.Dimens

/**
 * The app's one card surface: a flat tonal fill, a hairline outline, and an optional leading
 * status bar. Every list row, tile and panel builds on this so the whole app shares a single
 * "object on a surface" look.
 *
 * [accent] draws an inset rounded bar down the leading edge (branch / status colour at a glance).
 * The row is measured with an intrinsic height so the bar actually fills the card - inside a lazy
 * list the card's max height is unbounded, and a plain `fillMaxHeight()` there collapses to zero.
 *
 * Pass [onClick] to make the whole card tappable with proper ripple and semantics.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable () -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = containerColor)
    val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    val shape = MaterialTheme.shapes.large

    val body: @Composable () -> Unit = {
        if (accent != null) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(
                    Modifier
                        .padding(vertical = 12.dp)
                        .width(Dimens.AccentBarWidth)
                        .fillMaxHeight()
                        .background(accent, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)),
                )
                Box(Modifier.weight(1f)) { content() }
            }
        } else {
            content()
        }
    }

    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, elevation = elevation, border = border) {
            body()
        }
    } else {
        Card(modifier = modifier, shape = shape, colors = colors, elevation = elevation, border = border) {
            body()
        }
    }
}
