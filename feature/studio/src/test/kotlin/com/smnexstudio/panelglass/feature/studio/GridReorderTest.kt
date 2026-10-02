package com.smnexstudio.panelglass.feature.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Long-press-and-drag reordering over clickable tiles, as the chapter and import screens use it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GridReorderTest {
    @get:Rule val compose = createComposeRule()

    private var order by mutableStateOf(listOf(1L, 2L, 3L, 4L))
    private var dropped = 0
    private var clicks = 0

    private fun setContent() = compose.setContent {
        val grid = rememberLazyGridState()
        val reorder = rememberGridReorder(
            grid, movable = { it is Long },
            onMove = { a, b -> order = order.moved(order.indexOf(a as Long), order.indexOf(b as Long)) },
            onDrop = { dropped++ },
        )
        LazyVerticalGrid(GridCells.Fixed(3), state = grid, modifier = Modifier.fillMaxSize().testTag("grid").reorderable(reorder)) {
            items(order, key = { it }) { id ->
                Box(reorder.itemModifier(id).size(100.dp).clickable { clicks++ }) { Text(id.toString()) }
            }
        }
    }

    @Test fun longPressAndDragMovesTheItemAndDrops() {
        setContent()
        // Tiles are 100 dp (300 px): item 4 sits in row 2, column 1; item 1 in row 1, column 1.
        compose.onNodeWithTag("grid").performTouchInput {
            down(Offset(150f, 450f))
            advanceEventTime(1000)
            moveTo(Offset(150f, 350f))
            moveTo(Offset(150f, 250f))
            moveTo(Offset(150f, 150f))
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(4L, 1L, 2L, 3L), order)
        assertEquals(1, dropped)
        assertEquals(0, clicks)
    }

    @Test fun aTapStillClicks() {
        setContent()
        compose.onNodeWithTag("grid").performTouchInput { click(Offset(150f, 150f)) }
        compose.waitForIdle()
        assertEquals(1, clicks)
        assertEquals(listOf(1L, 2L, 3L, 4L), order)
        assertEquals(0, dropped)
    }
}
