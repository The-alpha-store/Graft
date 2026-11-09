package dev.graft.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A 4dp-based spacing scale. Use these instead of ad-hoc dp literals so rhythm stays consistent. */
@Immutable
data class Spacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }

object Dimens {
    /** Horizontal gutter for screen content. */
    val ScreenPadding = 16.dp
    /** Minimum interactive size (Material / WCAG touch target). */
    val MinTouchTarget = 48.dp
    /** Leading status bar on cards. */
    val AccentBarWidth = 4.dp
}
