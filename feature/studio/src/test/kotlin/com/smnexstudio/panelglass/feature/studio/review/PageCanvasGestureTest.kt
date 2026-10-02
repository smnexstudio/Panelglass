package com.smnexstudio.panelglass.feature.studio.review

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.model.Bubble
import com.smnexstudio.panelglass.core.model.Pt
import com.smnexstudio.panelglass.core.model.RegionKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The canvas's gestures on a real (Robolectric) Compose tree: a 1000 × 1000 page in a 1000 × 1000 px canvas, so page
 * and screen pixels coincide at the fit.
 */
@RunWith(RobolectricTestRunner::class)
// A screen wide enough for a 1000 dp canvas (a narrower one would clamp it and the page would no longer fit 1:1).
@Config(sdk = [34], qualifiers = "w1100dp-h1300dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageCanvasGestureTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var file: File
    private lateinit var tiles: PageTiles

    private val bubble = Bubble(id = 7, pageId = 1, order = 0, kind = RegionKind.ENCLOSED, polygon = listOf(Pt(600f, 100f), Pt(800f, 100f), Pt(800f, 400f), Pt(600f, 400f)))
    private var bubbles by mutableStateOf<List<Bubble>>(emptyList())
    private var selected by mutableStateOf<Long?>(null)
    private var inPlace: Long? = null
    private var drawn: Box? = null
    private var mode by mutableStateOf(CanvasMode.SELECT)
    private val state = CanvasState(1f)

    @Before fun page() {
        file = File(context.cacheDir, "canvas-test.png")
        val bmp = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tiles = PageTiles.open(file, PageTiles.BUDGET)!!
    }

    @After fun cleanUp() {
        tiles.close()
        file.delete()
    }

    private fun setContent() = compose.setContent {
        PageCanvas(
            state = state, tiles = tiles, pageKey = 1L, bubbles = bubbles.ifEmpty { listOf(bubble) }, selectedId = selected, mode = mode,
            onSelect = { selected = it }, onEditInPlace = { inPlace = it }, onBoxDrawn = { drawn = it },
            // mdpi: 1 dp = 1 px, so the canvas is 1000 × 1000 px like the page.
            modifier = Modifier.size(1000.dp).testTag("canvas"),
        )
    }

    @Test fun aTapSelectsTheBubbleUnderItAndEmptyArtClearsIt() {
        setContent()
        compose.onNodeWithTag("canvas").performTouchInput { click(Offset(700f, 250f)) }
        // A single tap is only a tap once the double-tap window has passed.
        compose.mainClock.advanceTimeBy(DOUBLE_TAP_WAIT)
        compose.waitForIdle()
        assertEquals(7L, selected)
        compose.onNodeWithTag("canvas").performTouchInput { click(Offset(200f, 800f)) }
        compose.mainClock.advanceTimeBy(DOUBLE_TAP_WAIT)
        compose.waitForIdle()
        assertNull(selected)
    }

    private companion object {
        const val DOUBLE_TAP_WAIT = 500L
    }

    @Test fun aDismissedBubbleCanStillBeTappedToRestoreIt() {
        bubbles = listOf(bubble.copy(ignored = true))
        setContent()
        compose.onNodeWithTag("canvas").performTouchInput { click(Offset(700f, 250f)) }
        compose.mainClock.advanceTimeBy(DOUBLE_TAP_WAIT)
        compose.waitForIdle()
        assertEquals(7L, selected)
    }

    @Test fun doubleTapOnABubbleZoomsToIt() {
        setContent()
        compose.onNodeWithTag("canvas").performTouchInput { doubleClick(Offset(700f, 250f)) }
        compose.waitForIdle()
        assertTrue(state.zoom > 2f)
        val t = state.transform!!
        val r = t.toScreen(state.view!!, Box(600f, 100f, 800f, 400f))
        assertTrue(r.left >= 0 && r.right <= 1000 && r.top >= 0 && r.bottom <= 1000)
    }

    @Test fun doubleTapOnArtZoomsInAndBackOut() {
        setContent()
        compose.onNodeWithTag("canvas").performTouchInput { doubleClick(Offset(200f, 800f)) }
        compose.waitForIdle()
        assertEquals(2.5f, state.zoom, 0.05f)
        compose.onNodeWithTag("canvas").performTouchInput { doubleClick(Offset(200f, 800f)) }
        compose.waitForIdle()
        assertEquals(1f, state.zoom, 0.01f)
    }

    @Test fun twoFingersZoom() {
        setContent()
        compose.onNodeWithTag("canvas").performTouchInput {
            pinch(Offset(450f, 500f), Offset(350f, 500f), Offset(550f, 500f), Offset(150f, 500f))
        }
        compose.waitForIdle()
        assertTrue(state.zoom > 1.5f)
    }

    @Test fun aLongPressOnABubbleEditsItInPlace() {
        setContent()
        compose.onNodeWithTag("canvas").performTouchInput { longClick(Offset(700f, 250f)) }
        compose.waitForIdle()
        assertEquals(7L, inPlace)
        assertEquals(7L, selected)
    }

    @Test fun inAddModeADragDrawsABoxInPagePixels() {
        mode = CanvasMode.DRAW_BOX
        setContent()
        compose.onNodeWithTag("canvas").performTouchInput { swipe(Offset(100f, 500f), Offset(300f, 650f), 300) }
        compose.waitForIdle()
        val box = assertNotNullBox()
        assertEquals(100f, box.left, 12f); assertEquals(500f, box.top, 12f)
        assertEquals(300f, box.right, 12f); assertEquals(650f, box.bottom, 12f)
        assertEquals(1f, state.zoom, 0.01f) // drawing does not pan
    }

    @Test fun oneFingerPansWhenZoomed() {
        setContent()
        compose.onNodeWithTag("canvas").performTouchInput { doubleClick(Offset(500f, 500f)) }
        compose.waitForIdle()
        val before = state.view!!
        compose.onNodeWithTag("canvas").performTouchInput { swipe(Offset(500f, 500f), Offset(300f, 400f), 200) }
        compose.waitForIdle()
        val after = state.view!!
        assertTrue(after.tx < before.tx && after.ty < before.ty)
        assertEquals(before.scale, after.scale, 1e-4f)
    }

    private fun assertNotNullBox(): Box {
        assertNotNull(drawn)
        return drawn!!
    }
}
