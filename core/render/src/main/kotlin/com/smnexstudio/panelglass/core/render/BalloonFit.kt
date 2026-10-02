package com.smnexstudio.panelglass.core.render

import kotlin.math.max
import kotlin.math.min

/**
 * Where text fits inside a speech balloon whose stored shape is only its bounding rectangle (detection gives a box, not
 * the oval). The balloon's paper is flood-filled on the clean layer from the box's centre, and the largest centred
 * rectangle inside that paper is the text box. An oval's box corners are art, so text fitted to the box alone ran out
 * of the balloon there. Pure (pixels in, a rectangle out), so it is tested on the JVM.
 */
object BalloonFit {
    /** A pixel this light (0–255, by luma) is paper; the balloon's outline and the art around it are darker. */
    const val PAPER_LUMA = 200

    /** The paper must cover this share of the box, or it is not a balloon's inside (a caption on art, a dark panel). */
    const val MIN_SHARE = 0.25f

    /**
     * The text rectangle inside the balloon in [pixels] (ARGB, [w] × [h], the balloon's box and nothing else), in those
     * pixels' coordinates; null when no balloon's inside is found from near the centre.
     */
    fun interior(pixels: IntArray, w: Int, h: Int, insetShare: Float = 0.04f): FRect? {
        if (w < 8 || h < 8 || pixels.size < w * h) return null
        val paper = BooleanArray(w * h) { luma(pixels[it]) >= PAPER_LUMA }
        val seed = seed(paper, w, h) ?: return null
        val inside = closeHoles(fill(paper, w, h, seed), w, h)
        var count = 0
        var sx = 0L
        var sy = 0L
        for (i in inside.indices) if (inside[i]) { count++; sx += i % w; sy += i / w }
        if (count < MIN_SHARE * w * h) return null
        // Centred on the paper's centroid, as wide and tall as the paper's spread allows, then shrunk until it fits.
        val cx = sx.toFloat() / count
        val cy = sy.toFloat() / count
        val hw = min(cx, w - 1 - cx)
        val hh = min(cy, h - 1 - cy)
        var lo = 0f
        var hi = 1f
        repeat(16) {
            val s = (lo + hi) / 2f
            if (fits(inside, w, h, cx, cy, hw * s, hh * s)) lo = s else hi = s
        }
        if (lo <= 0.05f) return null
        val r = FRect(cx - hw * lo, cy - hh * lo, cx + hw * lo, cy + hh * lo)
        return r.inset(min(r.width, r.height) * insetShare)
    }

    /**
     * The balloon's own outline in [pixels] (its box only, [w] × [h], text may still be in it): the paper flood-filled from
     * near the centre with its holes (the text) closed, then [points] rays from its centroid to where the paper ends,
     * pushed [grow] px out to reach the balloon's line. Box coordinates, clockwise from the right; null when no balloon's
     * inside is found (a caption on art, a dark panel), so the caller keeps its rectangle.
     */
    fun outline(pixels: IntArray, w: Int, h: Int, points: Int = 32, grow: Float = 2f): List<Pair<Float, Float>>? {
        if (w < 8 || h < 8 || pixels.size < w * h) return null
        val paper = BooleanArray(w * h) { luma(pixels[it]) >= PAPER_LUMA }
        val seed = seed(paper, w, h) ?: return null
        val inside = closeHoles(fill(paper, w, h, seed), w, h)
        var count = 0
        var sx = 0L
        var sy = 0L
        for (i in inside.indices) if (inside[i]) { count++; sx += i % w; sy += i / w }
        if (count < MIN_SHARE * w * h) return null
        val cx = sx.toFloat() / count
        val cy = sy.toFloat() / count
        val reach = kotlin.math.hypot(w.toFloat(), h.toFloat())
        return (0 until points).map { k ->
            val a = 2.0 * Math.PI * k / points
            val dx = kotlin.math.cos(a).toFloat()
            val dy = kotlin.math.sin(a).toFloat()
            var r = 0f
            // Out along the ray while still inside the balloon.
            while (r < reach) {
                val x = (cx + dx * (r + 0.5f)).toInt()
                val y = (cy + dy * (r + 0.5f)).toInt()
                if (x !in 0 until w || y !in 0 until h || !inside[y * w + x]) break
                r += 0.5f
            }
            ((cx + dx * (r + grow)).coerceIn(0f, w.toFloat())) to ((cy + dy * (r + grow)).coerceIn(0f, h.toFloat()))
        }
    }

    private fun luma(c: Int): Int = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    /** The paper pixel nearest the centre (the cleaned text may leave a speck right on it). */
    private fun seed(paper: BooleanArray, w: Int, h: Int): Int? {
        val cx = w / 2
        val cy = h / 2
        val reach = min(w, h) / 4
        for (r in 0..reach) for (dy in -r..r) for (dx in -r..r) {
            if (max(kotlin.math.abs(dx), kotlin.math.abs(dy)) != r) continue
            val x = cx + dx
            val y = cy + dy
            if (x in 0 until w && y in 0 until h && paper[y * w + x]) return y * w + x
        }
        return null
    }

    /** Four-way flood fill over paper from [seed] (an explicit stack: no recursion on a large balloon). */
    private fun fill(paper: BooleanArray, w: Int, h: Int, seed: Int): BooleanArray {
        val inside = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var top = 0
        stack[top++] = seed
        inside[seed] = true
        while (top > 0) {
            val i = stack[--top]
            val x = i % w
            val y = i / w
            if (x > 0) push(i - 1, paper, inside, stack, top).let { if (it) top++ }
            if (x < w - 1) push(i + 1, paper, inside, stack, top).let { if (it) top++ }
            if (y > 0) push(i - w, paper, inside, stack, top).let { if (it) top++ }
            if (y < h - 1) push(i + w, paper, inside, stack, top).let { if (it) top++ }
        }
        return inside
    }

    /**
     * The balloon with its holes filled: whatever the paper encloses (a speck the cleanup left, a heart drawn in the
     * balloon) is inside too. The outside is what the box's border reaches through non-paper; the rest is the balloon.
     */
    private fun closeHoles(inside: BooleanArray, w: Int, h: Int): BooleanArray {
        val open = BooleanArray(w * h) { !inside[it] }
        val outside = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var top = 0
        fun seed(i: Int) { if (open[i] && !outside[i]) { outside[i] = true; stack[top++] = i } }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        while (top > 0) {
            val i = stack[--top]
            val x = i % w
            val y = i / w
            if (x > 0) seed(i - 1)
            if (x < w - 1) seed(i + 1)
            if (y > 0) seed(i - w)
            if (y < h - 1) seed(i + w)
        }
        return BooleanArray(w * h) { !outside[it] }
    }

    private fun push(j: Int, paper: BooleanArray, inside: BooleanArray, stack: IntArray, top: Int): Boolean {
        if (!paper[j] || inside[j]) return false
        inside[j] = true
        stack[top] = j
        return true
    }

    /** Every point of a 9 × 9 grid over the rectangle (edges included) is inside the balloon. */
    private fun fits(inside: BooleanArray, w: Int, h: Int, cx: Float, cy: Float, hw: Float, hh: Float): Boolean {
        for (i in 0..8) for (j in 0..8) {
            val x = (cx - hw + 2 * hw * i / 8f).toInt()
            val y = (cy - hh + 2 * hh * j / 8f).toInt()
            if (x !in 0 until w || y !in 0 until h || !inside[y * w + x]) return false
        }
        return true
    }
}
