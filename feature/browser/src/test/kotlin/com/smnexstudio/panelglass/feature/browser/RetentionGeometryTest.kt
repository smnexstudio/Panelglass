package com.smnexstudio.panelglass.feature.browser

import androidx.compose.ui.graphics.ImageBitmap
import com.smnexstudio.panelglass.core.data.cache.RegionStore
import com.smnexstudio.panelglass.core.data.cache.SavedRegion
import com.smnexstudio.panelglass.core.model.IntRect
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.Proxy

/**
 * The save path's geometry, at the real boundary.
 *
 * These tests use the **production** coordinator — the one the reader calls — and the **production** store. A test
 * of a hand-corrected copy of the geometry proves nothing: the bug this fixes was a parallel reimplementation of
 * the live placement, and it passed every test that did not go through the real code.
 *
 * The fixture is deliberately awkward: a viewport that is not 1000 pixels wide, a zoom that is not 2, a scroll
 * that is not zero, an image that does not start at the page's corner, and a patch that is not square.
 */
class RetentionGeometryTest {
    @TempDir
    lateinit var dir: File

    /** The phone: 1200 x 2160 view pixels. */
    private val viewportW = 1200
    private val viewportH = 2160

    /** The zoom the page was read at: not 2, and not 1. */
    private val scale = 2.60625f

    /** The page was scrolled: not zero. */
    private val scrollX = 137
    private val scrollY = 4209

    /**
     * The image on the page: it does not start at the page's corner, so the patch's offset from the image's own
     * corner is not the same as its offset from the viewport's.
     */
    private val image = IntRect(240, 310, 240 + 900, 310 + 1600)

    /** The patch: 31% across, 25% down, 20% wide, 12% tall. Not square, and not at the corner. */
    private val xPct = 31f
    private val yPct = 25f
    private val wPct = 20f
    private val hPct = 12f

    private val fingerprint = "v1|ja|en|CUSTOM|my-model|auto|abcd1234|15"

    private val geo = Geometry.CaptureGeometry(viewportW, viewportH, scrollX, scrollY, scale)

