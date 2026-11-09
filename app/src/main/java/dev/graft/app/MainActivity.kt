package dev.graft.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.graft.app.data.AppearancePrefs
import dev.graft.app.ui.navigation.GraftApp
import dev.graft.app.ui.theme.AccentPalettes
import dev.graft.app.ui.theme.GraftTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val themeMode by AppearancePrefs.themeMode.collectAsState()
            val dynamicColor by AppearancePrefs.dynamicColor.collectAsState()
            val accent by AppearancePrefs.accent.collectAsState()

            val darkTheme = when (themeMode) {
                AppearancePrefs.ThemeMode.SYSTEM -> isSystemInDarkTheme()
                AppearancePrefs.ThemeMode.LIGHT -> false
                AppearancePrefs.ThemeMode.DARK -> true
            }
            val palette = remember(accent) { AccentPalettes.resolve(accent.presetId, accent.customHex) }

            GraftTheme(darkTheme = darkTheme, palette = palette, dynamicColor = dynamicColor) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    GraftApp()
                }
            }
        }
    }
}
