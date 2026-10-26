package com.smnexstudio.panelglass.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The reader's memory of the chapter: what it keeps, and what it hands back for the next screen. */
class ChapterMemoryTest {
    private fun entry(top: Float, source: String = "s${top.toInt()}", translation: String = "t${top.toInt()}", speaker: String? = null) =
        ChapterMemory.Entry(top, 0f, source, translation, speaker)

    @Test
    fun onlyTheLinesAboveAreGiven() {
        val m = ChapterMemory()
        m.add(listOf(entry(100f), entry(200f), entry(300f)))
        val lines = m.linesAbove(250f, 5)
        assertEquals(listOf("t100", "t200"), lines.map { it.translation })
    }

    @Test
    fun theNearestLinesAreGiven() {
        val m = ChapterMemory()
        m.add((1..10).map { entry(it * 100f) })
        val lines = m.linesAbove(1000f, 3)
        assertEquals(listOf("t700", "t800", "t900"), lines.map { it.translation })
    }

    @Test
    fun theLinesComeBackInReadingOrder() {
        val m = ChapterMemory()
        m.add(listOf(entry(300f), entry(100f), entry(200f)))
        val lines = m.linesAbove(400f, 5)
        assertEquals(listOf("t100", "t200", "t300"), lines.map { it.translation })
    }

    @Test
    fun nothingAboveMeansNothing() {
        val m = ChapterMemory()
        m.add(listOf(entry(100f)))
        assertTrue(m.linesAbove(50f, 5).isEmpty())
    }

    @Test
    fun theSameBubbleReplacesTheOldOne() {
        val m = ChapterMemory()
        m.add(listOf(entry(100f, source = "same", translation = "old")))
        m.add(listOf(entry(120f, source = "same", translation = "new")))
        assertEquals(1, m.linesAbove(500f, 5).size)
        assertEquals("new", m.linesAbove(500f, 5)[0].translation)
    }

    @Test
    fun aDifferentBubbleIsKept() {
        val m = ChapterMemory()
        m.add(listOf(entry(100f, source = "a")))
        m.add(listOf(entry(120f, source = "b")))
        assertEquals(2, m.linesAbove(500f, 5).size)
    }

    @Test
    fun aFarAwayLineWithTheSameTextIsAnotherBubble() {
        val m = ChapterMemory()
        m.add(listOf(entry(100f, source = "same")))
        m.add(listOf(entry(900f, source = "same")))
        assertEquals(2, m.linesAbove(1000f, 5).size)
    }

    @Test
    fun theMemoryKeepsAtMostSixHundredLines() {
        val m = ChapterMemory()
        m.add((1..700).map { entry(it * 10f) })
        val lines = m.linesAbove(100_000f, 1000)
        assertEquals(ChapterMemory.MAX_ENTRIES, lines.size)
        // The oldest are gone: the first kept line is the 101st.
        assertEquals("t1010", lines.first().translation)
    }

    @Test
    fun clearingForgetsEverything() {
        val m = ChapterMemory()
        m.add(listOf(entry(100f)))
        m.clear()
        assertTrue(m.linesAbove(500f, 5).isEmpty())
    }

    @Test
    fun theSpeakerIsCarried() {
        val m = ChapterMemory()
        m.add(listOf(entry(100f, speaker = "A")))
        assertEquals("A", m.linesAbove(500f, 5)[0].speaker)
    }

    @Test
    fun twoThreadsMayUseItAtOnce() {
        val m = ChapterMemory()
        val done = CountDownLatch(2)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val writer = Thread {
            try {
                repeat(500) { m.add(listOf(entry(it * 10f))) }
            } catch (e: Throwable) { errors.add(e) } finally { done.countDown() }
        }
        val reader = Thread {
            try {
                repeat(500) { m.linesAbove(100_000f, 10) }
            } catch (e: Throwable) { errors.add(e) } finally { done.countDown() }
        }
        writer.start(); reader.start()
        done.await(10, TimeUnit.SECONDS)
        assertTrue("no exception: ${errors.joinToString()}", errors.isEmpty())
    }
}
