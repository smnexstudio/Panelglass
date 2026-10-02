package com.smnexstudio.panelglass.core.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Where a scrollbar's thumb goes: [offset] and [size] as fractions of the track. Null when everything fits (no bar).
 * Pure, so the thumb maths is tested on the JVM (`ScrollbarTest`).
 */
data class Thumb(val offset: Float, val size: Float)

object ScrollbarMath {
    /** The smallest thumb, as a share of the track, so a long list still has something to see and aim for. */
    const val MIN_SIZE = 0.08f

    /** A plain scroll: [value] of [max] scrolled, [viewport] visible out of `viewport + max`. */
    fun forScroll(value: Int, max: Int, viewport: Int): Thumb? {
        if (max <= 0 || viewport <= 0) return null
        val size = (viewport.toFloat() / (viewport + max)).coerceIn(MIN_SIZE, 1f)
        return Thumb((value.toFloat() / max) * (1f - size), size)
    }

    /**
     * A lazy list, estimated from the items: [first] visible item and how far it is scrolled out ([firstOffsetShare],
     * 0..1 of its height), [visible] items on screen out of [total]. [atEnd] pins the thumb to the bottom.
     */
    fun forList(first: Int, firstOffsetShare: Float, visible: Int, total: Int, atEnd: Boolean): Thumb? {
        if (total <= 0 || visible <= 0) return null
        if (first == 0 && firstOffsetShare == 0f && visible >= total && atEnd) return null
        val size = (visible.toFloat() / total).coerceIn(MIN_SIZE, 1f)
        val progress = if (atEnd) 1f else ((first + firstOffsetShare) / (total - visible).coerceAtLeast(1)).coerceIn(0f, 1f)
        return Thumb(progress * (1f - size), size)
    }
}

private val THUMB = Color(0x66888888)

/** A thin always-visible bar on the right while the list does not fit, so the user sees there is more below. */
fun Modifier.verticalScrollbar(state: LazyListState, width: Dp = 4.dp, color: Color = THUMB): Modifier = drawWithContent {
    drawContent()
    val info = state.layoutInfo
    val items = info.visibleItemsInfo
    if (items.isEmpty()) return@drawWithContent
    val first = items.first()
    val lastVisible = items.last()
    val fullyVisible = items.count { it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset }
    val atEnd = lastVisible.index == info.totalItemsCount - 1 && lastVisible.offset + lastVisible.size <= info.viewportEndOffset
    val share = if (first.size > 0) (-first.offset.toFloat() / first.size).coerceIn(0f, 1f) else 0f
    val thumb = ScrollbarMath.forList(first.index, share, fullyVisible.coerceAtLeast(1), info.totalItemsCount, atEnd) ?: return@drawWithContent
    drawThumb(thumb, width.toPx(), color)
}

fun Modifier.verticalScrollbar(state: ScrollState, width: Dp = 4.dp, color: Color = THUMB): Modifier = drawWithContent {
    drawContent()
    val thumb = ScrollbarMath.forScroll(state.value, state.maxValue, size.height.toInt()) ?: return@drawWithContent
    drawThumb(thumb, width.toPx(), color)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawThumb(thumb: Thumb, w: Float, color: Color) {
    val pad = 2f
    val track = size.height - 2 * pad
    drawRoundRect(
        color,
        topLeft = Offset(size.width - w - pad, pad + thumb.offset * track),
        size = Size(w, thumb.size * track),
        cornerRadius = CornerRadius(w / 2, w / 2),
    )
}
