package com.smnexstudio.panelglass.core.engine.local

import android.content.Context
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.RegionItem
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.TranslateRequest
import java.io.File

/**
 * Debug tool, inert unless `files/debug-llm-compare` exists: translates the same lines with every installed on-device
 * model, one after the other and outside the pipeline (no watchdog), and writes each model's lines and timings to
 * `files/debug-llm-compare.out` and the `LlmCompare` log tag. The trigger file may hold its own lines: a first line
 * `JA EN` (source and target `Lang` names) then one source line each; empty means the built-in set below.
 *
 *   adb shell run-as com.smnexstudio.panelglass touch files/debug-llm-compare
 *   (start the app, wait)  adb shell run-as com.smnexstudio.panelglass cat files/debug-llm-compare.out
 */
object LlmCompare {
    private const val TAG = "LlmCompare"

    /** Lines from the chapter proofread in September 2026, where Qwen 0.5B (since removed) and ML Kit both went wrong. */
    private val DEFAULT = listOf(
        Triple(Lang.JA, Lang.EN, listOf(
            "レントちゃんの生み出したアイテムなんでしょ?",
            "そういやレントの姿が見えねぇな",
            "いや知らねぇな",
            "数年前各地の教会を燃やした集団の首謀者",
            "その男の本名がヴァンジェだ",
            "でもそんな名前聞いたことねぇぞ",
            "後悔するヒマがあるなら奴の所にすぐ向かってやれ",
        )),
        Triple(Lang.JA, Lang.HI, listOf("いや知らねぇな", "その男の本名がヴァンジェだ")),
        Triple(Lang.KO, Lang.EN, listOf("예로부터 중원은 싸움이 끊이지 않는 곳이었다.")),
    )

    fun trigger(context: Context): File? = File(context.filesDir, "debug-llm-compare").takeIf { it.isFile }

    suspend fun run(context: Context, engines: LocalEngines, stores: ModelStores) {
        val trigger = trigger(context) ?: return
        val sets = parse(trigger.readLines()) ?: DEFAULT
        val out = File(context.filesDir, "debug-llm-compare.out")
        out.writeText("")
        fun log(line: String) { android.util.Log.i(TAG, line); out.appendText(line + "\n") }
        for (model in LocalModel.entries) {
            if (!stores[model].isReady) { log("== ${model.label}: not installed"); continue }
            val engine = engines[model]
            val t0 = System.currentTimeMillis()
            runCatching { engine.warmUp(Lang.JA, Lang.EN) }
            log("== ${model.label} load+warm-up ${System.currentTimeMillis() - t0} ms")
            for ((src, tgt, lines) in sets) {
                val items = lines.mapIndexed { i, t -> RegionItem(i, "compare", RegionKind.ENCLOSED, t) }
                val t1 = System.currentTimeMillis()
                val result = runCatching { engine.translate(TranslateRequest(items, src, tgt)) }
                val ms = System.currentTimeMillis() - t1
                result.onSuccess { got ->
                    log("-- ${src.name}>${tgt.name} ${lines.size} lines in $ms ms")
                    for (item in items) log("  ${item.text}\n    => ${got.firstOrNull { it.i == item.i }?.text}")
                }.onFailure { log("-- ${src.name}>${tgt.name} FAILED after $ms ms: ${it::class.simpleName}: ${it.message}") }
            }
            engine.release()
        }
        log("== done")
    }

    private fun parse(lines: List<String>): List<Triple<Lang, Lang, List<String>>>? {
        val body = lines.map { it.trim() }.filter { it.isNotEmpty() }
        if (body.size < 2) return null
        val head = body.first().split(Regex("\\s+"))
        val src = head.getOrNull(0)?.let { runCatching { Lang.valueOf(it) }.getOrNull() } ?: return null
        val tgt = head.getOrNull(1)?.let { runCatching { Lang.valueOf(it) }.getOrNull() } ?: return null
        return listOf(Triple(src, tgt, body.drop(1)))
    }
}
