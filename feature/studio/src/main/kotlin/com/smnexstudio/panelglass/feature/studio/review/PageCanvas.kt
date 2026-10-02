package com.smnexstudio.panelglass.feature.studio.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smnexstudio.panelglass.core.model.BrushMode
import com.smnexstudio.panelglass.core.model.BrushStroke
import com.smnexstudio.panelglass.core.model.Bubble
import com.smnexstudio.panelglass.core.model.Pt
import com.smnexstudio.panelglass.core.ui.Jakarta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The editor canvas's view, hoisted so the editor pane can move it too (select a bubble in the list → the canvas
 * follows it). Everything is in pixels; [margin] is the room kept around a bubble that is followed or fitted.
 */
@Stable
class CanvasState(startZoom: Float) {
    var transform by mutableStateOf<CanvasTransform?>(null)
        private set
    var view by mutableStateOf<View?>(null)
    var margin = 48f

    /** The zoom the last page was left at: a page turn keeps it. */
    var lastZoom = startZoom
        private set

    /**
     * A new page or canvas size. A new page starts at [lastZoom], centred and at its top (the screen then follows the
     * first bubble); a resize keeps the scale and the centre.
     */
    fun layout(pageW: Int, pageH: Int, viewW: Int, viewH: Int, newPage: Boolean) {
        if (viewW <= 0 || viewH <= 0 || pageW <= 0 || pageH <= 0) return
        val old = transform
        val t = CanvasTransform(pageW.toFloat(), pageH.toFloat(), viewW.toFloat(), viewH.toFloat())
        val v = view
        transform = t
        view = if (v == null || old == null || newPage) {
            val fit = t.fitView()
            if (lastZoom <= 1.001f) fit
            else {
                val s = fit.scale * lastZoom
                t.clamp(View(s, viewW / 2f - pageW / 2f * s, 0f))
            }
        } else t.resized(v, old)
    }

    val zoom: Float get() = transform?.let { t -> view?.let { t.zoom(it) } } ?: 1f

    fun update(v: View) {
        view = v
        transform?.let { lastZoom = it.zoom(v) }
    }

    fun follow(box: Box) {
        val t = transform ?: return
        val v = view ?: return
        update(t.follow(v, box, margin))
    }

    fun fit() {
        val t = transform ?: return
        update(t.fitView())
    }

    /** The zoom buttons: [factor] around the middle of the canvas. */
    fun zoomBy(factor: Float) {
        val t = transform ?: return
        val v = view ?: return
        update(t.transform(v, t.viewW / 2f, t.viewH / 2f, factor, 0f, 0f))
    }

    /** Centres the view on a page point, keeping the zoom (the minimap). */
    fun centreOn(px: Float, py: Float) {
        val t = transform ?: return
        val v = view ?: return
        update(t.clamp(View(v.scale, t.viewW / 2f - px * v.scale, t.viewH / 2f - py * v.scale)))
    }
}

/** What one finger does on the canvas (two fingers always pan and zoom). */
/** [SFX]: the selected sound effect's transform box takes one-finger drags (move, rotate, scale); elsewhere it selects. */
enum class CanvasMode { SELECT, DRAW_BOX, RESHAPE, BRUSH, SFX }

/** What a drag on a sound effect's transform box does. */
private enum class SfxGrab { MOVE, ROTATE, SCALE }

