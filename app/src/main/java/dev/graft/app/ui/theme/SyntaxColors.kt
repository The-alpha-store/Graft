package dev.graft.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Color tokens for code syntax highlighting.
 *
 * Kept apart from the Material color scheme because highlighting needs more distinct hues
 * (keyword vs. string vs. comment vs. number...) than a UI palette offers, and each needs its own
 * light/dark pair so code stays readable in both themes. The active set travels with the app's
 * theme through [GitColors.syntax] - it follows the theme the *user* chose in Settings, not the
 * system's day/night mode (the old implementation read `isSystemInDarkTheme()`, so forcing Dark
 * on a Light system rendered dark-on-dark code).
 */
@Immutable
data class SyntaxColorSet(
    val keyword: Color,
    val type: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val annotation: Color,
    val punctuation: Color,
    /** XML/HTML tag names. */
    val tag: Color,
    /** XML/HTML attribute names. */
    val attribute: Color,
    val plain: Color,
)

internal val DarkSyntax = SyntaxColorSet(
    keyword = Color(0xFF7CC4FF),
    type = Color(0xFF7EE0C3),
    string = Color(0xFFF2B33D),
    number = Color(0xFFD5A6FF),
    comment = Color(0xFF7D8AA0),
    annotation = Color(0xFFFF9D5C),
    punctuation = Color(0xFF93A0B5),
    tag = Color(0xFF7CC4FF),
    attribute = Color(0xFF7EE0C3),
    plain = Color(0xFFE7EAF0),
)

internal val LightSyntax = SyntaxColorSet(
    keyword = Color(0xFF0B5FA8),
    type = Color(0xFF0E7A5F),
    string = Color(0xFF8F5A00),
    number = Color(0xFF7A3FB8),
    comment = Color(0xFF667085),
    annotation = Color(0xFFB5590E),
    punctuation = Color(0xFF4F5968),
    tag = Color(0xFF0B5FA8),
    attribute = Color(0xFF0E7A5F),
    plain = Color(0xFF11151C),
)
