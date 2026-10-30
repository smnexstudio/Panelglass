package com.smnexstudio.panelglass.core.engine.local

/** Removes tagged reasoning, including an unfinished reasoning block in streamed output. */
internal object LocalReply {
    private val tags = Regex("</?(?:think|analysis)>", RegexOption.IGNORE_CASE)

    fun visible(raw: String): String {
        val out = StringBuilder()
        var cursor = 0
        var depth = 0
        for (tag in tags.findAll(raw)) {
            if (depth == 0) out.append(raw.substring(cursor, tag.range.first))
            if (tag.value.startsWith("</")) {
                if (depth == 0) out.setLength(0) else depth--
            } else depth++
            cursor = tag.range.last + 1
        }
        if (depth == 0) out.append(raw.substring(cursor))
        return out.toString().trim()
    }
}
