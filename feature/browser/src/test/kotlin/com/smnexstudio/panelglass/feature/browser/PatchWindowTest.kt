package com.smnexstudio.panelglass.feature.browser

import androidx.compose.ui.graphics.ImageBitmap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/** The pruning of the decoded overlays: the distance rule and the byte cap. */
class PatchWindowTest {
    private val viewportH = 1000

    /** A patch at page (content) pixel [top], [height] tall, whose bitmap is [w]×[h]. */
    private fun patch(top: Int, height: Int = 100, w: Int = 200, h: Int = 100) =
        ScreenPatch(fakeImage(w, h), 0f, top.toFloat(), w.toFloat(), height.toFloat())

    private fun prune(
        patches: List<ScreenPatch>,
        screens: Int = 3,
        maxBytes: Long = Long.MAX_VALUE,
        scrollY: Int = 0,
    ) = PatchWindow.prune(patches, scrollY, viewportH, 1f, screens, maxBytes)

    @Test
    fun theDistanceRuleKeepsWhatIsNear() {
        val near = patch(100)
        val far = patch(-5000)
        val r = prune(listOf(near, far))
        assertEquals(listOf(near), r.kept)
        assertEquals(1, r.droppedByDistance)
    }

    @Test
    fun theByteCapDropsTheFarthestFirst() {
        // Three patches of 200x100 pixels are 80 000 bytes each.
        val near = patch(100)
        val middle = patch(-500)
        val far = patch(-1500)
        val r = prune(listOf(near, middle, far), maxBytes = 160_000)
        assertEquals(listOf(near, middle), r.kept)
        assertEquals(1, r.droppedByBytes)
        assertEquals(0, r.droppedByDistance)
    }

    @Test
    fun bothRulesApply() {
        val near = patch(100)
        val far = patch(-5000)
        val mid = patch(-1500)
        val r = prune(listOf(near, far, mid), maxBytes = 80_000)
        // The far one is dropped for distance, the middle one for the bytes.
        assertEquals(listOf(near), r.kept)
        assertEquals(1, r.droppedByDistance)
        assertEquals(1, r.droppedByBytes)
    }

    @Test
    fun aPatchOnScreenIsNeverDroppedForTheBytes() {
        // The visible patch alone is over the cap, and it stays: the user is looking at it.
        val onScreen = patch(100, w = 400, h = 400)
        val r = prune(listOf(onScreen), maxBytes = 1)
        assertEquals(listOf(onScreen), r.kept)
        assertEquals(0, r.droppedByBytes)
    }

    @Test
    fun anEmptyListIsEmpty() {
        val r = prune(emptyList())
        assertTrue(r.kept.isEmpty())
        assertEquals(0, r.droppedByDistance)
        assertEquals(0, r.droppedByBytes)
    }

    @Test
    fun oneScreenKeepsOnlyWhatIsOnScreen() {
        val onScreen = patch(100)
        val above = patch(-1500)
        val r = prune(listOf(onScreen, above), screens = 1)
        assertEquals(listOf(onScreen), r.kept)
        assertEquals(1, r.droppedByDistance)
    }

    @Test
    fun theOrderOfTheKeptPatchesIsTheOrderTheyCameIn() {
        val a = patch(300)
        val b = patch(100)
        val c = patch(200)
        assertEquals(listOf(a, b, c), prune(listOf(a, b, c)).kept)
    }

    @Test
    fun theSameInputAlwaysGivesTheSameOutput() {
        val patches = listOf(patch(100), patch(-500), patch(-1500))
        val first = prune(patches, maxBytes = 120_000).kept
        val second = prune(patches, maxBytes = 120_000).kept
        assertEquals(first, second)
    }

    @Test
    fun aPatchIsDroppedOnlyWhenTheNextOneWouldNotFit() {
        val a = patch(100)
        val b = patch(-500)
        // Exactly one patch's worth of bytes: the nearer one stays.
        val r = prune(listOf(a, b), maxBytes = 80_000)
        assertEquals(listOf(a), r.kept)
    }

    @Test
    fun theBytesOfAPatchAreItsPixelsTimesFour() {
        val p = patch(0, w = 200, h = 100)
        assertEquals(200L * 100L * 4L, p.bytes)
    }

    // The fake bitmap: a proxy that answers the size, and nothing else.
    private fun fakeImage(w: Int, h: Int): ImageBitmap = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(ImageBitmap::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getWidth" -> w
            "getHeight" -> h
            else -> null
        }
    } as ImageBitmap
}