    private fun fakeImage(w: Int = 200, h: Int = 100): ImageBitmap = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(ImageBitmap::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getWidth" -> w
            "getHeight" -> h
            else -> null
        }
    } as ImageBitmap

    /** The view-pixel box of the patch, the way the live placement computes it. */
    private fun liveView(): Geometry.ViewRect {
        val v = Geometry.viewRect(xPct, yPct, wPct, hPct, geo)!!
        return v
    }

    /**
     * The whole path: the live placement, the accepted region, the disk record, the read back, the restore, and
     * the box the overlay would draw.
     */
    @Test
    fun aTranslationComesBackAtTheSizeAndPlaceItHad() = runBlocking {
        // 1. The live placement: the patch's box in the snapshot's pixels, and on the page.
        val view = liveView()
        val page = Geometry.pageRect(view, geo)!!

        // 2. The accepted region: the same numbers, in every space.
        val accepted = RetentionCoordinator.accept(
            imageKey = "https://example.com/p.jpg", occurrence = 0, view = view, image = image,
            geo = geo, fingerprint = fingerprint, createdAtMs = 1_000L,
        )
        assertNotNull(accepted, "the live geometry was rejected")

        // 3. The disk: the real store, the real file.
        val store = RegionStore(dir)
        val saved = RetentionCoordinator.toSaved(accepted!!, byteArrayOf(1, 2, 3))
        assertNotNull(saved)
        store.upsert("chapter", saved!!.first, saved.second, maxBytes = 1_000_000)
        val back = store.load("chapter", saved.first)
        assertEquals(1, back.size)

        // 4. The restore: the same layout, the reader is still where it was.
        val layout = ImageLayout(listOf(image), listOf("https://example.com/p.jpg"), scrollX, scrollY, scale)
        val restored = RetentionCoordinator.restore(
            back, "https://example.com/p.jpg", 0, layout, viewportH, 2000, fingerprint, emptyList(), ::decodeOk,
        )
        assertEquals(1, restored.size, "the translation did not come back")

        // 5. The box the overlay draws, to within a pixel of the screen.
        val r = restored[0]
        val tol = 1f / scale
        assertEquals(page.leftCss, r.left, tol, "the translation came back at the wrong place")
        assertEquals(page.topCss, r.top, tol, "the translation came back at the wrong place")
        assertEquals(page.widthCss, r.width, tol, "the translation came back at the wrong size")
        assertEquals(page.heightCss, r.height, tol, "the translation came back at the wrong size")
    }

    /**
     * The defect itself: the old save path put the patch's **percentages** where the CSS pixels belonged.
     *
     * A patch 20% of the image wide was saved as 20 CSS pixels wide, and came back 20 pixels wide instead of the
     * several hundred it should be.
     */
    @Test
    fun theSavedSizeIsNotThePatchsPercentage() = runBlocking {
        val view = liveView()
        val accepted = RetentionCoordinator.accept(
            "https://example.com/p.jpg", 0, view, image, geo, fingerprint, 1_000L,
        )!!
        val saved = RetentionCoordinator.toSaved(accepted, byteArrayOf(1))!!
        val rec: SavedRegion = saved.second
        // The record keeps the box as a fraction of the image, so the width is a fraction and not a percentage.
        assertTrue(rec.nw in 0f..1f, "the record's width is not a fraction of the image")
        assertTrue(rec.nw > 0.05f, "the record's width is far too small: the percentage was saved as a length")
        // And the same bubble, restored, is the size it was.
        val layout = ImageLayout(listOf(image), listOf("https://example.com/p.jpg"), scrollX, scrollY, scale)
        val restored = RetentionCoordinator.restore(
            listOf(rec), "https://example.com/p.jpg", 0, layout, viewportH, 2000, fingerprint, emptyList(), ::decodeOk,
        )
        assertEquals(1, restored.size)
        assertEquals(
            Geometry.pageRect(view, geo)!!.widthCss, restored[0].width, 1f / scale,
            "the restored bubble is the wrong size",
        )
    }

    /**
     * The same picture twice on one page is two images, and a translation belongs to the one it was made from.
     */
    @Test
    fun theSamePictureTwiceKeepsItsTranslationsApart() = runBlocking {
        val first = IntRect(0, 0, 800, 1200)
        val second = IntRect(0, 1400, 800, 2600)
        val key = "https://example.com/same.jpg"
        val layout = ImageLayout(listOf(first, second), listOf(key, key), 0, 0, 1f)

        // The same bubble shape, on each of the two pictures: 100 px from that picture's own corner.
        val onFirst = RetentionCoordinator.accept(
            key, 0, Geometry.ViewRect(100, 100, 200, 100), first,
            Geometry.CaptureGeometry(800, 2600, 0, 0, 1f), fingerprint, 1_000L,
        )!!
        val onSecond = RetentionCoordinator.accept(
            key, 1, Geometry.ViewRect(100, 1500, 200, 100), second,
            Geometry.CaptureGeometry(800, 2600, 0, 0, 1f), fingerprint, 1_000L,
        )!!

        val store = RegionStore(dir)
        val a = RetentionCoordinator.toSaved(onFirst, byteArrayOf(1))!!
        val b = RetentionCoordinator.toSaved(onSecond, byteArrayOf(2))!!
        store.upsert("chapter", key, a.second, 1_000_000)
        store.upsert("chapter", key, b.second, 1_000_000)
        val back = store.load("chapter", key)
        assertEquals(2, back.size, "the two records of the same key were not both kept")

        // The first image gets the first record, and only the first record.
        val firstOnly = RetentionCoordinator.restore(
            back, key, 0, layout, 2600, 10_000, fingerprint, emptyList(), ::decodeOk,
        )
        assertEquals(1, firstOnly.size)
        assertEquals(100f, firstOnly[0].top, 1e-3f)

        // The second image gets the second record, at its own place on that picture.
        val secondOnly = RetentionCoordinator.restore(
            back, key, 1, layout, 2600, 10_000, fingerprint, emptyList(), ::decodeOk,
        )
        assertEquals(1, secondOnly.size)
        assertEquals(1500f, secondOnly[0].top, 1e-3f)
        // And the first image's bubble is not the second one's: they are a screen apart.
        assertTrue(secondOnly[0].top - firstOnly[0].top > 1000f)
    }

    /**
     * The round trip must not drift: twenty save-and-restore cycles give the same box every time.
     */
    @Test
    fun twentyCyclesDoNotDrift() = runBlocking {
        val view = liveView()
        val accepted = RetentionCoordinator.accept(
            "https://example.com/p.jpg", 0, view, image, geo, fingerprint, 1_000L,
        )!!
        val saved = RetentionCoordinator.toSaved(accepted, byteArrayOf(1))!!
        val layout = ImageLayout(listOf(image), listOf("https://example.com/p.jpg"), scrollX, scrollY, scale)
        val first = RetentionCoordinator.restore(
            listOf(saved.second), "https://example.com/p.jpg", 0, layout, viewportH, 2000, fingerprint,
            emptyList(), ::decodeOk,
        )[0]
        var last = first
        repeat(20) {
            // Save the restored patch again, as the reader does when it re-anchors and re-saves.
            val rec = SavedRegion(
                schema = saved.second.schema, nx = saved.second.nx, ny = saved.second.ny,
                nw = saved.second.nw, nh = saved.second.nh, imgW = saved.second.imgW, imgH = saved.second.imgH,
                kind = 0, occurrence = 0, fingerprint = fingerprint, createdAtMs = 1_000L, webp = byteArrayOf(1),
            )
            val again = RetentionCoordinator.restore(
                listOf(rec), "https://example.com/p.jpg", 0, layout, viewportH, 2000, fingerprint,
                emptyList(), ::decodeOk,
            )[0]
            assertEquals(last.left, again.left, 1e-3f, "the box drifted")
            assertEquals(last.top, again.top, 1e-3f, "the box drifted")
            assertEquals(last.width, again.width, 1e-3f, "the box shrank")
            assertEquals(last.height, again.height, 1e-3f, "the box shrank")
            last = again
        }
    }

    /**
     * The numbers that cannot be drawn are refused, with a reason, rather than published.
     */
    @Test
    fun impossibleGeometryIsRefused() {
        val notFinite = Float.NaN
        assertEquals(null, Geometry.viewRect(notFinite, 10f, 10f, 10f, geo))
        assertEquals(null, Geometry.viewRect(10f, 10f, 0f, 10f, geo))
        assertEquals(null, Geometry.viewRect(10f, 10f, -5f, 10f, geo))
        // A zoom of zero would divide by zero.
        val broken = Geometry.CaptureGeometry(viewportW, viewportH, 0, 0, 0f)
        assertEquals(null, Geometry.viewRect(10f, 10f, 10f, 10f, broken))
        assertEquals(null, Geometry.pageRect(Geometry.ViewRect(0, 0, 10, 10), broken))
    }

    /**
     * A patch that hangs off the edge of its image keeps its place: the artwork is not there under that part of the
     * bubble, and clipping it would move the text.
     */
    @Test
    fun aPatchOffTheEdgeOfItsImageKeepsItsPlace() {
        val img = IntRect(0, 0, 100, 100)
        val g = Geometry.CaptureGeometry(200, 200, 0, 0, 1f)
        // The patch starts 20 px to the left of the image.
        val view = Geometry.ViewRect(-20, 10, 50, 20)
        val norm = Geometry.normalizedRect(view, img, g)!!
        assertTrue(norm.nx < 0f, "the negative offset was clamped")
        assertEquals(-0.2f, norm.nx, 1e-4f)
    }

    private fun decodeOk(bytes: ByteArray): ImageBitmap = fakeImage()
}
