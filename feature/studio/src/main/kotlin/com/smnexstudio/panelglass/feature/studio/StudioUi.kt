package com.smnexstudio.panelglass.feature.studio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.smnexstudio.panelglass.core.model.Chapter
import com.smnexstudio.panelglass.core.ui.Jakarta
import com.smnexstudio.panelglass.core.ui.PrimaryPill
import com.smnexstudio.panelglass.core.ui.StickerDialog
import com.smnexstudio.panelglass.core.ui.TextAction
import com.smnexstudio.panelglass.core.ui.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.smnexstudio.panelglass.core.ui.R as UiR

/** Title row with a back arrow, used by every Studio screen below the tab. */
@Composable
internal fun StudioTopBar(title: String, onBack: () -> Unit, subtitle: String? = null, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.cd_back), tint = Tokens.Ink)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 20.sp, color = Tokens.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, fontFamily = Jakarta, fontSize = 12.5.sp, color = Tokens.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}

@Composable
internal fun StudioField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine,
        minLines = if (singleLine) 1 else 3, maxLines = if (singleLine) 1 else 6,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(14.dp), modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Tokens.Ink, unfocusedBorderColor = Tokens.Border, focusedTextColor = Tokens.Ink, unfocusedTextColor = Tokens.Ink,
            cursorColor = Tokens.Ink, focusedLabelColor = Tokens.InkSoft, unfocusedLabelColor = Tokens.InkFaint,
        ),
    )
}

/** A yes/no question before something that cannot be undone. */
@Composable
internal fun ConfirmDialog(title: String, message: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    StickerDialog(onDismiss) {
        Text(title, fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 20.sp, color = Tokens.Ink)
        Spacer(Modifier.height(10.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Tokens.InkSoft)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextAction(stringResource(UiR.string.action_cancel), Tokens.InkSoft, onDismiss)
            Spacer(Modifier.width(8.dp))
            PrimaryPill(confirm.uppercase(), letterSpaced = true, onClick = { onConfirm(); onDismiss() })
        }
    }
}

/** "Chapter 12 · Title", "Chapter 12", "Title" or the untitled fallback. */
@Composable
internal fun chapterName(c: Chapter): String {
    val number = c.numberLabel?.let { stringResource(UiR.string.studio_chapter_label, it) }
    return listOfNotNull(number, c.title.takeIf { it.isNotBlank() }).joinToString(" · ")
        .ifEmpty { stringResource(UiR.string.studio_chapter_untitled) }
}

// ---- thumbnails ------------------------------------------------------------------------------

/**
 * Page thumbnails, decoded small from the page file (a long strip shows its top) and kept in a small in-memory cache
 * shared by every Studio screen.
 */
internal object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun cached(file: File, targetPx: Int): Bitmap? = cache.get(key(file, targetPx))

    fun load(file: File, targetPx: Int): Bitmap? {
        val key = key(file, targetPx)
        cache.get(key)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        // A strip is shown by its top (a card is at most 3:2 tall).
        val cropH = minOf(h, (w * 1.5f).toInt())
        var sample = 1
        while (w / (sample * 2) >= targetPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = runCatching {
            if (cropH < h) {
                BitmapRegionDecoder.newInstance(file.path).let { d ->
                    try { d.decodeRegion(Rect(0, 0, w, cropH), opts) } finally { d.recycle() }
                }
            } else BitmapFactory.decodeFile(file.path, opts)
        }.getOrNull() ?: return null
        cache.put(key, bmp)
        return bmp
    }

    /** The file's time is part of the key: a cover replaced in place is a new picture. */
    private fun key(file: File, targetPx: Int) = file.path + "@" + targetPx + "@" + file.lastModified()

    /** The whole page (a strip too), about [targetPx] wide and at most ~4 MP, for a page preview. Not cached. */
    fun loadWhole(file: File, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / (sample * 2) >= targetPx || (w.toLong() / sample) * (h.toLong() / sample) > 4_000_000) sample *= 2
        return runCatching { BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull()
    }
}

/** A whole page, fitted into [modifier]'s bounds. */
@Composable
internal fun PagePreview(file: File, modifier: Modifier = Modifier, targetDp: Int = 360) {
    val px = with(LocalDensity.current) { targetDp.dp.roundToPx() }
    val bmp by produceState<Bitmap?>(null, file, px) { value = withContext(Dispatchers.IO) { Thumbs.loadWhole(file, px) } }
    Box(modifier) {
        bmp?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize()) }
    }
}

@Composable
internal fun PageThumb(file: File, modifier: Modifier = Modifier, targetDp: Int = 120) {
    val px = with(LocalDensity.current) { targetDp.dp.roundToPx() }
    // A new file restarts the producer but keeps the old picture: always load, or a changed cover shows the old one.
    val bmp by produceState(Thumbs.cached(file, px), file, px) {
        value = Thumbs.cached(file, px) ?: withContext(Dispatchers.IO) { Thumbs.load(file, px) }
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Tokens.Border)) {
        bmp?.let {
            Image(
                it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter, modifier = Modifier.matchParentSize(),
            )
        }
    }
}

