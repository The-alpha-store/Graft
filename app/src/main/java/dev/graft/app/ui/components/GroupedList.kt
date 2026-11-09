package dev.graft.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** One continuous rounded surface holding several rows, titled by a [SectionHeader]. */
@Composable
fun GroupedListSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier) {
        SectionHeader(title)
        GroupedListColumn(content = content)
    }
}

@Composable
fun GroupedListColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    AppCard(modifier.fillMaxWidth()) {
        Column(content = content)
    }
}

/** A row's position within its group - every position except the last draws a divider under it. */
enum class GroupPosition { TOP, MIDDLE, BOTTOM, ONLY }

fun groupPositionFor(index: Int, count: Int): GroupPosition = when {
    count <= 1 -> GroupPosition.ONLY
    index == 0 -> GroupPosition.TOP
    index == count - 1 -> GroupPosition.BOTTOM
    else -> GroupPosition.MIDDLE
}

/** One row of a [GroupedListColumn]; draws a hairline divider (indented past the icon) unless last. */
@Composable
fun GroupedListItem(position: GroupPosition, content: @Composable () -> Unit) {
    Column {
        content()
        if (position == GroupPosition.TOP || position == GroupPosition.MIDDLE) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 66.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                thickness = 0.5.dp,
            )
        }
    }
}

/**
 * A tappable row: icon in a tinted rounded chip, title + subtitle, and an optional trailing slot
 * (a Switch, a chevron, a value...).
 */
@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(iconTint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        } else if (onClick != null) {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
