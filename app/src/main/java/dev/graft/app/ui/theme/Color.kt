package dev.graft.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------------------------------------
// Color math
//
// Graft builds every palette from a handful of seed colors, so the app needs a tiny amount
// of color science: HSL conversion (to rotate hue / pick tonal steps) and WCAG contrast (to make
// sure text stays readable no matter which accent the user picks). Plain HSL is the right level
// of sophistication here - these are flat accents, not a generated HCT tonal scheme.
// ---------------------------------------------------------------------------------------------

/** Hue in degrees (0..360), saturation and lightness in 0..1. */
data class Hsl(val h: Float, val s: Float, val l: Float)

fun Color.toHsl(): Hsl {
    val r = red
    val g = green
    val b = blue
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val delta = maxC - minC
    val lightness = (maxC + minC) / 2f

    var hue = 0f
    var saturation = 0f
    if (delta != 0f) {
        saturation = if (lightness < 0.5f) delta / (maxC + minC) else delta / (2f - maxC - minC)
        hue = when (maxC) {
            r -> (g - b) / delta + (if (g < b) 6f else 0f)
            g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        }
        hue *= 60f
    }
    return Hsl(hue, saturation, lightness)
}

fun hsl(hue: Float, saturation: Float, lightness: Float, alpha: Float = 1f): Color {
    val h = ((hue % 360f) + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val l = lightness.coerceIn(0f, 1f)
    val chroma = (1f - abs(2f * l - 1f)) * s
    val x = chroma * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - chroma / 2f
    val r: Float
    val g: Float
    val b: Float
    when {
        h < 60f -> { r = chroma; g = x; b = 0f }
        h < 120f -> { r = x; g = chroma; b = 0f }
        h < 180f -> { r = 0f; g = chroma; b = x }
        h < 240f -> { r = 0f; g = x; b = chroma }
        h < 300f -> { r = x; g = 0f; b = chroma }
        else -> { r = chroma; g = 0f; b = x }
    }
    return Color(
        red = (r + m).coerceIn(0f, 1f),
        green = (g + m).coerceIn(0f, 1f),
        blue = (b + m).coerceIn(0f, 1f),
        alpha = alpha,
    )
}

/** WCAG 2.x contrast ratio between two opaque colors (1..21). */
fun contrastRatio(a: Color, b: Color): Float {
    val l1 = a.luminance()
    val l2 = b.luminance()
    return (max(l1, l2) + 0.05f) / (min(l1, l2) + 0.05f)
}

/**
 * Nudges [seed]'s lightness (keeping its hue/saturation character) until it reaches
 * [minContrast] against [background]. Dark themes lighten, light themes darken. This is what lets
 * one seed color (say, a yellow "Honey" accent) be legible as text on both a near-black and a
 * near-white surface without hand-picking a second hex.
 */
fun accentAgainst(seed: Color, background: Color, dark: Boolean, minContrast: Float = 4.6f): Color {
    val c = seed.toHsl()
    // Keep genuinely neutral seeds (e.g. a gray accent) neutral instead of forcing chroma into them.
    val s = if (c.s < 0.25f) c.s else c.s.coerceIn(0.50f, 0.95f)
    var l = if (dark) max(c.l, 0.62f) else min(c.l, 0.40f)
    var color = hsl(c.h, s, l)
    var guard = 0
    while (contrastRatio(color, background) < minContrast && guard < 40) {
        l = if (dark) l + 0.02f else l - 0.02f
        if (l !in 0.12f..0.92f) break
        color = hsl(c.h, s, l)
        guard++
    }
    return color
}

/** Text/icon color to place on top of [background]: whichever of white or [ink] reads better. */
fun onColorFor(background: Color, ink: Color): Color =
    if (contrastRatio(background, Color.White) >= contrastRatio(background, ink)) Color.White else ink

// ---------------------------------------------------------------------------------------------
// Semantic git colors
//
// Material's ColorScheme has no notion of "added" or "deleted", and those meanings must not move
// when the user swaps accent palettes (a deletion should always read as red). So git-status colors
// live in their own immutable set, provided through the theme, and tuned separately for dark and
// light so each pairing clears WCAG AA against its surface.
// ---------------------------------------------------------------------------------------------

@Immutable
data class GitColors(
    val added: Color,
    val addedContainer: Color,
    val modified: Color,
    val modifiedContainer: Color,
    val deleted: Color,
    val deletedContainer: Color,
    val renamed: Color,
    val renamedContainer: Color,
    val conflict: Color,
    val conflictContainer: Color,
    /** Row tint behind added/removed/hunk lines in a diff. Translucent so syntax colors stay legible. */
    val diffAddedBackground: Color,
    val diffRemovedBackground: Color,
    val diffHunkBackground: Color,
    /** Line-number gutter, slightly offset from the code surface. */
    val diffGutterBackground: Color,
    val syntax: SyntaxColorSet,
    val isDark: Boolean,
)

private val DarkGitColors = GitColors(
    added = hsl(145f, 0.60f, 0.62f),
    addedContainer = hsl(145f, 0.40f, 0.17f),
    modified = hsl(40f, 0.95f, 0.62f),
    modifiedContainer = hsl(40f, 0.55f, 0.17f),
    deleted = hsl(355f, 0.92f, 0.70f),
    deletedContainer = hsl(355f, 0.50f, 0.20f),
    renamed = hsl(212f, 0.90f, 0.72f),
    renamedContainer = hsl(212f, 0.50f, 0.20f),
    conflict = hsl(305f, 0.75f, 0.74f),
    conflictContainer = hsl(305f, 0.45f, 0.21f),
    diffAddedBackground = hsl(145f, 0.60f, 0.50f, alpha = 0.16f),
    diffRemovedBackground = hsl(355f, 0.80f, 0.60f, alpha = 0.17f),
    diffHunkBackground = hsl(212f, 0.80f, 0.62f, alpha = 0.12f),
    diffGutterBackground = Color(0x0AFFFFFF),
    syntax = DarkSyntax,
    isDark = true,
)

private val LightGitColors = GitColors(
    added = hsl(145f, 0.75f, 0.26f),
    addedContainer = hsl(145f, 0.55f, 0.89f),
    modified = hsl(30f, 0.95f, 0.31f),
    modifiedContainer = hsl(40f, 0.90f, 0.88f),
    deleted = hsl(355f, 0.75f, 0.40f),
    deletedContainer = hsl(355f, 0.85f, 0.92f),
    renamed = hsl(212f, 0.85f, 0.38f),
    renamedContainer = hsl(212f, 0.85f, 0.92f),
    conflict = hsl(305f, 0.70f, 0.36f),
    conflictContainer = hsl(305f, 0.65f, 0.92f),
    diffAddedBackground = hsl(145f, 0.65f, 0.45f, alpha = 0.16f),
    diffRemovedBackground = hsl(355f, 0.75f, 0.55f, alpha = 0.14f),
    diffHunkBackground = hsl(212f, 0.80f, 0.55f, alpha = 0.10f),
    diffGutterBackground = Color(0x0A000000),
    syntax = LightSyntax,
    isDark = false,
)

fun gitColorsFor(dark: Boolean): GitColors = if (dark) DarkGitColors else LightGitColors

val LocalGitColors = staticCompositionLocalOf { DarkGitColors }
