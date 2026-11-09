package dev.graft.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Corner radii step up with the size of the surface: tight on chips and fields, soft on cards,
 * generous on sheets. One scale used everywhere instead of per-screen radius literals.
 */
val GraftShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Semantic git colors (added / modified / deleted ...) for the active theme. */
val MaterialTheme.gitColors: GitColors
    @Composable @ReadOnlyComposable get() = LocalGitColors.current

/** Monospace text styles for paths, hashes, branches and code. */
val MaterialTheme.code: CodeStyles
    @Composable @ReadOnlyComposable get() = LocalCodeStyles.current

val MaterialTheme.spacing: Spacing
    @Composable @ReadOnlyComposable get() = LocalSpacing.current

/**
 * @param darkTheme  resolved dark/light (the caller maps System/Light/Dark to this).
 * @param palette    accent identity; ignored while [dynamicColor] is active on Android 12+.
 * @param dynamicColor Material You wallpaper colors. Off by default so the brand identity - and the
 *                   user's accent choice - is what they see; Settings makes the trade-off explicit.
 */
@Composable
fun GraftTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    palette: AccentPalette = AccentPalettes.Command,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val useDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme = if (useDynamic) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        remember(palette, darkTheme) { buildColorScheme(palette, darkTheme) }
    }
    val gitColors = remember(darkTheme) { gitColorsFor(darkTheme) }

    // The app can force a theme that differs from the system's, so the status/navigation bar icon
    // contrast has to follow the *app* theme rather than whatever edge-to-edge guessed.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalGitColors provides gitColors,
        LocalCodeStyles provides DefaultCodeStyles,
        LocalSpacing provides Spacing(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = GraftTypography,
            shapes = GraftShapes,
            content = content,
        )
    }
}
