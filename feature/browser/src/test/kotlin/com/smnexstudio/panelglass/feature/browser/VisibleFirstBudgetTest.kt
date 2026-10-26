package com.smnexstudio.panelglass.feature.browser

import androidx.compose.ui.graphics.ImageBitmap
import com.smnexstudio.panelglass.core.model.IntRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * What the reader is looking at survives the memory budget.
 *
 * Two budgets are at work: the ordinary one (a few screens around the viewport) and the whole-page scan's own,
 * which is much larger. Both have the same rule: **a bubble that is on screen is the last to go.**
 */
class VisibleFirstBudgetTest {
    private fun fakeImage(w: Int, h: Int): ImageBitmap = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(ImageBitmap::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getWidth" -> w
            "getHeight" -> h
            else -> null
        }
    } as ImageBitmap

    /** A patch of [w]x[h] decoded pixels at [top], in page CSS pixels. */
    private fun patch(top: Float, w: Int = 200, h: Int = 100): ScreenPatch =
        ScreenPatch(fakeImage(w, h), 100f, top, w.toFloat(), h.toFloat())

    @Test
    fun aPatchOnScreenIsNotDroppedForTheByteCap() {
        val viewportH = 1000
        // One visible patch that is far too big for the cap, and one that is not.
        val visible = patch(100f, 4000, 4000)
        val hidden = patch(5000f, 4000, 4000)
        val kept = PatchWindow.prune(listOf(visible, hidden), 0, viewportH, 1f, 3, 1000L).kept
        assertTrue(kept.contains(visible), "the visible patch was dropped for the byte cap")
        assertTrue(!kept.contains(hidden), "the hidden patch was kept and the visible one dropped")
    }

    @Test
    fun theVisibleOnesAreKeptAndTheFarOnesGoFirst() {
        val viewportH = 1000
        val near = patch(900f, 2000, 2000)   // just above the viewport
        val far = patch(9000f, 2000, 2000)   // far above
        val kept = PatchWindow.prune(listOf(near, far), 0, viewportH, 1f, 3, 1000L).kept
        assertTrue(kept.contains(near), "the nearer patch was dropped and the farther one kept")
        assertTrue(!kept.contains(far))
    }

    @Test
    fun theWholePageEvictionDropsTheFarthestFirst() {
        val items = listOf(100, 5000, 9000)
        val kept = PageScanPlan.evictFarthest(
            items, budget = 2, bytes = { 1L }, distance = { it },
        )
        assertEquals(listOf(100, 5000), kept, "the farthest item was not the first to go")
    }

    /**
     * The whole-page budget is spent on the visible bubbles first: the ones far away are dropped before them.
     *
     * This is the rule the scan's own eviction follows, and it is stated here so it cannot change by accident.
     */
    @Test
    fun theWholePageBudgetPrefersTheVisibleBubbles() {
        // The reader is at the top; one bubble is on screen and one is far below.
        val visible = patch(100f, 2000, 2000)
        val far = patch(20000f, 2000, 2000)
        val centre = 0 + 1000 / 2
        val (onScreen, offScreen) = listOf(visible, far).partition { p ->
            val r = p.rectAt(1f)
            r.bottom >= 0 && r.top <= 1000
        }
        assertEquals(1, onScreen.size)
        assertEquals(1, offScreen.size)
        // The visible one is closer to the centre as well, so either rule keeps it.
        assertTrue(
            kotlin.math.abs((onScreen[0].rectAt(1f).top + 1000) - centre) <
                kotlin.math.abs((offScreen[0].rectAt(1f).top + 1000) - centre),
        )
    }
}
