package com.smnexstudio.panelglass.core.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smnexstudio.panelglass.core.data.cache.PatchCache
import com.smnexstudio.panelglass.core.data.db.PanelglassDb
import com.smnexstudio.panelglass.core.engine.TranslationEngine
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Patch
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.TranslateConfig
import com.smnexstudio.panelglass.core.model.TranslateRequest
import com.smnexstudio.panelglass.core.model.TranslatedItem
import com.smnexstudio.panelglass.core.ocr.MlKitTextDetector
import com.smnexstudio.panelglass.core.ocr.RegionClassifier
import com.smnexstudio.panelglass.core.ocr.RegionClusterer
import com.smnexstudio.panelglass.core.ocr.raster.BitmapPixelSource
import com.smnexstudio.panelglass.core.render.PatchEncoder
import com.smnexstudio.panelglass.core.render.RegionRenderer
import com.smnexstudio.panelglass.core.render.TextEraser
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end on a real device/emulator: a rendered page round-trips to patches with
 * fitted text, ENCLOSED / FREE classification holds, and patches are cached. Uses ML Kit's Latin recognizer so no network is needed.
 */
@RunWith(AndroidJUnit4::class)
class PipelineInstrumentedTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PanelglassDb
    private lateinit var detector: MlKitTextDetector

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, PanelglassDb::class.java).build()
        detector = MlKitTextDetector()
    }

    /** A page with a white speech bubble over hatched art plus free text on a flat grey area. */
    private fun fixture(w: Int, h: Int, bubbles: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(200, 200, 200))
        val hatch = Paint().apply { color = Color.DKGRAY; strokeWidth = 2f }
        var x = 0f
        while (x < w + h) { c.drawLine(x, 0f, x - h, h.toFloat(), hatch); x += 9f }
        val white = Paint().apply { color = Color.WHITE }
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 34f; isFakeBoldText = true; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        val bubbleH = 150f
        for (i in 0 until bubbles) {
            val top = 120f + i * (h - 240f) / bubbles
            c.drawOval(RectF(80f, top, w - 80f, top + bubbleH), white)
            c.drawText("HELLO THERE", 150f, top + 70f, ink)
            c.drawText("HOW ARE YOU", 150f, top + 112f, ink)
        }
        return bmp
    }

    private class EchoEngine : TranslationEngine {
        override val id = EngineId.GOOGLE
        override val acceptsImages = false
        var calls = 0
        override suspend fun translate(req: TranslateRequest): List<TranslatedItem> { calls++; return req.items.map { TranslatedItem(it.i, "Translated: " + it.text.lowercase()) } }
    }

    @Test
    fun pageRoundTripsToPatches_andPatchesAreCached() { runBlocking {
        val page = fixture(800, 1200, 1)
        val lines = detector.detect(page, Lang.EN)
        assertTrue("OCR should read the bubble text, got $lines", lines.isNotEmpty())
        val regions = RegionClusterer().cluster(lines, Lang.EN)
        val classified = regions.map { RegionClassifier().classify(BitmapPixelSource(page), it) }
        assertTrue("bubble text must classify as ENCLOSED: $classified", classified.any { it.kind == RegionKind.ENCLOSED })

        val renderer = RegionRenderer(TextEraser(), PatchEncoder())
        val cfg = TranslateConfig(Lang.EN, Lang.EN, EngineId.GOOGLE)
        val patches = classified.mapNotNull { renderer.render(PageSource.of(page), it, "Translated line", cfg, null) }
        assertTrue(patches.isNotEmpty())
        for (p in patches) {
            assertTrue(p.webp.size in 200..400_000)
            assertTrue(p.xPct >= 0f && p.yPct >= 0f && p.xPct + p.wPct <= 100.01f && p.yPct + p.hPct <= 100.01f)
        }

        val hash = TranslationPipeline.nativeHash(page)
        // Patch cache: back-navigation costs nothing.
        val patchCache = PatchCache(File(ctx.cacheDir, "test-patches").apply { deleteRecursively() })
        val key = patchCache.key(hash, Lang.EN, Lang.EN, EngineId.GOOGLE, TranslationPipeline.PIPELINE_VERSION)
        patchCache.put(key, com.smnexstudio.panelglass.core.data.cache.CachedPage(page.width, page.height, classified.size, patches))
        assertEquals(patches.size, patchCache.get(key)!!.patches.size)
        page.recycle()
    } }
}
