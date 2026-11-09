package dev.graft.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Standard Material scale with firmer weights at the top and tighter titles. `bodySmall` stays a
 * normal proportional face - it carries helper prose all over the app. Anything that is *data*
 * (paths, hashes, branch names, URLs, diffs) uses [CodeStyles] instead, so "this is data, not
 * prose" is an explicit choice at each call site rather than a global side effect.
 */
private val Base = Typography()

val GraftTypography = Typography(
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.Bold),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge,
    bodyMedium = Base.bodyMedium,
    bodySmall = Base.bodySmall,
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.Medium),
    labelSmall = Base.labelSmall.copy(fontWeight = FontWeight.Medium),
)

/** Monospace styles for data: file paths, commit hashes, branch names, URLs, code. */
@Immutable
data class CodeStyles(
    val body: TextStyle,
    val small: TextStyle,
    val tiny: TextStyle,
)

val DefaultCodeStyles = CodeStyles(
    body = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    small = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    tiny = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp),
)

val LocalCodeStyles = staticCompositionLocalOf { DefaultCodeStyles }
