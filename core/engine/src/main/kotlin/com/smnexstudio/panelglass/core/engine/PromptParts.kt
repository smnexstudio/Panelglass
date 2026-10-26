package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.model.ContextPair

/**
 * The pieces every prompt renders the same way: glossary terms and the chapter's preceding lines as `source => target`.
 * Each prompt keeps its own headings and layout (the on-device one is compact); only the renderings live here, so a
 * change to how a term or a line is shown reaches the cloud and the on-device prompts alike.
 */
object PromptParts {
    /** Preceding lines a prompt shows: enough to keep a conversation going, few enough to stay cheap. */
    const val CONTEXT_LINES = 2

    /** `- term => rendering` per glossary entry, one per line. */
    fun glossaryList(glossary: Map<String, String>): String = glossary.entries.joinToString("") { "- " + pair(it.key, it.value) + "\n" }

    /** `term => rendering; …` on one line. */
    fun glossaryInline(glossary: Map<String, String>): String = glossary.entries.joinToString("; ") { pair(it.key, it.value) }

    /** `- source => translation` per preceding line (the last [limit]), one per line. */
    fun context(prior: List<ContextPair>, limit: Int = CONTEXT_LINES): String =
        prior.takeLast(limit).joinToString("") { "- " + pair(it.source, it.translation) + "\n" }

    private fun pair(source: String, target: String) = "$source => $target"
}
