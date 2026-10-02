package com.smnexstudio.panelglass.core.render

import com.smnexstudio.panelglass.core.model.FontRole
import com.smnexstudio.panelglass.core.model.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/** The bundled font catalogue as shipped (assets/fonts/catalogue.json), checked against the files beside it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FontCatalogueTest {
    private val assets = File("src/main/assets/fonts")
    private val entries = StudioFonts.parse(File(assets, "catalogue.json").readText())
    private fun cat(id: String) = entries.first { it.id == id }

    @Test fun everyFileIsThereWithItsPinnedHash() {
        val json = org.json.JSONObject(File(assets, "catalogue.json").readText()).getJSONArray("fonts")
        for (i in 0 until json.length()) {
            val o = json.getJSONObject(i)
            val files = o.optJSONObject("files") ?: continue
            val hashes = o.getJSONObject("sha256")
            for (style in files.keys()) {
                val f = File(assets, files.getString(style))
                assertTrue("missing ${f.path}", f.isFile)
                val sha = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
                assertEquals(f.path, hashes.getString(style), sha)
            }
            // Every family carries its licence.
            File(assets, files.getString("regular")).parentFile?.let { assertTrue("no licence beside ${it.name}", File(it, "OFL.txt").isFile) }
        }
    }

    @Test fun everyLanguageDefaultDrawsItsScript() {
        for (lang in Lang.entries) for (role in listOf(FontRole.DIALOGUE, FontRole.SFX)) {
            val id = StudioFonts.defaultId(lang, role)
            if (id.startsWith(FontEntry.SYS)) continue // the phone's Noto draws every script
            val f = cat(id)
            assertTrue("$id cannot draw ${lang.name}", StudioFonts.scriptOf(lang) in f.scripts)
        }
    }

    @Test fun rolesAndVariationsAreRead() {
        assertEquals(FontRole.SFX, cat("cat:bangers").role)
        assertEquals("'wght' 800", cat("cat:shantell_sans_extrabold").variation)
        assertEquals("'wght' 700", cat("cat:shantell_sans").boldVariation)
        assertEquals("coming_soon", cat("cat:coming_soon").res)
        assertTrue(cat("cat:comic_neue").files.keys.containsAll(listOf("regular", "bold", "italic", "boldItalic")))
    }

    @Test fun searchIgnoresCaseAndAccentsAndNeedsEveryWord() {
        assertTrue(StudioFonts.matches("COMIC", "Comic Neue", "handwritten"))
        assertTrue(StudioFonts.matches("comic hand", "Comic Neue", "handwritten"))
        assertFalse(StudioFonts.matches("comic horror", "Comic Neue", "handwritten"))
        assertTrue(StudioFonts.matches("tieng", "Tiếng Việt"))
        assertTrue(StudioFonts.matches("viet", "Tiếng Việt"))
        assertTrue(StudioFonts.matches("", "anything"))
    }

    /** The reader's fonts from before the catalogue: a saved choice must still name a font that is there. */
    @Test fun everyEarlierReaderFontIsInTheCatalogue() {
        for (f in com.smnexstudio.panelglass.core.model.BubbleFont.entries) assertTrue(f.fontId, entries.any { it.id == f.fontId })
    }
}
