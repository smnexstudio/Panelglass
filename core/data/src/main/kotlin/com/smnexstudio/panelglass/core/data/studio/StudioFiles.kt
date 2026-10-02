package com.smnexstudio.panelglass.core.data.studio

import java.io.File
import java.util.UUID

/**
 * Where Studio pages live: `filesDir/studio/pages/<key>/original.<ext>`, with the page's layers (`clean.png`,
 * `mask.png`) beside it. Once imported, a page never depends on the picker's URI (its permission can be revoked).
 * A page keeps its directory when it is reordered or moved to another chapter, so only a delete touches the files.
 */
class StudioFiles(val root: File) {
    /** Directories an import is still writing: not in the database yet, and not orphans. */
    private val pending = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * A fresh, empty page directory and its Studio-relative path (`pages/<key>`). It stays pending, safe from
     * [sweepOrphans], until [settle] is called once its page row is saved or the import dropped it.
     */
    fun newPageDir(): Pair<String, File> {
        val rel = "$PAGES/" + UUID.randomUUID().toString().replace("-", "")
        val dir = File(root, rel)
        dir.mkdirs()
        pending += rel
        return rel to dir
    }

    fun settle(rels: Collection<String>) {
        pending.removeAll(rels.toSet())
    }

    /** A Studio-relative path as a file. Only paths under the Studio root resolve; anything else is refused. */
    fun file(rel: String): File {
        val f = File(root, rel).canonicalFile
        require(f.path.startsWith(root.canonicalFile.path + File.separator)) { "outside the Studio root" }
        return f
    }

    /** Deletes a page directory (`pages/<key>`, or a file inside it: its directory goes). */
    fun deletePageDir(relFileOrDir: String) {
        val rel = if (relFileOrDir.count { it == '/' } >= 2) relFileOrDir.substringBeforeLast('/') else relFileOrDir
        if (!rel.startsWith("$PAGES/")) return
        runCatching { file(rel).deleteRecursively() }
    }

    /**
     * The manga's own cover, when the user chose one (`covers/<mangaId>.jpg`, 2 : 3); without it the first page of
     * the first chapter is shown.
     */
    fun cover(mangaId: Long): File = File(root, "$COVERS/$mangaId.jpg")

    fun deleteCover(mangaId: Long) { cover(mangaId).delete() }

    /** Bytes a page directory holds (`pages/<key>`, or a file inside it: its directory): the original and its layers. */
    fun pageBytes(relFileOrDir: String): Long {
        val rel = if (relFileOrDir.count { it == '/' } >= 2) relFileOrDir.substringBeforeLast('/') else relFileOrDir
        if (!rel.startsWith("$PAGES/")) return 0
        return runCatching { file(rel).walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0)
    }

    /**
     * Removes page directories no page row points at: what a killed import left behind. [known] are the page rows'
     * directories (`pages/<key>`).
     */
    fun sweepOrphans(known: Set<String>): Int {
        val dirs = File(root, PAGES).listFiles() ?: return 0
        var removed = 0
        for (d in dirs) {
            val rel = "$PAGES/${d.name}"
            if (rel !in known && rel !in pending && d.deleteRecursively()) removed++
        }
        return removed
    }

    companion object {
        const val PAGES = "pages"
        const val COVERS = "covers"
        const val ORIGINAL = "original"
        /** The page with the source text removed (rebuilt from the original, the bubbles and the strokes). */
        const val CLEAN = "clean.png"
        /** The page's brush strokes, as JSON (`PageStrokes`). */
        const val STROKES = "strokes.json"

        /** A layer file of the page whose original is [pageFile] (`pages/<key>/original.png` → `pages/<key>/clean.png`). */
        fun layer(pageFile: String, name: String) = pageFile.substringBeforeLast('/') + "/" + name
    }
}
