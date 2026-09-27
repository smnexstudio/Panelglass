package com.smnexstudio.panelglass.core.pipeline

import android.content.Context
import android.graphics.Bitmap
import com.smnexstudio.panelglass.core.model.Patch
import com.smnexstudio.panelglass.core.model.TextRegion
import java.io.File
import java.io.FileOutputStream

/**
 * Developer dump of one pipeline run: the source bitmap, every region with what was read and rendered, and each
 * patch, written under `files/debug-dump/<run>/`. Off unless that directory exists (`run-as … mkdir
 * files/debug-dump`), so a shipped build never writes page content to disk.
 */
class PipelineDump private constructor(private val dir: File) {
    private val lines = StringBuilder()

    fun page(bitmap: Bitmap?) {
        if (bitmap == null) return
        runCatching { FileOutputStream(File(dir, "page.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    fun region(i: Int, r: TextRegion, seed: Any?, translation: String?) {
        lines.append("#$i kind=${r.kind} seed=$seed bbox=${r.bbox} container=${r.container} vertical=${r.vertical} ")
            .append("font=${"%.1f".format(r.fontSizePx)} fg=${"%08x".format(r.fgColor)} bg=${"%08x".format(r.bgColor)} lines=${r.lines.size}\n")
        for (l in r.lines) lines.append("    line bbox=${l.bbox} font=${l.fontSizePx} text=${l.text}\n")
        lines.append("    text=${r.text}\n    translation=${translation}\n")
    }

    fun note(i: Int, s: String) { lines.append("#$i $s\n") }

    fun patch(i: Int, p: Patch) {
        runCatching { File(dir, "patch-$i.webp").writeBytes(p.webp) }
        lines.append("#$i patch x=${"%.2f".format(p.xPct)}% y=${"%.2f".format(p.yPct)}% w=${"%.2f".format(p.wPct)}% h=${"%.2f".format(p.hPct)}%\n")
    }

    fun close() { runCatching { File(dir, "regions.txt").writeText(lines.toString()) } }

    companion object {
        fun open(context: Context): PipelineDump? {
            val root = File(context.filesDir, "debug-dump")
            if (!root.isDirectory) return null
            val dir = File(root, System.currentTimeMillis().toString())
            return if (dir.mkdirs()) PipelineDump(dir) else null
        }
    }
}
