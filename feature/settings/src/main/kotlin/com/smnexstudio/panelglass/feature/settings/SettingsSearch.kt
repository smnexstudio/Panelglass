package com.smnexstudio.panelglass.feature.settings

import java.text.Normalizer
import java.util.Locale

/** The Settings hub and the pages it opens; [LICENCES] and [PRIVACY] are opened from [ABOUT]. */
enum class SettingsPage {
    HUB, ENGINE, LANGUAGES, MODELS, READER, STUDIO, STORAGE, APPEARANCE, ABOUT, LICENCES, PRIVACY;

    /** Where Back goes: About for the documents it opens, else the hub. */
    val parent: SettingsPage get() = when (this) {
        LICENCES, PRIVACY -> ABOUT
        else -> HUB
    }

    /** The hub row this page belongs to. */
    val top: SettingsPage get() = if (parent == HUB) this else parent
}

/** One searchable label: a page's title or a row on it, in the UI's language. */
data class SearchHit(val page: SettingsPage, val label: String)

/**
 * The hub's search: every label of [index] that holds [query], ignoring case and accents ("cafe" finds "Café"), each
 * label once, in the index's order (page by page, as the hub lists them). A blank query finds nothing: the hub then
 * shows its groups instead.
 */
fun searchSettings(query: String, index: List<SearchHit>): List<SearchHit> {
    val q = fold(query.trim())
    if (q.isEmpty()) return emptyList()
    return index.filter { fold(it.label).contains(q) }.distinctBy { it.page to it.label }
}

private val MARKS = Regex("\\p{Mn}+")

/** Lower case without combining marks, so search ignores case and accents. */
private fun fold(s: String): String = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
