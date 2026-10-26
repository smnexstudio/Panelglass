package com.smnexstudio.panelglass.feature.browser

import androidx.compose.ui.graphics.ImageBitmap
import com.smnexstudio.panelglass.core.data.cache.SavedRegion
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.TranslateConfig
import com.smnexstudio.panelglass.core.pipeline.RegionOfInterest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/** The saved translations: what may be kept, and how a record comes back onto the screen. */
class PatchRetentionTest {
    private val fingerprint = "v1|ja|en|CUSTOM|my-model|auto|abcd1234|15"
    private val otherFingerprint = "v1|en|ja|GEMINI|other|auto|99999999|15"

    /** The image: 1000 CSS px wide, 2000 tall. */
    private val img = IntRect(0, 0, 1000, 2000)
    private val layout = ImageLayout(listOf(img), listOf("p.jpg"), 0, 0, 1f)

    private fun fakeImage(w: Int = 200, h: Int = 100): ImageBitmap = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(ImageBitmap::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getWidth" -> w
            "getHeight" -> h
            else -> null
        }
    } as ImageBitmap

    /** A patch anchored to the image, with its box in CSS pixels from the image's corner. */
    private fun patch(dx: Float, dy: Float, w: Float = 200f, h: Float = 100f, key: String = "p.jpg") =
        ScreenPatch(fakeImage(), dx, dy, w, h, Anchor(key, dx, dy, 1000f, 2000f))

    private fun decodeOk(bytes: ByteArray): ImageBitmap = fakeImage()

    @Test
    fun aBlankKeyMayNotBeKept() {
        assertFalse(PatchRetention.retainable(""))
        assertFalse(PatchRetention.retainable("   "))
    }

    @Test
    fun aCanvasKeyMayNotBeKept() {
        // A paged canvas reader reuses the key for every page: a record from one page would land on another.
        assertFalse(PatchRetention.retainable("canvas#0"))
        assertFalse(PatchRetention.retainable("canvas#12"))
    }

    @Test
    fun aDataKeyMayNotBeKept() {
        assertFalse(PatchRetention.retainable("data:image/png;base64,AAAA"))
    }

    @Test
    fun anOrdinaryImageKeyMayBeKept() {
        assertTrue(PatchRetention.retainable("https://example.com/p.jpg"))
        assertTrue(PatchRetention.retainable("blob:https://example.com/1234"))
    }

    @Test
    fun aPatchWithoutAnAnchorIsNotSaved() {
        val p = ScreenPatch(fakeImage(), 0f, 0f, 200f, 100f, null)
        assertNull(PatchRetention.toSaved(p, fingerprint, 1000L, byteArrayOf(1)))
    }

    @Test
    fun aPatchOnACanvasIsNotSaved() {
        assertNull(PatchRetention.toSaved(patch(0f, 0f, key = "canvas#0"), fingerprint, 1000L, byteArrayOf(1)))
    }

    @Test
    fun aPatchWithoutAnImageSizeIsNotSaved() {
        val p = ScreenPatch(fakeImage(), 0f, 0f, 200f, 100f, Anchor("p.jpg", 0f, 0f, 0f, 0f))
        assertNull(PatchRetention.toSaved(p, fingerprint, 1000L, byteArrayOf(1)))
    }

    @Test
    fun aSavedPatchComesBackOnTheSameRect() {
        val (key, rec) = PatchRetention.toSaved(patch(100f, 200f), fingerprint, 1000L, byteArrayOf(1))!!
        assertEquals("p.jpg", key)
        val back = PatchRetention.restore(listOf(rec), key, layout, 1000, 500, fingerprint, emptyList(), ::decodeOk)
        assertEquals(1, back.size)
        assertEquals(100f, back[0].left, 1e-3f)
        assertEquals(200f, back[0].top, 1e-3f)
        assertEquals(200f, back[0].width, 1e-3f)
        assertEquals(100f, back[0].height, 1e-3f)
    }

    @Test
    fun aRestoredPatchSurvivesALayoutShift() {
        val (_, rec) = PatchRetention.toSaved(patch(100f, 200f), fingerprint, 1000L, byteArrayOf(1))!!
        // The page scrolled by 94 px and the image moved with it.
        val moved = ImageLayout(listOf(img), listOf("p.jpg"), 0, 94, 1f)
        val back = PatchRetention.restore(listOf(rec), "p.jpg", moved, 1000, 500, fingerprint, emptyList(), ::decodeOk)
        assertEquals(1, back.size)
        assertEquals(100f, back[0].left, 1e-3f)
        assertEquals(200f + 94f, back[0].top, 1e-3f)
    }

    @Test
    fun aRestoredPatchKeepsItsBoxAtAnotherZoom() {
        val (_, rec) = PatchRetention.toSaved(patch(100f, 200f), fingerprint, 1000L, byteArrayOf(1))!!
        // The same image at twice the zoom: the CSS rect is the same, the page pixels are not.
        val zoomed = ImageLayout(listOf(IntRect(0, 0, 2000, 4000)), listOf("p.jpg"), 0, 0, 2f)
        val back = PatchRetention.restore(listOf(rec), "p.jpg", zoomed, 2000, 1000, fingerprint, emptyList(), ::decodeOk)
        assertEquals(1, back.size)
        assertEquals(100f, back[0].left, 1e-3f)
        assertEquals(200f, back[0].top, 1e-3f)
        assertEquals(200f, back[0].width, 1e-3f)
    }

    @Test
    fun aRestoredPatchKeepsItsBoxAfterARotation() {
        val (_, rec) = PatchRetention.toSaved(patch(100f, 200f), fingerprint, 1000L, byteArrayOf(1))!!
        // The image is now narrower: the box is a fraction of the image, so it stays proportional.
        val rotated = ImageLayout(listOf(IntRect(0, 0, 500, 1000)), listOf("p.jpg"), 0, 0, 1f)
        val back = PatchRetention.restore(listOf(rec), "p.jpg", rotated, 1000, 500, fingerprint, emptyList(), ::decodeOk)
        assertEquals(1, back.size)
        assertEquals(50f, back[0].left, 1e-3f)
        assertEquals(100f, back[0].top, 1e-3f)
        assertEquals(100f, back[0].width, 1e-3f)
    }

    @Test
    fun aRecordOfADifferentShapeIsSkipped() {
        val (_, rec) = PatchRetention.toSaved(patch(100f, 200f), fingerprint, 1000L, byteArrayOf(1))!!
        // The image is now a different shape: a different picture.
        val reshaped = ImageLayout(listOf(IntRect(0, 0, 1000, 1000)), listOf("p.jpg"), 0, 0, 1f)
        val back = PatchRetention.restore(listOf(rec), "p.jpg", reshaped, 1000, 500, fingerprint, emptyList(), ::decodeOk)
        assertTrue(back.isEmpty())
    }

    @Test
    fun aRecordMadeWithOtherSettingsIsSkipped() {
        val (_, rec) = PatchRetention.toSaved(patch(100f, 200f), fingerprint, 1000L, byteArrayOf(1))!!
        val back = PatchRetention.restore(listOf(rec), "p.jpg", layout, 1000, 500, otherFingerprint, emptyList(), ::decodeOk)
        assertTrue(back.isEmpty())
    }

    @Test
    fun aRecordOfAnImageThatIsNotOnScreenIsEmpty() {
        val (_, rec) = PatchRetention.toSaved(patch(100f, 200f), fingerprint, 1000L, byteArrayOf(1))!!
        val other = ImageLayout(listOf(IntRect(0, 0, 1000, 2000)), listOf("other.jpg"), 0, 0, 1f)
        assertTrue(PatchRetention.restore(listOf(rec), "p.jpg", other, 1000, 500, fingerprint, emptyList(), ::decodeOk).isEmpty())
    }

    @Test
    fun aRecordOutsideTheMarginIsNotRestored() {
        // The bubble is far below the viewport: it is saved, but not now.
        val (_, rec) = PatchRetention.toSaved(patch(100f, 5000f), fingerprint, 1000L, byteArrayOf(1))!!
        val back = PatchRetention.restore(listOf(rec), "p.jpg", layout, 1000, 500, fingerprint, emptyList(), ::decodeOk)
        assertTrue(back.isEmpty())
    }

    @Test
    fun aRecordInsideTheMarginIsRestored() {
        val (_, rec) = PatchRetention.toSaved(patch(100f, 1200f), fingerprint, 1000L, byteArrayOf(1))!!
        val back = PatchRetention.restore(listOf(rec), "p.jpg", layout, 1000, 500, fingerprint, emptyList(), ::decodeOk)
        assertEquals(1, back.size)
    }

    @Test
    fun aRecordThatDuplicatesWhatIsOnScreenIsSkipped() {
        val (_, rec) = PatchRetention.toSaved(patch(100f, 200f), fingerprint, 1000L, byteArrayOf(1))!!
        val existing = listOf(patch(100f, 200f))
        val back = PatchRetention.restore(listOf(rec), "p.jpg", layout, 1000, 500, fingerprint, existing, ::decodeOk)
        assertTrue(back.isEmpty())
    }

    @Test
    fun twoBubblesOfTheSameSizeAreBothRestored() {
        // Identity is geometry, never text: two bubbles of the same shape in different places are two bubbles.
        val (_, a) = PatchRetention.toSaved(patch(100f, 200f), fingerprint, 1000L, byteArrayOf(1))!!
        val (_, b) = PatchRetention.toSaved(patch(100f, 600f), fingerprint, 1000L, byteArrayOf(2))!!
        val back = PatchRetention.restore(listOf(a, b), "p.jpg", layout, 1000, 500, fingerprint, emptyList(), ::decodeOk)
        assertEquals(2, back.size)
        assertEquals(200f, back[0].top, 1e-3f)
        assertEquals(600f, back[1].top, 1e-3f)
    }

    @Test
    fun aRecordThatCannotBeDecodedIsSkipped() {
        val (_, rec) = PatchRetention.toSaved(patch(100f, 200f), fingerprint, 1000L, byteArrayOf(1))!!
        val back = PatchRetention.restore(listOf(rec), "p.jpg", layout, 1000, 500, fingerprint, emptyList(), { null })
        assertTrue(back.isEmpty())
    }

    @Test
    fun theRestoreMarginIsThreeQuartersOfThePruneDistance() {
        assertEquals(2250, PatchRetention.restoreMargin(3, 1000))
        assertEquals(750, PatchRetention.restoreMargin(1, 1000))
    }

    @Test
    fun aRestoredPatchAtTheMarginSurvivesThePruning() {
        // The point of the margin: a patch restored at the edge of the window is not pruned again at once.
        val screens = 3
        val viewportH = 1000
        val margin = PatchRetention.restoreMargin(screens, viewportH)
        val (_, rec) = PatchRetention.toSaved(patch(100f, viewportH + margin - 200f), fingerprint, 1000L, byteArrayOf(1))!!
        val back = PatchRetention.restore(listOf(rec), "p.jpg", layout, viewportH, margin, fingerprint, emptyList(), ::decodeOk)
        assertEquals(1, back.size)
        val kept = PatchWindow.prune(back, 0, viewportH, 1f, screens, Long.MAX_VALUE).kept
        assertEquals(1, kept.size)
    }

    @Test
    fun theFingerprintHoldsTheSettingsAndNothingElse() {
        val cfg = TranslateConfig(Lang.JA, Lang.EN, EngineId.CUSTOM, fontId = "comic")
        val fp = PatchRetention.fingerprint(cfg, "my-model", "abcdef1234567890", 15)
        assertEquals("v1|ja|en|CUSTOM|my-model|comic|abcdef12|15", fp)
        // Nothing secret, nothing that changes between two translations of the same page.
        assertFalse(fp.contains("key"))
        assertFalse(fp.contains("http"))
        assertFalse(fp.contains("timestamp"))
    }

    @Test
    fun theFingerprintChangesWithTheModelAndTheFont() {
        val cfg = TranslateConfig(Lang.JA, Lang.EN, EngineId.CUSTOM, fontId = "comic")
        val a = PatchRetention.fingerprint(cfg, "model-a", "abcdef1234567890", 15)
        val b = PatchRetention.fingerprint(cfg, "model-b", "abcdef1234567890", 15)
        val c = PatchRetention.fingerprint(cfg.copy(fontId = "other"), "model-a", "abcdef1234567890", 15)
        assertTrue(a != b)
        assertTrue(a != c)
    }

    /**
     * The promise the whole feature makes: a bubble that was translated and is now off the screen comes back with
     * no detector, no OCR and no engine call.
     */
    @Test
    fun aRestoredBubbleIsNeverDetectedAgain() {
        // Ten screens of bubbles on one tall image.
        val screens = 10
        val viewportH = 1000
        val tall = IntRect(0, 0, 1000, screens * viewportH)
        val all = ImageLayout(listOf(tall), listOf("p.jpg"), 0, 0, 1f)

        val records = ArrayList<SavedRegion>()
        val originals = ArrayList<ScreenPatch>()
        for (s in 0 until screens) {
            for (k in 0 until 3) {
                val top = s * viewportH + k * 300f
                val p = ScreenPatch(fakeImage(), 100f, top, 200f, 100f, Anchor("p.jpg", 100f, top, 1000f, screens * viewportH.toFloat()))
                originals += p
                val (_, rec) = PatchRetention.toSaved(p, fingerprint, 1000L, byteArrayOf(1))!!
                records += rec
            }
        }

        // The reader is at the tenth screen: everything before it was pruned.
        val atTen = ImageLayout(listOf(tall), listOf("p.jpg"), 0, 9 * viewportH, 1f)
        val pruned = PatchWindow.prune(originals, 9 * viewportH, viewportH, 1f, 3, Long.MAX_VALUE).kept
        assertTrue(pruned.size < originals.size)

        // Coming back to the first screen restores the bubbles of the screens around it.
        val atOne = ImageLayout(listOf(tall), listOf("p.jpg"), 0, 0, 1f)
        val restored = PatchRetention.restore(
            records, "p.jpg", atOne, viewportH, PatchRetention.restoreMargin(3, viewportH), fingerprint, emptyList(), ::decodeOk,
        )
        assertTrue(restored.size >= 3, "the first screen's bubbles were not restored")

        // The pipeline's own rule: a detection inside a restored area is not allowed, so it is never read and the
        // engine is never called for it. The bubbles of the FIRST screen are the ones that matter.
        val firstScreen = originals.filter { it.top < viewportH.toFloat() }
        assertEquals(3, firstScreen.size)
        val done = restored.map { it.rectAt(1f) }
        val roi = RegionOfInterest(include = listOf(tall), done = done)
        var engineCalls = 0
        for (p in firstScreen) {
            if (roi.allows(p.rectAt(1f))) engineCalls++
        }
        assertEquals(0, engineCalls, "a restored bubble was detected again: the engine would be called")

        // And with nothing restored, the very same bubbles would be detected: the restore is what stops them.
        val emptyRoi = RegionOfInterest(include = listOf(tall), done = emptyList())
        var callsWithoutRestore = 0
        for (p in firstScreen) {
            if (emptyRoi.allows(p.rectAt(1f))) callsWithoutRestore++
        }
        assertEquals(3, callsWithoutRestore, "the bubbles were never detected in the first place")
    }
}
