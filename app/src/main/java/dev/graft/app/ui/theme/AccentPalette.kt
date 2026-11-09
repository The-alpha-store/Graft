package dev.graft.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * A complete accent identity: three seed colors plus the hue the neutral surfaces are tinted with.
 *
 * - [primary]   navigation, selection, links, primary actions.
 * - [secondary] anything that talks to the remote (push, pull, fetch, sync), so that one color
 *               always means "this leaves your phone".
 * - [tertiary]  positive / "all good" highlights.
 *
 * Seeds are *inputs*: [ColorSchemes] solves the final per-theme colors (lightness, contrast,
 * containers) from them, so a seed never has to be hand-tuned for both light and dark.
 */
data class AccentPalette(
    val id: String,
    val label: String,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    /** Hue (degrees) used to tint neutral surfaces, so the whole UI feels like one family. */
    val surfaceHue: Float,
)

object AccentPalettes {

    /** Brand default - cyan trunk, amber remote, green success. Matches the launcher icon. */
    val Command = AccentPalette(
        id = "command",
        label = "Command",
        primary = Color(0xFF00D4F0),
        secondary = Color(0xFFFFAB00),
        tertiary = Color(0xFF2EE887),
        surfaceHue = 218f,
    )

    /** The reference app's violet / coral / emerald trio on warm neutrals. */
    val Orchid = AccentPalette(
        id = "orchid",
        label = "Orchid",
        primary = Color(0xFFC6A6FF),
        secondary = Color(0xFFFF8A5B),
        tertiary = Color(0xFF4CDA9B),
        surfaceHue = 28f,
    )

    private val seeded: List<Pair<String, Pair<String, Color>>> = listOf(
        "ember" to ("Ember" to Color(0xFFF97316)),
        "ocean" to ("Ocean" to Color(0xFF0284C7)),
        "grove" to ("Grove" to Color(0xFF6B8E23)),
        "berry" to ("Berry" to Color(0xFFD946EF)),
        "honey" to ("Honey" to Color(0xFFFACC15)),
        "dusk" to ("Dusk" to Color(0xFF6B7280)),
        "iris" to ("Iris" to Color(0xFF7C3AED)),
    )

    const val CUSTOM_ID = "custom"

    /** Every selectable preset, in picker order. */
    val presets: List<AccentPalette> =
        listOf(Command, Orchid) + seeded.map { (id, labelAndSeed) -> fromSeed(id, labelAndSeed.first, labelAndSeed.second) }

    fun byId(id: String): AccentPalette = presets.firstOrNull { it.id == id } ?: Command

    /**
     * Resolves the user's stored choice. A valid [customHex] wins when [id] is [CUSTOM_ID];
     * anything unrecognized falls back to [Command] rather than crashing on stale prefs.
     */
    fun resolve(id: String, customHex: String?): AccentPalette {
        if (id == CUSTOM_ID) {
            val seed = customHex?.let(::parseHexColorOrNull)
            if (seed != null) return fromSeed(CUSTOM_ID, "Custom", seed)
        }
        return byId(id)
    }

    /**
     * Builds a coherent three-accent palette from a single seed: secondary rotates +20 degrees with
     * softened saturation, tertiary rotates -40 degrees on a different curve - enough separation
     * that three colors from one seed read as a deliberate trio, not three shades of one hue.
     */
    fun fromSeed(id: String, label: String, seed: Color): AccentPalette {
        val (h, s, l) = seed.toHsl()

        val secHue = (h + 20f) % 360f
        val secSat = if (s < 0.30f) s else (s * 0.70f).coerceIn(0.35f, 0.85f)
        val secLight = if (l < 0.5f) (l + 0.04f).coerceIn(0f, 1f) else (l - 0.04f).coerceIn(0f, 1f)

        val terHue = (h - 40f + 360f) % 360f
        val terSat = if (s < 0.35f) s else (s * 0.85f).coerceIn(0.40f, 0.90f)
        val terLight = if (l < 0.5f) (l + 0.08f).coerceIn(0f, 1f) else (l - 0.08f).coerceIn(0f, 1f)

        return AccentPalette(
            id = id,
            label = label,
            primary = seed,
            secondary = hsl(secHue, secSat, secLight),
            tertiary = hsl(terHue, terSat, terLight),
            surfaceHue = h,
        )
    }
}

/** @return the parsed color, or null if [hex] isn't a valid 6-digit hex color (with or without '#'). */
fun parseHexColorOrNull(hex: String): Color? {
    val clean = hex.removePrefix("#").trim()
    if (clean.length != 6 || clean.any { it !in "0123456789abcdefABCDEF" }) return null
    return try {
        Color(("FF" + clean).toLong(16).toInt())
    } catch (e: NumberFormatException) {
        null
    }
}