// ---- drag to reorder -------------------------------------------------------------------------

/**
 * Long-press-and-drag reordering for a lazy grid (a one-column grid serves as a list). Only items whose key passes
 * [movable] take part. [onMove] reorders the caller's list while dragging; [onDrop] commits the final order.
 */
internal class GridReorder(
    val grid: LazyGridState,
    private val movable: (Any) -> Boolean,
    private val onMove: (from: Any, to: Any) -> Unit,
    private val onDrop: () -> Unit,
) {
    var dragging by mutableStateOf<Any?>(null)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    /** Pointer position in the grid, for auto-scroll near an edge. */
    var pointerY by mutableStateOf<Float?>(null)
        private set

    private fun itemAt(p: Offset): LazyGridItemInfo? = grid.layoutInfo.visibleItemsInfo.firstOrNull {
        p.x >= it.offset.x && p.x < it.offset.x + it.size.width && p.y >= it.offset.y && p.y < it.offset.y + it.size.height
    }

    fun start(p: Offset) {
        val item = itemAt(p) ?: return
        if (!movable(item.key)) return
        dragging = item.key
        offset = Offset.Zero
        pointerY = p.y
    }

    fun drag(delta: Offset, p: Offset) {
        val key = dragging ?: return
        offset += delta
        pointerY = p.y
        val current = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val center = Offset(
            current.offset.x + offset.x + current.size.width / 2f,
            current.offset.y + offset.y + current.size.height / 2f,
        )
        val target = itemAt(center)?.takeIf { it.key != key && movable(it.key) } ?: return
        onMove(key, target.key)
        // The dragged item is laid out at the target's slot now: keep it under the finger.
        offset += Offset((current.offset.x - target.offset.x).toFloat(), (current.offset.y - target.offset.y).toFloat())
    }

    fun end() {
        if (dragging != null) onDrop()
        dragging = null
        offset = Offset.Zero
        pointerY = null
    }

    fun itemModifier(key: Any): Modifier =
        if (key == dragging) Modifier.zIndex(1f).graphicsLayer {
            translationX = offset.x
            translationY = offset.y
            scaleX = 1.04f
            scaleY = 1.04f
            shadowElevation = 12f
        } else Modifier
}

@Composable
internal fun rememberGridReorder(
    grid: LazyGridState,
    movable: (Any) -> Boolean,
    onMove: (Any, Any) -> Unit,
    onDrop: () -> Unit,
): GridReorder {
    val r = remember(grid) { GridReorderHolder() }
    r.movable = movable
    r.onMove = onMove
    r.onDrop = onDrop
    val state = remember(grid) { GridReorder(grid, { r.movable(it) }, { a, b -> r.onMove(a, b) }, { r.onDrop() }) }
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    // Auto-scroll while a dragged item is held near the top or bottom edge.
    LaunchedEffect(state.dragging) {
        if (state.dragging == null) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val y = state.pointerY ?: continue
            val height = grid.layoutInfo.viewportSize.height
            val step = when {
                y < edge -> -(edge - y) / 4f
                y > height - edge -> (y - (height - edge)) / 4f
                else -> 0f
            }
            if (step != 0f) {
                val moved = grid.scrollBy(step)
                if (moved != 0f) state.drag(Offset(0f, moved), Offset(0f, y))
            }
        }
    }
    return state
}

private class GridReorderHolder {
    var movable: (Any) -> Boolean = { false }
    var onMove: (Any, Any) -> Unit = { _, _ -> }
    var onDrop: () -> Unit = {}
}

/**
 * The reorder gesture on the grid. It watches the touch in the initial pass, before the tiles' own `clickable` sees
 * it: a tile consumes its touches, and a long-press detector running after it never fired. Once the press has been
 * held still for the long-press timeout, the gesture is taken over (consumed), so the tile does not also click and
 * the grid does not scroll.
 */
internal fun Modifier.reorderable(state: GridReorder): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var released = false
        val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                if (c == null || !c.pressed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    released = true
                    break
                }
            }
        } == null
        if (!held || released) return@awaitEachGesture
        state.start(down.position)
        if (state.dragging == null) return@awaitEachGesture
        try {
            while (true) {
                val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                c.consume()
                if (!c.pressed) break
                state.drag(c.position - c.previousPosition, c.position)
            }
        } finally {
            state.end()
        }
    }
}

/** Moves the element with key [from] to where [to] is, keeping the rest in order. */
internal fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    val m = toMutableList()
    val item = m.removeAt(from)
    m.add(to, item)
    return m
}

@Composable
internal fun Badge(text: String, modifier: Modifier = Modifier, strong: Boolean = false) {
    Text(
        text, fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 10.5.sp,
        color = if (strong) Tokens.Ink else Tokens.InkSoft,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(if (strong) Tokens.Yellow else Tokens.Card).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** A thin rounded progress bar: [fraction] of the track filled. */
@Composable
internal fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(Tokens.Ink.copy(alpha = 0.08f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(height / 2)).background(Tokens.YellowDeep))
    }
}
