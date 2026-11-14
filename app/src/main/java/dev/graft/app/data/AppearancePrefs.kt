package dev.graft.app.data

import android.content.Context
import android.content.SharedPreferences
import dev.graft.app.ui.theme.AccentPalettes
import dev.graft.app.ui.theme.parseHexColorOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The user's accent selection: a preset id, or [AccentPalettes.CUSTOM_ID] with a stored hex. */
data class AccentChoice(
    val presetId: String = AccentPalettes.Command.id,
    val customHex: String? = null,
)

/**
 * Everything about how the app looks, as reactive [StateFlow]s: changing a value in Settings is
 * collected by `MainActivity`, which rebuilds the theme - no activity restart, and no global
 * mutable color state anywhere (the previous design repainted by reassigning top-level
 * `mutableStateOf` colors, which only worked for code that happened to read those vars).
 *
 * [init] runs once from `App.onCreate`; every setter is safe from anywhere with a [Context].
 */
object AppearancePrefs {
    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    enum class EditorTextSize(val fontSp: Float, val lineHeightSp: Float, val label: String) {
        SMALL(11f, 16f, "Small"),
        DEFAULT(13f, 19f, "Default"),
        LARGE(15f, 22f, "Large"),
    }

    private const val PREFS_NAME = "graft_appearance_prefs"
    private lateinit var prefs: SharedPreferences

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode

    /** Material You wallpaper colors (Android 12+). Off by default so the accent picker applies. */
    private val _dynamicColor = MutableStateFlow(false)
    val dynamicColor: StateFlow<Boolean> = _dynamicColor

    private val _accent = MutableStateFlow(AccentChoice())
    val accent: StateFlow<AccentChoice> = _accent

    private val _editorTextSize = MutableStateFlow(EditorTextSize.DEFAULT)
    val editorTextSize: StateFlow<EditorTextSize> = _editorTextSize

    /** Wrap long lines in the code editor (hides the line-number gutter, which can't track wrapped rows). */
    private val _editorWordWrap = MutableStateFlow(false)
    val editorWordWrap: StateFlow<Boolean> = _editorWordWrap

    private val _editorLineNumbers = MutableStateFlow(true)
    val editorLineNumbers: StateFlow<Boolean> = _editorLineNumbers

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _themeMode.value = runCatching { ThemeMode.valueOf(prefs.getString("theme_mode", null) ?: "") }
            .getOrDefault(ThemeMode.SYSTEM)
        _dynamicColor.value = prefs.getBoolean("dynamic_color", false)
        _accent.value = AccentChoice(
            presetId = prefs.getString("accent_preset", null) ?: AccentPalettes.Command.id,
            customHex = prefs.getString("accent_custom_hex", null),
        )
        _editorWordWrap.value = prefs.getBoolean("editor_word_wrap", false)
        _editorLineNumbers.value = prefs.getBoolean("editor_line_numbers", true)
        _editorTextSize.value = runCatching { EditorTextSize.valueOf(prefs.getString("editor_text_size", null) ?: "") }
            .getOrDefault(EditorTextSize.DEFAULT)
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        init(context)
        prefs.edit().putString("theme_mode", mode.name).apply()
        _themeMode.value = mode
    }

    fun setDynamicColor(context: Context, enabled: Boolean) {
        init(context)
        prefs.edit().putBoolean("dynamic_color", enabled).apply()
        _dynamicColor.value = enabled
    }

    fun setAccentPreset(context: Context, presetId: String) {
        init(context)
        prefs.edit().putString("accent_preset", presetId).remove("accent_custom_hex").apply()
        _accent.value = AccentChoice(presetId = presetId)
    }

    /** @return false (changing nothing) if [hex] isn't a valid 6-digit hex color. */
    fun setCustomAccent(context: Context, hex: String): Boolean {
        init(context)
        if (parseHexColorOrNull(hex) == null) return false
        prefs.edit().putString("accent_preset", AccentPalettes.CUSTOM_ID).putString("accent_custom_hex", hex).apply()
        _accent.value = AccentChoice(presetId = AccentPalettes.CUSTOM_ID, customHex = hex)
        return true
    }

    fun setEditorWordWrap(context: Context, enabled: Boolean) {
        init(context)
        prefs.edit().putBoolean("editor_word_wrap", enabled).apply()
        _editorWordWrap.value = enabled
    }

    fun setEditorLineNumbers(context: Context, enabled: Boolean) {
        init(context)
        prefs.edit().putBoolean("editor_line_numbers", enabled).apply()
        _editorLineNumbers.value = enabled
    }

    fun setEditorTextSize(context: Context, size: EditorTextSize) {
        init(context)
        prefs.edit().putString("editor_text_size", size.name).apply()
        _editorTextSize.value = size
    }
}
