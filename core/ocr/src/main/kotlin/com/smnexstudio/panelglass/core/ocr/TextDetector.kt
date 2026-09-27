package com.smnexstudio.panelglass.core.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.TextLine
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** What the container model saw: a balloon, the text block inside a balloon, or a text block on open art. */
enum class BoxKind { BUBBLE, TEXT_BUBBLE, TEXT_FREE }

/** A model-found container in the bitmap's pixel space, before any recognition. */
data class TextBox(val bbox: IntRect, val kind: BoxKind, val confidence: Float)

/** Recognised lines plus the containers they were found in; [boxes] is empty for a recogniser with no container model. */
class PageDetection(val lines: List<TextLine>, val boxes: List<TextBox> = emptyList())

/** Finds text lines in a page. Coordinates are in the bitmap's own pixel space. */
interface TextDetector {
    suspend fun detect(bitmap: Bitmap, lang: Lang): List<TextLine>

    /** Lines together with the boxes a container model found; the default has no such model. */
    suspend fun detectPage(bitmap: Bitmap, lang: Lang): PageDetection = PageDetection(detect(bitmap, lang))

    /**
     * As [detectPage], but containers for which [skipRead] is true are not recognised (they are still reported in
     * [PageDetection.boxes]): the caller already knows it will discard them, and per-crop OCR is the slow stage.
     */
    suspend fun detectPage(bitmap: Bitmap, lang: Lang, skipRead: (TextBox) -> Boolean): PageDetection = detectPage(bitmap, lang)

    /**
     * With [read] false, nothing is recognised: each recognition target comes back as one [TextLine.UNREAD] line over
     * its box, for an engine that reads the crops itself. A detector without a container model has no boxes to hand
     * back and reads as usual.
     */
    suspend fun detectPage(bitmap: Bitmap, lang: Lang, skipRead: (TextBox) -> Boolean, read: Boolean): PageDetection =
        detectPage(bitmap, lang, skipRead)

    /** Instantiates the recognizer ahead of the first page so image #1 does not pay the setup cost. */
    suspend fun warmUp(lang: Lang)

    /** [recognizers] false: only what [detectPage] with `read = false` needs (the container model). */
    suspend fun warmUp(lang: Lang, recognizers: Boolean) = warmUp(lang)

    fun close()
}

/**
 * ML Kit Text Recognition v2, bundled artifacts, one script-specific recognizer per source language.
 * Recognizers are cached because construction is the expensive part.
 */
@Singleton
class MlKitTextDetector @Inject constructor() : TextDetector {
    private enum class Script { LATIN, JAPANESE, KOREAN, CHINESE }

    private val clients = HashMap<Script, TextRecognizer>()

    private fun scriptFor(lang: Lang) = when (lang) {
        Lang.JA -> Script.JAPANESE
        Lang.KO -> Script.KOREAN
        Lang.ZH, Lang.ZH_TW -> Script.CHINESE
        else -> Script.LATIN
    }

    private fun client(lang: Lang): TextRecognizer = synchronized(clients) {
        clients.getOrPut(scriptFor(lang)) {
            when (scriptFor(lang)) {
                Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
        }
    }

    override suspend fun warmUp(lang: Lang) {
        // Running a tiny image through the recognizer forces model load.
        val probe = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try { client(lang).process(InputImage.fromBitmap(probe, 0)).await() } catch (_: Exception) { }
    }

    override suspend fun detect(bitmap: Bitmap, lang: Lang): List<TextLine> {
        val result: Text = client(lang).process(InputImage.fromBitmap(bitmap, 0)).await()
        val out = ArrayList<TextLine>()
        for (block in result.textBlocks) for (line in block.lines) {
            val b = line.boundingBox ?: continue
            val text = line.text.trim()
            if (text.isEmpty()) continue
            val rect = IntRect(b.left, b.top, b.right, b.bottom)
            val vertical = lang.mayBeVertical && isVertical(line, rect)
            out += TextLine(bbox = rect, text = text, vertical = vertical, angle = uprightTilt(line.angle), confidence = line.confidence)
        }
        return out
    }

    /** Vertical CJK text reaches us as tall, narrow lines whose elements stack top-to-bottom. */
    private fun isVertical(line: Text.Line, rect: IntRect): Boolean {
        if (line.text.length < 2) return rect.height > rect.width * 1.8f
        if (rect.height <= rect.width * 1.3f) return false
        val els = line.elements
        if (els.size >= 2) {
            val first = els.first().boundingBox; val last = els.last().boundingBox
            if (first != null && last != null) return last.top - first.top > (last.left - first.left)
        }
        return true
    }

    override fun close() {
        synchronized(clients) { clients.values.forEach { it.close() }; clients.clear() }
    }
}

/**
 * ML Kit's line angle as a tilt from upright, within ±45°. A vertical Japanese column comes back at about ±90°,
 * which read as scene text lying on its side: the translation was drawn turned 90°, tiny and unreadable. The
 * translation is never laid on its side (nor upside down), so only the tilt off the nearest axis is kept.
 */
internal fun uprightTilt(angle: Float): Float {
    var a = angle % 90f
    if (a > 45f) a -= 90f
    if (a <= -45f) a += 90f
    return a + 0f   // -0.0 → 0.0
}
