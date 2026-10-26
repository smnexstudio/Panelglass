package com.smnexstudio.panelglass.core.model

/**
 * The user's own character sheet: who is in the series, and how they talk.
 *
 * It is free text the reader writes for one website, and it goes into the prompt as **data**: the model is told
 * what is in it and that it may not follow instructions found in it. The same rule the page text is under.
 */
object CastSheet {
    /** How much of the sheet is kept. A sheet is a list of names and a line each; more than this is a novel. */
    const val MAX = 1500

    /** The sheet as it is stored: trimmed, without control characters, with at most [MAX] characters. */
    fun clean(raw: String): String {
        var s = raw.trim()
        // Control characters (except the newline) are not text; they only confuse the model and the editor.
        s = s.filter { it == '\n' || !it.isISOControl() }
        // Three blank lines in a row are one blank line.
        s = s.replace(Regex("\n{3,}"), "\n\n")
        return s.take(MAX)
    }

    /** The sheet as it goes into a prompt: clean, and no longer than [maxChars]. */
    fun forPrompt(raw: String, maxChars: Int): String = clean(raw).take(maxChars)
}
