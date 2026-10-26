package com.smnexstudio.panelglass.feature.browser

import androidx.compose.ui.graphics.ImageBitmap
import com.smnexstudio.panelglass.core.data.cache.SavedRegion
import com.smnexstudio.panelglass.core.model.IntRect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * The diagnostics: what the reader is told, and what it is never told.
 *
 * A diagnostic that leaks a URL, a line of text or a character's name is worse than no diagnostic: it puts the
 * reader's reading into a log file. The reasons are names and counts, and that is what these tests hold.
 */
class RetentionDiagnosticsTest {
    private val events = ArrayList<RetentionEvent>()

    private val sink = object : RetentionDiagnostics {
        override fun record(event: RetentionEvent) { events += event }
    }

    private fun fakeImage(): ImageBitmap = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(ImageBitmap::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getWidth" -> 200
            "getHeight" -> 100
            else -> null
        }
    } as ImageBitmap

    @AfterEach
    fun restoreTheSink() {
        RetentionDiagnosticsHub.current = LogRetentionDiagnostics
    }

    @Test
    fun aRecordOfTheBrokenVersionIsQuarantinedNotDrawn() {
        RetentionDiagnosticsHub.current = sink
        val image = IntRect(0, 0, 1000, 2000)
        val layout = ImageLayout(listOf(image), listOf("p.jpg"), 0, 0, 1f)
        // A record of the broken version: its sizes are percentages where the CSS pixels belonged.
        val legacy = SavedRegion(
            schema = 1, nx = 0.1f, ny = 0.1f, nw = 20f, nh = 12f, imgW = 1000f, imgH = 2000f,
            kind = 0, occurrence = 0, fingerprint = "fp", createdAtMs = 1_000L, webp = byteArrayOf(1),
        )
        val restored = RetentionCoordinator.restore(
            listOf(legacy), "p.jpg", 0, layout, 1000, 500, "fp", emptyList(), { fakeImage() },
        )
        assertTrue(restored.isEmpty(), "a record of the broken version was drawn")
        assertTrue(
            events.any { it.reason == RetentionReason.LEGACY_GEOMETRY_INVALID },
            "the quarantine was not said: ${events.map { it.reason }}",
        )
    }

    @Test
    fun aRecordOfOtherSettingsIsNotDrawnAndItIsSaid() {
        RetentionDiagnosticsHub.current = sink
        val image = IntRect(0, 0, 1000, 2000)
        val layout = ImageLayout(listOf(image), listOf("p.jpg"), 0, 0, 1f)
        val rec = SavedRegion(
            schema = PatchRetention.GEOMETRY_SCHEMA, nx = 0.1f, ny = 0.1f, nw = 0.2f, nh = 0.05f,
            imgW = 1000f, imgH = 2000f, kind = 0, occurrence = 0, fingerprint = "other",
            createdAtMs = 1_000L, webp = byteArrayOf(1),
        )
        val restored = RetentionCoordinator.restore(
            listOf(rec), "p.jpg", 0, layout, 1000, 500, "fp", emptyList(), { fakeImage() },
        )
        assertTrue(restored.isEmpty())
        assertTrue(events.any { it.reason == RetentionReason.FINGERPRINT_MISMATCH })
    }

    @Test
    fun aRecordOfAnotherPictureIsNotDrawn() {
        RetentionDiagnosticsHub.current = sink
        val image = IntRect(0, 0, 1000, 2000)
        val layout = ImageLayout(listOf(image), listOf("p.jpg"), 0, 0, 1f)
        val rec = SavedRegion(
            schema = PatchRetention.GEOMETRY_SCHEMA, nx = 0.1f, ny = 0.1f, nw = 0.2f, nh = 0.05f,
            imgW = 1000f, imgH = 2000f, kind = 0, occurrence = 1, fingerprint = "fp",
            createdAtMs = 1_000L, webp = byteArrayOf(1),
        )
        val restored = RetentionCoordinator.restore(
            listOf(rec), "p.jpg", 0, layout, 1000, 500, "fp", emptyList(), { fakeImage() },
        )
        assertTrue(restored.isEmpty(), "a record of the other picture was drawn")
    }

    @Test
    fun aBlobThatWillNotDecodeIsSaid() {
        RetentionDiagnosticsHub.current = sink
        val image = IntRect(0, 0, 1000, 2000)
        val layout = ImageLayout(listOf(image), listOf("p.jpg"), 0, 0, 1f)
        val rec = SavedRegion(
            schema = PatchRetention.GEOMETRY_SCHEMA, nx = 0.1f, ny = 0.1f, nw = 0.2f, nh = 0.05f,
            imgW = 1000f, imgH = 2000f, kind = 0, occurrence = 0, fingerprint = "fp",
            createdAtMs = 1_000L, webp = byteArrayOf(1),
        )
        val restored = RetentionCoordinator.restore(
            listOf(rec), "p.jpg", 0, layout, 1000, 500, "fp", emptyList(), { null },
        )
        assertTrue(restored.isEmpty())
        assertTrue(events.any { it.reason == RetentionReason.DECODE_FAILED })
    }

    /**
     * The event itself holds numbers and a name, and nothing else.
     */
    @Test
    fun anEventHoldsNumbersAndANameAndNothingElse() {
        val e = RetentionEvent(RetentionReason.LEGACY_GEOMETRY_INVALID, 3, 7)
        val text = e.toString()
        assertTrue(text.contains("LEGACY_GEOMETRY_INVALID"))
        assertTrue(text.contains("count=3"))
        assertTrue(text.contains("count2=7"))
        // No url, no text, no key.
        assertTrue(!text.contains("http"))
        assertTrue(!text.contains("://"))
    }

    @Test
    fun theReasonsAreNamesAndNotSentences() {
        // Every reason is a name in capitals with underscores: a machine can read it, and it cannot leak a sentence.
        for (r in RetentionReason.entries) {
            assertTrue(r.name.all { it.isUpperCase() || it == '_' || it.isDigit() }, r.name)
        }
    }

    @Test
    fun theLegacyPolicySaysWhyAndNotWhat() {
        assertEquals(null, PatchRetention.legacyReason(PatchRetention.GEOMETRY_SCHEMA))
        assertEquals(
            PatchRetention.LegacyReason.GEOMETRY_INVALID,
            PatchRetention.legacyReason(1),
        )
    }
}
