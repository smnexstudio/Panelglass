package com.smnexstudio.panelglass.core.ocr

import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.TextLine
import com.smnexstudio.panelglass.core.model.TextRegion

/** A region plus what the container model said about it; null when only proximity grouped it. */
class BuiltRegion(val region: TextRegion, val seed: BoxKind?)

/**
 * Regions from a [PageDetection]. Lines inside the same detector box are one translation unit — a
 * speech bubble's three columns become one sentence, which is worth more to the translator than any
 * prompt — and the box's class seeds the region kind: text in a balloon is `ENCLOSED` whatever the
 * ring test says; free text is left for the pixel classifier to sort into FREE / SFX / IN_SCENE / CAPTION.
 * Lines outside every box (the recall backstop) are clustered by proximity exactly as before.
 *
 * The region's bbox is the union of its lines, never the detector box: a loose box must not become
 * an erase rectangle.
 */
class RegionBuilder(private val clusterer: RegionClusterer = RegionClusterer()) {

    fun build(detection: PageDetection, lang: Lang): List<BuiltRegion> {
        // A line with no letter or digit ("……", "———") says nothing to translate; when OCR reads a stylised logo
        // it is also how the misread comes back, and merged with real text it made one page-wide region.
        val lines = detection.lines.filter { l -> l.text.any { it.isLetterOrDigit() } && !l.bbox.isEmpty }
        if (detection.boxes.isEmpty()) return clusterer.cluster(lines, lang).map { BuiltRegion(it, null) }

        // Tight text boxes claim lines first; balloons only take what no text box covered.
        val containers = detection.boxes.sortedBy { if (it.kind == BoxKind.BUBBLE) 1 else 0 }
        val byBox = HashMap<TextBox, MutableList<TextLine>>()
        val loose = ArrayList<TextLine>()
        for (line in lines) {
            val cx = line.bbox.centerX.toInt(); val cy = line.bbox.centerY.toInt()
            val box = containers.firstOrNull { it.bbox.contains(cx, cy) }
            if (box == null) loose += line else byBox.getOrPut(box) { ArrayList() } += line
        }

        val out = ArrayList<BuiltRegion>()
        val balloons = detection.boxes.filter { it.kind == BoxKind.BUBBLE }
        for ((box, members) in byBox) {
            val built = clusterer.build(members, lang)
            val seed = if (box.kind == BoxKind.TEXT_FREE) null else BoxKind.TEXT_BUBBLE
            // The balloon the text sits in is where the translation may spread out; the text box stays the erase area.
            val balloon = if (seed == null) null else balloons.firstOrNull { it.bbox.contains(box.bbox.centerX.toInt(), box.bbox.centerY.toInt()) }?.bbox
                ?: box.bbox.takeIf { box.kind == BoxKind.BUBBLE }
            out += BuiltRegion(built.copy(container = balloon), seed)
        }
        for (r in clusterer.cluster(loose, lang)) out += BuiltRegion(r, null)
        return out
    }

    companion object {
        /** Applies the seed after pixel classification: balloon text keeps the classifier's colours, not its kind. */
        fun seeded(region: TextRegion, seed: BoxKind?): TextRegion =
            if (seed == BoxKind.TEXT_BUBBLE || seed == BoxKind.BUBBLE) region.copy(kind = RegionKind.ENCLOSED) else region
    }
}
