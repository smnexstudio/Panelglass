package com.smnexstudio.panelglass.feature.browser

import androidx.compose.ui.graphics.ImageBitmap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * What the reader keeps in memory, as it has always kept it.
 *
 * These tests pin the rule the reader has used since the beginning: a patch is kept while it is within three
 * viewport heights above or below the viewport, and dropped otherwise. They must pass before and after the
 * retention work, except where the byte cap intentionally changes the outcome — and then the expected value
 * changes and the report says so.
 */
class PatchRetentionCharacterizationTest {
    private val viewportH = 1000

    /** A patch at page (content) pixel [top], [height] tall. */
    private fun patch(top: Int, height: Int = 100, width: Int = 200) =
        ScreenPatch(fakeImage, 0f, top.toFloat(), width.toFloat(), height.toFloat())

    private fun prune(vararg patches: ScreenPatch, screens: Int = 3, maxBytes: Long = Long.MAX_VALUE) =
        PatchWindow.prune(patches.toList(), 0, viewportH, 1f, screens, maxBytes)

    @Test
    fun aPatchOnScreenIsKept() {
        val p = patch(100)
        assertEquals(listOf(p), prune(p).kept)
    }

    @Test
    fun aPatchJustAboveTheWindowIsKept() {
        // The window is three viewport heights: 3000 px above and below.
        val p = patch(-2900)
        assertEquals(listOf(p), prune(p).kept)
    }

    @Test
    fun aPatchJustBelowTheWindowIsKept() {
        val p = patch(viewportH + 2900)
        assertEquals(listOf(p), prune(p).kept)
    }

    @Test
    fun aPatchFarAboveTheWindowIsDropped() {
        // The window reaches 3000 px above the viewport; a patch whose bottom is above that is too far.
        val p = patch(-3200)
        val r = prune(p)
        assertTrue(r.kept.isEmpty())
        assertEquals(1, r.droppedByDistance)
    }

    @Test
    fun aPatchFarBelowTheWindowIsDropped() {
        val p = patch(viewportH + 3100)
        val r = prune(p)
        assertTrue(r.kept.isEmpty())
        assertEquals(1, r.droppedByDistance)
    }

    @Test
    fun theWindowIsThreeScreens() {
        // The exact boundary: a patch whose bottom is exactly at the window's edge is kept, one pixel above is not.
        val atEdge = patch(-3000, height = 1)
        assertEquals(listOf(atEdge), prune(atEdge).kept)
        val aboveEdge = patch(-3100, height = 1)
        assertTrue(prune(aboveEdge).kept.isEmpty())
    }

    @Test
    fun severalPatchesAreKeptAndDroppedTogether() {
        val near = patch(100)
        val mid = patch(-1500)
        val far = patch(-5000)
        val r = prune(near, mid, far)
        assertEquals(listOf(near, mid), r.kept)
        assertEquals(1, r.droppedByDistance)
    }

    @Test
    fun theOrderOfTheKeptPatchesIsTheOrderTheyCameIn() {
        val a = patch(300)
        val b = patch(100)
        val c = patch(200)
        assertEquals(listOf(a, b, c), prune(a, b, c).kept)
    }

    @Test
    fun anEmptyListIsEmpty() {
        val r = prune()
        assertTrue(r.kept.isEmpty())
        assertEquals(0, r.droppedByDistance)
        assertEquals(0, r.droppedByBytes)
    }

    @Test
    fun oneScreenKeepsOnlyWhatIsOnScreen() {
        val onScreen = patch(100)
        val above = patch(-1500)
        val r = prune(onScreen, above, screens = 1)
        assertEquals(listOf(onScreen), r.kept)
        assertEquals(1, r.droppedByDistance)
    }

    // The fake bitmap: a proxy that answers the size the patch's rect needs, and nothing else.
    private val fakeImage: ImageBitmap = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(ImageBitmap::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getWidth" -> 200
            "getHeight" -> 100
            else -> null
        }
    } as ImageBitmap
}
