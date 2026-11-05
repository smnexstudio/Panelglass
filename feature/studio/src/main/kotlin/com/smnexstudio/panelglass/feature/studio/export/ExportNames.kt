package com.smnexstudio.panelglass.feature.studio.export

import com.smnexstudio.panelglass.core.model.Chapter
import com.smnexstudio.panelglass.core.model.ComicInfo
import com.smnexstudio.panelglass.core.model.Manga

/**
 * Names on disk for an export: `<Manga>/<Ch 012 - Title>/001.png … ComicInfo.xml` (docs/STUDIO_PLAN.md › Export).
 * Every name is sanitised for every file system a folder may sit on (FAT on SD cards included): no path separators,
 * no reserved characters, no trailing dots or spaces, no Windows device names, at most [MAX_NAME] characters.
 */
object ExportNames {
    const val MAX_NAME = 80
    const val COMIC_INFO = "ComicInfo.xml"
    /**
     * The manga folder's cover, beside its chapters: what Mihon's local source (and Kavita, Komga) shows for the
     * series. Without it Mihon showed none.
     */
    const val COVER = "cover.jpg"
    /** Ends a clean export's chapter name, so it never replaces the translated export beside it. */
    const val CLEAN_SUFFIX = " (Clean)"

    private val reserved = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val spaces = Regex("\\s+")
    private val devices = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
    private val pageFile = Regex("^\\d+\\.(png|jpg)$")

    /** [name] made safe as one file or folder name; [fallback] when nothing is left. */
    fun sanitize(name: String, fallback: String): String {
        var s = name.replace(reserved, " ").replace(spaces, " ").trim().trimEnd('.', ' ').trimStart('.', ' ')
        if (s.length > MAX_NAME) s = s.take(MAX_NAME).trimEnd('.', ' ')
        if (s.substringBefore('.').uppercase() in devices) s = "_$s"
        return s.ifEmpty { fallback }
    }

    /** The manga's folder. */
    fun mangaFolder(m: Manga): String = sanitize(m.title, "Manga")

    /**
     * The chapter's folder: `Ch 012 - Title`, `Ch 012.5`, or the title alone for a chapter without a number; [position]
     * (1-based, in the manga's order) names one with neither, so two never share a folder. A [clean] export's name ends
     * in [CLEAN_SUFFIX], kept whole however long the title.
     */
    fun chapterFolder(c: Chapter, position: Int, clean: Boolean = false): String {
        val name = chapterName(c, position)
        if (!clean) return name
        return name.take(MAX_NAME - CLEAN_SUFFIX.length).trimEnd('.', ' ') + CLEAN_SUFFIX
    }

    private fun chapterName(c: Chapter, position: Int): String {
        val number = c.numberLabel?.let { n ->
            val whole = n.substringBefore('.')
            val frac = n.substringAfter('.', "")
            "Ch " + whole.padStart(3, '0') + if (frac.isEmpty()) "" else ".$frac"
        }
        val title = c.title.trim()
        val raw = when {
            number != null && title.isNotEmpty() -> "$number - $title"
            number != null -> number
            title.isNotEmpty() -> title
            else -> "Chapter $position"
        }
        return sanitize(raw, "Chapter $position")
    }

    /** A chapter exported as one archive, beside the others in the manga's folder: `Ch 012 - Title.cbz`. */
    fun archiveFile(c: Chapter, position: Int, format: ExportFormat, clean: Boolean = false): String =
        chapterFolder(c, position, clean) + "." + format.extension

    /** Page [index] (0-based) of [count]: `001.png`, wider when the chapter has 1000 pages or more. */
    fun pageFile(index: Int, count: Int, extension: String = "png"): String =
        (index + 1).toString().padStart(maxOf(3, count.toString().length), '0') + "." + extension

    /**
     * A page file an earlier export wrote (`001.png`, `001.jpg`): replaced on overwrite, so no stale page is left
     * behind, nor the other format's copy.
     */
    fun isPageFile(name: String): Boolean = pageFile.matches(name)

    /** The chapter's ComicInfo.xml, from the manga's and chapter's details; [clean] pages have no text, so no language. */
    fun comicInfo(m: Manga, c: Chapter, pages: Int, clean: Boolean = false): ComicInfo {
        val tgt = c.language ?: m.tgtLang
        val date = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").matchEntire(c.releaseDate.trim())?.groupValues
        return ComicInfo(
            series = m.title,
            localizedSeries = m.altTitle,
            number = c.numberLabel.orEmpty(),
            volume = c.volume,
            title = c.title,
            summary = m.summary,
            writer = m.author,
            penciller = m.artist,
            genre = m.genres,
            languageIso = if (clean) "" else tgt.code,
            pageCount = pages,
            year = date?.get(1)?.toIntOrNull(),
            month = date?.get(2)?.toIntOrNull(),
            day = date?.get(3)?.toIntOrNull(),
            notes = c.notes,
            // The pages keep the original art's reading direction.
            manga = if (m.srcLang.rtlReading) "YesAndRightToLeft" else "",
            publishingStatus = ComicInfo.publishingStatusOf(m.status),
        )
    }

    /**
     * The manga folder's own ComicInfo.xml: the series' details without any chapter's. Mihon's local source reads the
     * series from this file (it copies one out of a chapter only when there is none), so it is written on every export
     * and a changed status, summary or genre reaches the reader.
     */
    fun seriesInfo(m: Manga, clean: Boolean = false): ComicInfo = ComicInfo(
        series = m.title,
        localizedSeries = m.altTitle,
        summary = m.summary,
        writer = m.author,
        penciller = m.artist,
        genre = m.genres,
        languageIso = if (clean) "" else m.tgtLang.code,
        manga = if (m.srcLang.rtlReading) "YesAndRightToLeft" else "",
        publishingStatus = ComicInfo.publishingStatusOf(m.status),
    )
}
