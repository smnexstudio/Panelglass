package com.smnexstudio.panelglass.core.engine

import android.content.Context
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.TextNorm
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * First lookup tier for sound effects: bundled `assets/sfx_ja.json` / `sfx_ko.json` mapping common
 * onomatopoeia to English comic equivalents. Keys are normalised with [TextNorm.sfxKey].
 */
@Singleton
class SfxDictionary @Inject constructor(@ApplicationContext private val context: Context) {
    private val tables = HashMap<Lang, Map<String, String>>()

    fun lookup(text: String, src: Lang, tgt: Lang): String? {
        if (tgt != Lang.EN) return null // the bundled tables are English-only
        val table = table(src) ?: return null
        val key = TextNorm.sfxKey(text)
        table[key]?.let { return it }
        // Elongation marks and repeats: ドドドド → ドド, ゴゴゴ → ゴゴ.
        val collapsed = collapseRepeats(key)
        return table[collapsed]
    }

    private fun table(src: Lang): Map<String, String>? {
        val asset = when (src) { Lang.JA -> "sfx_ja.json"; Lang.KO -> "sfx_ko.json"; else -> return null }
        synchronized(tables) {
            tables[src]?.let { return it }
            val loaded = runCatching {
                val text = context.assets.open(asset).bufferedReader().use { it.readText() }
                Json.parseToJsonElement(text).jsonObject.entries.associate { (k, v) -> TextNorm.sfxKey(k) to v.jsonPrimitive.content }
            }.getOrDefault(emptyMap())
            tables[src] = loaded
            return loaded
        }
    }

    private fun collapseRepeats(s: String): String {
        if (s.length < 3) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            var run = 1
            while (i + run < s.length && s[i + run] == c) run++
            sb.append(c); if (run >= 2) sb.append(c)
            i += run
        }
        return sb.toString()
    }
}