@Composable
fun PageCanvas(
    state: CanvasState,
    tiles: PageTiles?,
    pageKey: Any?,
    bubbles: List<Bubble>,
    selectedId: Long?,
    mode: CanvasMode,
    onSelect: (Long?) -> Unit,
    onEditInPlace: (Long) -> Unit,
    onBoxDrawn: (Box) -> Unit,
    modifier: Modifier = Modifier,
    /** Draw the bubble outlines and numbers (off on the Final layer unless a bubble is selected or a tool is on). */
    showOutlines: Boolean = true,
    /** The page's brush strokes, shown while the brush is on. */
    strokes: List<BrushStroke> = emptyList(),
    /** Brush radius in page pixels and what a new stroke does. */
    brushRadius: Float = 20f,
    brushMode: BrushMode = BrushMode.CLEAN,
    onPolygonChanged: (Long, List<Pt>) -> Unit = { _, _ -> },
    /** The shape while a corner is dragged, so the lettering can follow it; null when the drag is abandoned. */
    onPolygonPreview: (Long, List<Pt>?) -> Unit = { _, _ -> },
    onStroke: (BrushStroke) -> Unit = {},
    /** The selected sound effect's drawn box (4 corners, page pixels), in [CanvasMode.SFX]. */
    sfxFrame: List<Pt>? = null,
    /** Where sound effects are drawn (bubble id → 4 corners): a tap there selects them, wherever they were moved. */
    sfxFrames: Map<Long, List<Pt>> = emptyMap(),
    /** Moves the effect by page pixels. */
    onSfxMove: (Float, Float) -> Unit = { _, _ -> },
    /** Turns the effect to this angle (degrees). */
    onSfxRotate: (Float) -> Unit = {},
    /** Scales the effect by this factor (relative to the last call). */
    onSfxScale: (Float) -> Unit = {},
    overlay: @Composable (screenBox: (Box) -> Box?) -> Unit = {},
) {
    val density = LocalDensity.current
    val stroke = with(density) { 2.dp.toPx() }
    val slop = with(density) { 24.dp.toPx() }
    state.margin = with(density) { 36.dp.toPx() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val base: ImageBitmap? = remember(tiles) { tiles?.base?.takeIf { !it.isRecycled }?.asImageBitmap() }
    var draft by remember { mutableStateOf<Box?>(null) }
    var tileTick by remember { mutableIntStateOf(0) }
    val currentBubbles by rememberUpdatedState(bubbles)
    val currentMode by rememberUpdatedState(mode)
    val currentSelected by rememberUpdatedState(selectedId)
    val currentRadius by rememberUpdatedState(brushRadius)
    val currentBrushMode by rememberUpdatedState(brushMode)
    val currentFrame by rememberUpdatedState(sfxFrame)
    val currentFrames by rememberUpdatedState(sfxFrames)
    // The gesture handlers outlive a composition: they call the callbacks as they are now (the selection changes).
    val moveSfx by rememberUpdatedState(onSfxMove)
    val rotateSfx by rememberUpdatedState(onSfxRotate)
    val scaleSfx by rememberUpdatedState(onSfxScale)
    val handleHit = with(density) { 24.dp.toPx() }
    val handleR = with(density) { 8.dp.toPx() }
    val midR = with(density) { 5.dp.toPx() }
    /** The selected bubble's shape while a corner is being dragged (committed on release). */
    var editPoly by remember { mutableStateOf<Pair<Long, List<Pt>>?>(null) }
    /** The stroke being painted, in page pixels. */
    var liveStroke by remember { mutableStateOf<List<Pt>?>(null) }

    fun shapeOf(id: Long?): List<Pt>? = editPoly?.takeIf { it.first == id }?.second ?: currentBubbles.firstOrNull { it.id == id }?.polygon

    /** Index of the selected shape's corner within reach of the screen point, if any. */
    fun vertexAt(sx: Float, sy: Float): Int? {
        val t = state.transform ?: return null
        val v = state.view ?: return null
        val poly = shapeOf(currentSelected) ?: return null
        return poly.indices.map { i -> i to t.toScreen(v, poly[i].x, poly[i].y) }
            .map { (i, p) -> i to kotlin.math.hypot(p.first - sx, p.second - sy) }
            .filter { it.second <= handleHit }.minByOrNull { it.second }?.first
    }

    /** The transform box's rotate handle (above the top edge's middle) and scale handle (a corner), on screen. */
    fun sfxHandles(): Triple<List<Offset>, Offset, Offset>? {
        val t = state.transform ?: return null
        val v = state.view ?: return null
        val f = currentFrame ?: return null
        val pts = f.map { val (x, y) = t.toScreen(v, it.x, it.y); Offset(x, y) }
        val topMid = (pts[0] + pts[1]) / 2f
        val centre = (pts[0] + pts[2]) / 2f
        val up = (topMid - centre).let { d -> val len = d.getDistance().coerceAtLeast(1f); d / len }
        return Triple(pts, topMid + up * handleHit * 1.6f, pts[2])
    }

    /** What a drag starting at a screen point does to the effect, if anything. */
    fun sfxGrabAt(p: Offset): SfxGrab? {
        val (pts, rotate, scale) = sfxHandles() ?: return null
        if ((p - rotate).getDistance() <= handleHit) return SfxGrab.ROTATE
        if ((p - scale).getDistance() <= handleHit) return SfxGrab.SCALE
        val t = state.transform ?: return null
        val v = state.view ?: return null
        val (px, py) = t.toPage(v, p.x, p.y)
        return if (PolygonHit.contains(currentFrame!!, px, py)) SfxGrab.MOVE else null
    }

    /** Index of the edge whose midpoint is within reach (a tap there adds a corner). */
    fun midpointAt(sx: Float, sy: Float): Int? {
        val t = state.transform ?: return null
        val v = state.view ?: return null
        val poly = shapeOf(currentSelected) ?: return null
        return poly.indices.map { i ->
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            val (mx, my) = t.toScreen(v, (a.x + b.x) / 2f, (a.y + b.y) / 2f)
            i to kotlin.math.hypot(mx - sx, my - sy)
        }.filter { it.second <= handleHit }.minByOrNull { it.second }?.first
    }

    // A new page (or the first layout) resets the view; a new size keeps it.
    LaunchedEffect(tiles, size) {
        val t = tiles ?: return@LaunchedEffect
        state.layout(t.pageW, t.pageH, size.width, size.height, newPage = state.transform?.let { it.pageW != t.pageW.toFloat() || it.pageH != t.pageH.toFloat() } ?: true)
    }
    LaunchedEffect(pageKey) { draft = null }

    // Full-resolution tiles for what is on screen, once the zoom asks for more pixels than the base has.
    LaunchedEffect(tiles) {
        val t = tiles ?: return@LaunchedEffect
        snapshotFlow { state.view to state.transform }.collectLatest { (v, tr) ->
            if (v == null || tr == null) return@collectLatest
            val sample = TileGrid.sampleFor(v.scale, t.baseSample) ?: return@collectLatest
            val keys = TileGrid.visible(tr.visiblePage(v), sample, t.pageW, t.pageH)
            for (k in keys) {
                if (t[k] != null) continue
                withContext(Dispatchers.IO) { t.load(k) }
                tileTick++
            }
        }
    }

    fun pick(sx: Float, sy: Float): Long? {
        val t = state.transform ?: return null
        val v = state.view ?: return null
        val (px, py) = t.toPage(v, sx, sy)
        // A sound effect is where it is drawn, not only where it was detected.
        currentFrames.entries.firstOrNull { (_, f) -> PolygonHit.contains(f, px, py) }?.let { return it.key }
        // Dismissed bubbles stay tappable (drawn dashed), so they can be restored; a live one wins where both overlap.
        val shown = currentBubbles.filter { it.polygon.size >= 3 }
        val live = shown.filter { !it.ignored }
        PolygonHit.pick(live.map { it.polygon }, px, py, slop / v.scale)?.let { return live[it].id }
        return PolygonHit.pick(shown.map { it.polygon }, px, py, slop / v.scale)?.let { shown[it].id }
    }

    Box(
        modifier.clipToBounds().background(Color(0xFF2A2A2E)).onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { p ->
                        when (currentMode) {
                            CanvasMode.SELECT -> onSelect(pick(p.x, p.y))
                            CanvasMode.SFX -> if (sfxGrabAt(p) == null) onSelect(pick(p.x, p.y))
                            CanvasMode.RESHAPE -> {
                                val id = currentSelected
                                val edge = if (vertexAt(p.x, p.y) == null) midpointAt(p.x, p.y) else null
                                val poly = shapeOf(id)
                                if (id != null && edge != null && poly != null) {
                                    val a = poly[edge]; val b = poly[(edge + 1) % poly.size]
                                    onPolygonChanged(id, poly.toMutableList().apply { add(edge + 1, Pt((a.x + b.x) / 2f, (a.y + b.y) / 2f)) })
                                } else if (vertexAt(p.x, p.y) == null) onSelect(pick(p.x, p.y))
                            }
                            else -> Unit
                        }
                    },
                    onDoubleTap = { p ->
                        val t = state.transform ?: return@detectTapGestures
                        val v = state.view ?: return@detectTapGestures
                        val id = pick(p.x, p.y)
                        val box = currentBubbles.firstOrNull { it.id == id }?.let { Box.of(it.polygon) }
                        if (id != null) onSelect(id)
                        state.update(t.doubleTap(v, p.x, p.y, box, state.margin))
                    },
                    onLongPress = { p ->
                        when (currentMode) {
                            CanvasMode.SELECT -> pick(p.x, p.y)?.let { onSelect(it); onEditInPlace(it) }
                            CanvasMode.RESHAPE -> {
                                val id = currentSelected
                                val i = vertexAt(p.x, p.y)
                                val poly = shapeOf(id)
                                if (id != null && i != null && poly != null && poly.size > 3) onPolygonChanged(id, poly.filterIndexed { k, _ -> k != i })
                            }
                            else -> Unit
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                // One finger pans (or draws the box in Add mode); two fingers always pan and zoom, even mid-box.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var multi = false
                    var moved = false
                    val drawing = currentMode == CanvasMode.DRAW_BOX
                    val brushing = currentMode == CanvasMode.BRUSH
                    val start = down.position
                    val corner = if (currentMode == CanvasMode.RESHAPE) vertexAt(start.x, start.y) else null
                    val cornerOf = currentSelected
                    val grab = if (currentMode == CanvasMode.SFX) sfxGrabAt(start) else null
                    var lastScale = 1f
                    var lastPos = start
                    if (brushing) state.transform?.let { t -> state.view?.let { v -> val (x, y) = t.toPage(v, start.x, start.y); liveStroke = listOf(Pt(x, y)) } }
                    do {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        val t = state.transform
                        val v = state.view
                        if (t == null || v == null) continue
                        if (pressed.size >= 2) {
                            multi = true
                            draft = null
                            liveStroke = null
                            editPoly?.let { onPolygonPreview(it.first, null) }
                            editPoly = null
                            val c = ev.calculateCentroid(useCurrent = true)
                            val z = ev.calculateZoom()
                            val pan = ev.calculatePan()
                            state.update(t.transform(v, c.x, c.y, z, pan.x, pan.y))
                            ev.changes.forEach { it.consume() }
                        } else if (pressed.size == 1 && !multi) {
                            val ch = pressed.first()
                            if (!moved && (ch.position - start).getDistance() > viewConfiguration.touchSlop) moved = true
                            if (moved && grab != null) {
                                val f = currentFrame
                                if (f != null) {
                                    val (px, py) = t.toPage(v, ch.position.x, ch.position.y)
                                    val cx = (f[0].x + f[2].x) / 2f
                                    val cy = (f[0].y + f[2].y) / 2f
                                    when (grab) {
                                        SfxGrab.MOVE -> {
                                            val (ax, ay) = t.toPage(v, lastPos.x, lastPos.y)
                                            moveSfx(px - ax, py - ay)
                                        }
                                        // The handle sits above the text: pointing straight up is 0°.
                                        SfxGrab.ROTATE -> rotateSfx(Math.toDegrees(kotlin.math.atan2((py - cy).toDouble(), (px - cx).toDouble())).toFloat() + 90f)
                                        SfxGrab.SCALE -> {
                                            val (sx, sy) = t.toPage(v, start.x, start.y)
                                            val d0 = kotlin.math.hypot(sx - cx, sy - cy).coerceAtLeast(1f)
                                            val ratio = kotlin.math.hypot(px - cx, py - cy) / d0
                                            scaleSfx(ratio / lastScale)
                                            lastScale = ratio
                                        }
                                    }
                                }
                                lastPos = ch.position
                                ch.consume()
                            } else if (moved) {
                                if (corner != null && cornerOf != null) {
                                    val poly = shapeOf(cornerOf)
                                    if (poly != null && corner < poly.size) {
                                        val (x, y) = t.toPage(v, ch.position.x, ch.position.y)
                                        val next = poly.toMutableList().apply {
                                            set(corner, Pt(x.coerceIn(0f, t.pageW), y.coerceIn(0f, t.pageH)))
                                        }
                                        editPoly = cornerOf to next
                                        onPolygonPreview(cornerOf, next)
                                    }
                                } else if (brushing) {
                                    val (x, y) = t.toPage(v, ch.position.x, ch.position.y)
                                    liveStroke = liveStroke.orEmpty() + Pt(x, y)
                                } else if (drawing) {
                                    val (x0, y0) = t.toPage(v, start.x, start.y)
                                    val (x1, y1) = t.toPage(v, ch.position.x, ch.position.y)
                                    draft = Box.normalized(x0, y0, x1, y1)
                                } else {
                                    val d = ch.positionChange()
                                    state.update(t.pan(v, d.x, d.y))
                                }
                                ch.consume()
                            }
                        }
                    } while (ev.changes.any { it.pressed })
                    val box = draft
                    if (drawing && !multi && box != null) onBoxDrawn(box)
                    draft = null
                    editPoly?.let { (id, poly) -> if (!multi) onPolygonChanged(id, poly) }
                    editPoly = null
                    val painted = liveStroke
                    if (brushing && !multi && painted != null && painted.isNotEmpty()) onStroke(BrushStroke(currentBrushMode, currentRadius, painted))
                    liveStroke = null
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (tileTick < 0) return@Canvas // reading it redraws when a tile arrives
            val t = state.transform ?: return@Canvas
            val v = state.view ?: return@Canvas
            val img = base ?: return@Canvas
            val src = tiles ?: return@Canvas
            if (src.base.isRecycled) return@Canvas
            // The base, then the sharper tiles over it.
            drawImage(
                img,
                dstOffset = IntOffset(v.tx.roundToInt(), v.ty.roundToInt()),
                dstSize = IntSize((t.pageW * v.scale).roundToInt(), (t.pageH * v.scale).roundToInt()),
            )
            TileGrid.sampleFor(v.scale, src.baseSample)?.let { sample ->
                for (k in TileGrid.visible(t.visiblePage(v), sample, src.pageW, src.pageH)) {
                    val bmp = src[k] ?: continue
                    val r = t.toScreen(v, TileGrid.rect(k, src.pageW, src.pageH))
                    drawImage(
                        bmp.asImageBitmap(),
                        dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                        dstSize = IntSize(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)),
                    )
                }
            }
            // Outlines in screen pixels: the same width at every zoom.
            bubbles.forEach { b ->
                val poly = editPoly?.takeIf { it.first == b.id }?.second ?: b.polygon
                if (poly.size < 3) return@forEach
                val sel = b.id == selectedId
                if (!showOutlines && !sel) return@forEach
                val color = when {
                    sel -> SELECTED
                    b.ignored -> Color(0x99FFFFFF)
                    else -> OUTLINE
                }
                drawPolygon(poly, t, v, color, if (sel) stroke * 2 else stroke, dashed = b.ignored)
            }
            // Reshape handles: corners to drag, midpoints to add a corner (screen-sized at any zoom).
            if (mode == CanvasMode.RESHAPE) shapeOf(selectedId)?.let { poly ->
                poly.indices.forEach { i ->
                    val a = poly[i]; val b = poly[(i + 1) % poly.size]
                    val (mx, my) = t.toScreen(v, (a.x + b.x) / 2f, (a.y + b.y) / 2f)
                    drawCircle(Color.White, midR, Offset(mx, my))
                    drawCircle(SELECTED, midR, Offset(mx, my), style = Stroke(stroke))
                }
                poly.forEach { pt ->
                    val (x, y) = t.toScreen(v, pt.x, pt.y)
                    drawCircle(Color.White, handleR, Offset(x, y))
                    drawCircle(SELECTED, handleR, Offset(x, y), style = Stroke(stroke * 1.5f))
                }
            }
            // The sound effect's transform box: its outline, the rotate handle above it and the scale handle at a corner.
            if (mode == CanvasMode.SFX) sfxHandles()?.let { (pts, rotate, scale) ->
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close()
                }
                drawPath(path, SELECTED, style = Stroke(stroke * 1.5f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
                drawLine(SELECTED, (pts[0] + pts[1]) / 2f, rotate, stroke)
                drawCircle(Color.White, handleR, rotate)
                drawCircle(SELECTED, handleR, rotate, style = Stroke(stroke * 1.5f))
                drawRect(Color.White, Offset(scale.x - handleR, scale.y - handleR), Size(handleR * 2, handleR * 2))
                drawRect(SELECTED, Offset(scale.x - handleR, scale.y - handleR), Size(handleR * 2, handleR * 2), style = Stroke(stroke * 1.5f))
            }
            // Brush strokes: red removes, green restores; drawn at their true size on the page.
            if (mode == CanvasMode.BRUSH) {
                (strokes + listOfNotNull(liveStroke?.let { BrushStroke(brushMode, brushRadius, it) })).forEach { s -> drawStroke(s, t, v) }
            }
            draft?.let { d ->
                val r = t.toScreen(v, d)
                drawRect(SELECTED.copy(alpha = 0.18f), Offset(r.left, r.top), Size(r.width, r.height))
                drawRect(SELECTED, Offset(r.left, r.top), Size(r.width, r.height), style = Stroke(stroke * 2))
            }
        }
        // Reading-order numbers, in screen space.
        val t = state.transform
        val v = state.view
        if (t != null && v != null && showOutlines) {
            var n = 0
            for (b in bubbles) {
                if (b.ignored || b.polygon.size < 3) continue
                n++
                val r = t.toScreen(v, Box.of(b.polygon))
                if (r.right < 0 || r.bottom < 0 || r.left > size.width || r.top > size.height) continue
                val sel = b.id == selectedId
                Text(
                    n.toString(), fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 11.sp,
                    color = if (sel) Color.Black else Color.White,
                    modifier = Modifier
                        .offset { IntOffset(max(0f, r.left).roundToInt(), max(0f, r.top).roundToInt()) }
                        .clip(RoundedCornerShape(50))
                        .background(if (sel) SELECTED else OUTLINE)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        overlay { box -> state.transform?.let { tr -> state.view?.let { vv -> tr.toScreen(vv, box) } } }

        // Zoom chip: shows the zoom, taps back to the fit.
        if (state.zoom > 1.05f) {
            Text(
                "%.1f×".format(state.zoom), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 12.sp, color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(50))
                    .background(Color(0xCC000000)).clickable { state.fit() }.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        // Minimap above 2×: where the view is on the page; drag or tap to move there.
        if (state.zoom > 2f && base != null && t != null && v != null) {
            Minimap(base, t, v, state, Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
    }
}

private val OUTLINE = Color(0xFF3D8BFF)
private val SELECTED = Color(0xFFFFC21A)

private fun DrawScope.drawStroke(s: BrushStroke, t: CanvasTransform, v: View) {
    val color = if (s.mode == BrushMode.CLEAN) Color(0x66FF3B30) else Color(0x6634C759)
    val r = s.radius * v.scale
    if (s.points.size == 1) {
        val (x, y) = t.toScreen(v, s.points[0].x, s.points[0].y)
        drawCircle(color, r, Offset(x, y))
        return
    }
    val path = Path()
    s.points.forEachIndexed { i, p ->
        val (x, y) = t.toScreen(v, p.x, p.y)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(r * 2, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

private fun DrawScope.drawPolygon(polygon: List<Pt>, t: CanvasTransform, v: View, color: Color, width: Float, dashed: Boolean) {
    val path = Path()
    polygon.forEachIndexed { i, p ->
        val (x, y) = t.toScreen(v, p.x, p.y)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color, style = Stroke(width, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 10f)) else null))
}

@Composable
private fun Minimap(base: ImageBitmap, t: CanvasTransform, v: View, state: CanvasState, modifier: Modifier) {
    val density = LocalDensity.current
    val maxW = with(density) { 72.dp.toPx() }
    val maxH = with(density) { 110.dp.toPx() }
    val k = min(maxW / t.pageW, maxH / t.pageH)
    val w = t.pageW * k
    val h = t.pageH * k
    val visible = t.visiblePage(v)
    Canvas(
        modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() })
            .border(1.dp, Color.White)
            .pointerInput(t) {
                detectTapGestures { p -> state.centreOn(p.x / k, p.y / k) }
            }
            .pointerInput(t) {
                detectDragGestures { change, _ -> change.consume(); state.centreOn(change.position.x / k, change.position.y / k) }
            },
    ) {
        drawImage(base, dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)))
        drawRect(Color(0x55000000), size = this.size)
        drawRect(
            SELECTED, Offset(visible.left * k, visible.top * k), Size(max(2f, visible.width * k), max(2f, visible.height * k)),
            style = Stroke(2f),
        )
    }
}
