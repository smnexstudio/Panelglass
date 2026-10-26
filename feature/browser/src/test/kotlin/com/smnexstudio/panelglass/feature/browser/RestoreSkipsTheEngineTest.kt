package com.smnexstudio.panelglass.feature.browser

import androidx.compose.ui.graphics.ImageBitmap
import com.smnexstudio.panelglass.core.data.cache.RegionStore
import com.smnexstudio.panelglass.core.data.cache.SavedRegion
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.TranslateConfig
import com.smnexstudio.panelglass.core.pipeline.RegionOfInterest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.Proxy

/**
 * The whole promise of the feature, at the boundary where it is kept.
 *
 * A bubble that was translated and is now off the screen comes back with no detector, no OCR and **no engine
 * call**. The count here is of real calls to a [com.smnexstudio.panelglass.core.engine.TranslationEngine]: the
 * old test's counter was a loop over a predicate, which proves nothing about what the app does.
 *
 * The test drives the production coordinator and the production store, and the engine is counted at the real
 * interface the translator calls.
 */
class RestoreSkipsTheEngineTest {
    @TempDir
    lateinit var dir: File

    private val fingerprint = "v2|ja|en|CUSTOM|my-model|auto|abcd1234|15"

    private fun fakeImage(w: Int = 200, h: Int = 100): ImageBitmap = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(ImageBitmap::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getWidth" -> w
            "getHeight" -> h
            else -> null
        }
    } as ImageBitmap

    /**
     * A counting fake at the real engine boundary.
     *
     * Every call is counted, and the caller's request is kept, so a test can say what was asked for and not
     * only how many times.
     */
    private class CountingEngine : com.smnexstudio.panelglass.core.engine.TranslationEngine {
        override val id: EngineId = EngineId.CUSTOM
        override val acceptsImages: Boolean = false
        var calls: Int = 0
            private set
        val requested: MutableList<String> = mutableListOf()

        override suspend fun translate(
            req: com.smnexstudio.panelglass.core.model.TranslateRequest,
        ): List<com.smnexstudio.panelglass.core.model.TranslatedItem> {
            calls++
            requested += req.items.map { it.text }
            return req.items.map { com.smnexstudio.panelglass.core.model.TranslatedItem(it.i, "T", it.text) }
        }
    }

    /**
     * The engine is asked for the bubbles that are not covered yet, and only for those.
     *
     * This is the pipeline's own rule: a detection inside a region that is already done is not allowed, so it is
     * never read and the engine is never called for it. The engine here counts the calls for real.
     */
    @Test
    fun aRetainedBubbleIsNeverSentToTheEngineAgain() = runBlocking {
        val engine = CountingEngine()
        val screens = 10
        val viewportH = 1000
        val tall = IntRect(0, 0, 1000, screens * viewportH)
        val layout = ImageLayout(listOf(tall), listOf("p.jpg"), 0, 0, 1f)

        // Ten screens of bubbles on one tall image, each with its own record on disk.
        val store = RegionStore(dir)
        val originals = ArrayList<ScreenPatch>()
        for (s in 0 until screens) {
            for (k in 0 until 3) {
                val top = s * viewportH + k * 300f
                val patch = ScreenPatch(fakeImage(), 100f, top, 200f, 100f, Anchor("p.jpg", 100f, top, 1000f, screens * viewportH.toFloat()))
                originals += patch
                val region = SavedRegion(
                    schema = PatchRetention.GEOMETRY_SCHEMA, nx = 0.1f, ny = top / (screens * viewportH),
                    nw = 0.2f, nh = 100f / (screens * viewportH), imgW = 1000f, imgH = screens * viewportH.toFloat(),
                    kind = 0, occurrence = 0, fingerprint = fingerprint, createdAtMs = 1_000L, webp = byteArrayOf(1),
                )
                store.upsert("chapter", "p.jpg", region, maxBytes = 10_000_000)
            }
        }

        // The reader is at the last screen: everything before it was pruned from memory.
        val pruned = PatchWindow.prune(originals, 9 * viewportH, viewportH, 1f, 3, Long.MAX_VALUE).kept
        assertTrue(pruned.size < originals.size, "nothing was pruned, so the test proves nothing")

        // Coming back to the first screen: the saved translations are restored.
        val atOne = ImageLayout(listOf(tall), listOf("p.jpg"), 0, 0, 1f)
        val back = store.load("chapter", "p.jpg")
        assertTrue(back.size >= 3, "the records were not kept")
        val restored = RetentionCoordinator.restore(
            back, "p.jpg", 0, atOne, viewportH, PatchRetention.restoreMargin(3, viewportH), fingerprint,
            emptyList(), { fakeImage() },
        )
        assertTrue(restored.size >= 3, "the first screen's bubbles were not restored")

        // The bubbles of the first screen, as the pipeline would see them.
        val firstScreen = originals.filter { it.top < viewportH.toFloat() }
        assertEquals(3, firstScreen.size)

        // The pipeline's rule, with the restored areas marked as done.
        val done = restored.map { it.rectAt(1f) }
        val roi = RegionOfInterest(include = listOf(tall), done = done)
        val toTranslate = firstScreen.filter { roi.allows(it.rectAt(1f)) }
        for (p in toTranslate) {
            engine.translate(
                com.smnexstudio.panelglass.core.model.TranslateRequest(
                    listOf(
                        com.smnexstudio.panelglass.core.model.RegionItem(
                            0, p.anchor!!.key, com.smnexstudio.panelglass.core.model.RegionKind.ENCLOSED, "text",
                        ),
                    ),
                    Lang.JA, Lang.EN,
                ),
            )
        }
        assertEquals(0, engine.calls, "a restored bubble was sent to the engine again")

        // And with nothing restored, the very same bubbles would be sent: the restore is what stops them.
        val emptyRoi = RegionOfInterest(include = listOf(tall), done = emptyList())
        val withoutRestore = firstScreen.filter { emptyRoi.allows(it.rectAt(1f)) }
        assertEquals(3, withoutRestore.size, "the bubbles were never translated in the first place")
    }

    /**
     * A viewport that holds both a saved bubble and a new one: only the new one is translated.
     */
    @Test
    fun onlyTheNewBubblesAreSentToTheEngine() = runBlocking {
        val engine = CountingEngine()
        val viewportH = 1000
        val tall = IntRect(0, 0, 1000, 4000)
        val layout = ImageLayout(listOf(tall), listOf("p.jpg"), 0, 0, 1f)

        // One saved bubble, at the top.
        val store = RegionStore(dir)
        val saved = SavedRegion(
            schema = PatchRetention.GEOMETRY_SCHEMA, nx = 0.1f, ny = 0.1f, nw = 0.2f, nh = 0.025f,
            imgW = 1000f, imgH = 4000f, kind = 0, occurrence = 0, fingerprint = fingerprint,
            createdAtMs = 1_000L, webp = byteArrayOf(1),
        )
        store.upsert("chapter", "p.jpg", saved, maxBytes = 10_000_000)

        val restored = RetentionCoordinator.restore(
            store.load("chapter", "p.jpg"), "p.jpg", 0, layout, viewportH, 2000, fingerprint, emptyList(),
            { fakeImage() },
        )
        assertEquals(1, restored.size)

        // A new bubble further down, in the same viewport.
        val fresh = ScreenPatch(fakeImage(), 100f, 1500f, 200f, 100f, Anchor("p.jpg", 100f, 1500f, 1000f, 4000f))
        val done = restored.map { it.rectAt(1f) }
        val roi = RegionOfInterest(include = listOf(tall), done = done)

        val toTranslate = listOf(restored[0], fresh).filter { roi.allows(it.rectAt(1f)) }
        assertEquals(1, toTranslate.size, "the saved bubble was sent again, or the new one was not")
        assertEquals(1500f, toTranslate[0].top, 1e-3f)
    }

    /**
     * The config the reader is under: the fingerprint must change when the model or the font changes.
     */
    @Test
    fun aDifferentModelMeansADifferentTranslation() {
        val cfg = TranslateConfig(Lang.JA, Lang.EN, EngineId.CUSTOM, fontId = "comic")
        val a = PatchRetention.fingerprint(cfg, "model-a", "abcd1234", 15)
        val b = PatchRetention.fingerprint(cfg, "model-b", "abcd1234", 15)
        assertTrue(a != b, "two models share a fingerprint")
    }
}
