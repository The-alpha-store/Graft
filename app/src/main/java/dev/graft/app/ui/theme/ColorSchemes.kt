package dev.graft.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Turns an [AccentPalette] into a full Material 3 [ColorScheme] for one theme.
 *
 * Every role is filled in explicitly - the old light scheme only set a handful and left the rest at
 * Material's baseline *purple*, which leaked into dividers, chips and sheets. Surfaces are
 * low-chroma neutrals tinted with the palette's hue, stepped in lightness to form a real tonal
 * ladder (lowest -> highest), so elevation reads through tone instead of hairline borders.
 */
fun buildColorScheme(p: AccentPalette, dark: Boolean): ColorScheme =
    if (dark) darkScheme(p) else lightScheme(p)

private fun darkScheme(p: AccentPalette): ColorScheme {
    val h = p.surfaceHue
    fun surface(l: Float, s: Float = 0.16f) = hsl(h, s, l)

    val background = surface(0.065f)
    val ink = hsl(h, 0.30f, 0.07f)

    val primary = accentAgainst(p.primary, surface(0.105f), dark = true)
    val secondary = accentAgainst(p.secondary, surface(0.105f), dark = true)
    val tertiary = accentAgainst(p.tertiary, surface(0.105f), dark = true)
    val error = DarkErrorBase

    return darkColorScheme(
        primary = primary,
        onPrimary = onColorFor(primary, ink),
        primaryContainer = containerOf(primary, dark = true),
        onPrimaryContainer = onContainerOf(primary, dark = true),
        inversePrimary = accentAgainst(p.primary, hsl(h, 0.20f, 0.92f), dark = false),
        secondary = secondary,
        onSecondary = onColorFor(secondary, ink),
        secondaryContainer = containerOf(secondary, dark = true),
        onSecondaryContainer = onContainerOf(secondary, dark = true),
        tertiary = tertiary,
        onTertiary = onColorFor(tertiary, ink),
        tertiaryContainer = containerOf(tertiary, dark = true),
        onTertiaryContainer = onContainerOf(tertiary, dark = true),
        error = error,
        onError = onColorFor(error, ink),
        errorContainer = hsl(355f, 0.50f, 0.22f),
        onErrorContainer = hsl(355f, 0.90f, 0.90f),
        background = background,
        onBackground = hsl(h, 0.20f, 0.91f),
        surface = background,
        onSurface = hsl(h, 0.20f, 0.91f),
        surfaceVariant = surface(0.17f, 0.14f),
        onSurfaceVariant = hsl(h, 0.12f, 0.72f),
        surfaceTint = primary,
        inverseSurface = hsl(h, 0.20f, 0.90f),
        inverseOnSurface = hsl(h, 0.20f, 0.14f),
        outline = hsl(h, 0.10f, 0.50f),
        outlineVariant = hsl(h, 0.14f, 0.24f),
        scrim = Color.Black,
        surfaceBright = surface(0.20f),
        surfaceDim = surface(0.05f),
        surfaceContainerLowest = surface(0.045f),
        surfaceContainerLow = surface(0.085f),
        surfaceContainer = surface(0.105f),
        surfaceContainerHigh = surface(0.14f),
        surfaceContainerHighest = surface(0.17f),
    )
}

private fun lightScheme(p: AccentPalette): ColorScheme {
    val h = p.surfaceHue
    fun surface(l: Float, s: Float = 0.30f) = hsl(h, s, l)

    val background = surface(0.975f)
    val ink = hsl(h, 0.30f, 0.09f)

    val primary = accentAgainst(p.primary, background, dark = false)
    val secondary = accentAgainst(p.secondary, background, dark = false)
    val tertiary = accentAgainst(p.tertiary, background, dark = false)
    val error = LightErrorBase

    return lightColorScheme(
        primary = primary,
        onPrimary = onColorFor(primary, ink),
        primaryContainer = containerOf(primary, dark = false),
        onPrimaryContainer = onContainerOf(primary, dark = false),
        inversePrimary = accentAgainst(p.primary, hsl(h, 0.16f, 0.14f), dark = true),
        secondary = secondary,
        onSecondary = onColorFor(secondary, ink),
        secondaryContainer = containerOf(secondary, dark = false),
        onSecondaryContainer = onContainerOf(secondary, dark = false),
        tertiary = tertiary,
        onTertiary = onColorFor(tertiary, ink),
        tertiaryContainer = containerOf(tertiary, dark = false),
        onTertiaryContainer = onContainerOf(tertiary, dark = false),
        error = error,
        onError = onColorFor(error, ink),
        errorContainer = hsl(355f, 0.85f, 0.92f),
        onErrorContainer = hsl(355f, 0.80f, 0.20f),
        background = background,
        onBackground = hsl(h, 0.25f, 0.10f),
        surface = background,
        onSurface = hsl(h, 0.25f, 0.10f),
        surfaceVariant = surface(0.91f, 0.22f),
        onSurfaceVariant = hsl(h, 0.12f, 0.34f),
        surfaceTint = primary,
        inverseSurface = hsl(h, 0.16f, 0.14f),
        inverseOnSurface = hsl(h, 0.20f, 0.94f),
        outline = hsl(h, 0.10f, 0.46f),
        outlineVariant = hsl(h, 0.16f, 0.82f),
        scrim = Color.Black,
        surfaceBright = Color.White,
        surfaceDim = surface(0.90f),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = surface(0.965f),
        surfaceContainer = surface(0.945f),
        surfaceContainerHigh = surface(0.925f),
        surfaceContainerHighest = surface(0.90f),
    )
}

private val DarkErrorBase = hsl(355f, 0.92f, 0.70f)
private val LightErrorBase = hsl(355f, 0.75f, 0.40f)

/** Tinted fill for "container" roles - the seed's hue at low chroma, dark or pale by theme. */
private fun containerOf(accent: Color, dark: Boolean): Color {
    val c = accent.toHsl()
    return if (dark) hsl(c.h, (c.s * 0.55f).coerceIn(0.10f, 0.60f), 0.24f)
    else hsl(c.h, (c.s * 0.85f).coerceIn(0.10f, 0.90f), 0.88f)
}

private fun onContainerOf(accent: Color, dark: Boolean): Color {
    val c = accent.toHsl()
    return if (dark) hsl(c.h, (c.s * 0.9f).coerceIn(0.10f, 1f), 0.90f)
    else hsl(c.h, (c.s * 0.9f).coerceIn(0.10f, 1f), 0.14f)
}
