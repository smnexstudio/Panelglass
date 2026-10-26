package com.smnexstudio.panelglass.feature.browser

import com.smnexstudio.panelglass.core.model.ContextPair

/**
 * What the reader remembers of the chapter it has read.
 *
 * Every bubble the reader translates is kept with where it sits on the page, so that the next screen can be sent the
 * lines that come just before it. The position is the page's own (CSS) pixels, which is what a scroll means, so the
 * memory survives a zoom and a scroll.
 *
 * The memory is in RAM only and is cleared when the page changes: a new chapter is a new conversation.
 *
 * It is written from the main thread and read from the pipeline's worker, so every method is synchronized.
 */
class ChapterMemory {
    /** One translated line and where it sits on the page. */
    class Entry(val top: Float, val left: Float, val source: String, val translation: String, val speaker: String?)

    private val entries = ArrayList<Entry>()

    /** Forgets everything: the page changed. */
    @Synchronized
    fun clear() {
        entries.clear()
    }

    /**
     * Remembers the lines of a screen.
     *
     * A line that is already known — the same text at about the same place — replaces the old one: the reader
     * translates the same bubble again after a scroll, and the newer translation is the better one.
     */
    @Synchronized
    fun add(entries: List<Entry>) {
        for (entry in entries) {
            val same = this.entries.indexOfFirst {
                it.source == entry.source && kotlin.math.abs(it.top - entry.top) <= SAME_PLACE
            }
            if (same >= 0) this.entries[same] = entry else this.entries.add(entry)
        }
        // A long chapter would grow without end; the oldest lines go first.
        while (this.entries.size > MAX_ENTRIES) this.entries.removeAt(0)
    }

    /**
     * The [n] lines that come before [y] on the page, in reading order.
     *
     * Only the lines above the point are returned, the nearest ones first, and they are handed back in the order they
     * are read.
     */
    @Synchronized
    fun linesAbove(y: Float, n: Int): List<ContextPair> {
        if (n <= 0) return emptyList()
        val above = entries.filter { it.top < y }
        if (above.isEmpty()) return emptyList()
        val nearest = above.sortedByDescending { it.top }.take(n)
        // Reading order: top to bottom. Lines at the same height keep the order they were added in.
        return nearest.sortedBy { it.top }.map { ContextPair(it.source, it.translation, it.speaker) }
    }

    companion object {
        /** How many lines the memory keeps. */
        const val MAX_ENTRIES = 600

        /** How close two lines must be to be the same bubble. */
        private const val SAME_PLACE = 60f
    }
}
