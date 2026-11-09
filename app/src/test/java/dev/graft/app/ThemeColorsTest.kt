package dev.graft.app

import androidx.compose.ui.graphics.Color
import dev.graft.app.ui.theme.AccentPalettes
import dev.graft.app.ui.theme.buildColorScheme
import dev.graft.app.ui.theme.contrastRatio
import dev.graft.app.ui.theme.gitColorsFor
import dev.graft.app.ui.theme.parseHexColorOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeColorsTest {

    /** WCAG AA for normal text. Every accent the user can pick must clear it in both themes. */
    private val aa = 4.5f

    @Test
    fun `every preset is readable as text and as a filled button in both themes`() {
        for (palette in AccentPalettes.presets) {
            for (dark in listOf(true, false)) {
                val s = buildColorScheme(palette, dark)
                val where = "${palette.id}/${if (dark) "dark" else "light"}"
                assertTrue("$where primary on surface", contrastRatio(s.primary, s.surface) >= aa)
                assertTrue("$where secondary on surface", contrastRatio(s.secondary, s.surface) >= aa)
                assertTrue("$where tertiary on surface", contrastRatio(s.tertiary, s.surface) >= aa)
                assertTrue("$where onPrimary on primary", contrastRatio(s.onPrimary, s.primary) >= aa)
                assertTrue("$where onSecondary on secondary", contrastRatio(s.onSecondary, s.secondary) >= aa)
                assertTrue("$where onSurface on surface", contrastRatio(s.onSurface, s.surface) >= 7f)
                assertTrue("$where onSurfaceVariant on container", contrastRatio(s.onSurfaceVariant, s.surfaceContainerHigh) >= aa)
                assertTrue("$where onPrimaryContainer on primaryContainer", contrastRatio(s.onPrimaryContainer, s.primaryContainer) >= aa)
            }
        }
    }

    @Test
    fun `git status colors are readable on cards in both themes`() {
        for (dark in listOf(true, false)) {
            val card = buildColorScheme(AccentPalettes.Command, dark).surfaceContainer
            val g = gitColorsFor(dark)
            for ((name, c) in listOf("added" to g.added, "modified" to g.modified, "deleted" to g.deleted,
                "renamed" to g.renamed, "conflict" to g.conflict)) {
                assertTrue("$name dark=$dark", contrastRatio(c, card) >= aa)
            }
        }
    }

    @Test
    fun `preset ids are unique and lookup falls back to the default`() {
        val ids = AccentPalettes.presets.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(AccentPalettes.Command, AccentPalettes.byId("no-such-palette"))
        assertEquals(AccentPalettes.Orchid, AccentPalettes.byId("orchid"))
    }

    @Test
    fun `custom accent resolves from valid hex and degrades gracefully otherwise`() {
        val custom = AccentPalettes.resolve(AccentPalettes.CUSTOM_ID, "#33AA77")
        assertEquals(AccentPalettes.CUSTOM_ID, custom.id)
        assertEquals(Color(0xFF33AA77), custom.primary)
        // Garbage stored in prefs must never crash the theme.
        assertEquals(AccentPalettes.Command, AccentPalettes.resolve(AccentPalettes.CUSTOM_ID, "not-a-color"))
        assertEquals(AccentPalettes.Command, AccentPalettes.resolve(AccentPalettes.CUSTOM_ID, null))
    }

    @Test
    fun `hex parsing accepts 6 digits with or without hash and rejects the rest`() {
        assertNotNull(parseHexColorOrNull("FFAA00"))
        assertNotNull(parseHexColorOrNull("#ffaa00"))
        assertNull(parseHexColorOrNull("FFAA0"))
        assertNull(parseHexColorOrNull("GGGGGG"))
        assertNull(parseHexColorOrNull(""))
    }
}
