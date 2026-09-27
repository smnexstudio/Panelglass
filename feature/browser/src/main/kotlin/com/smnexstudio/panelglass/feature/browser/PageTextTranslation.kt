package com.smnexstudio.panelglass.feature.browser

import com.smnexstudio.panelglass.core.engine.mt.PageTranslator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** The reader's translate bar: Chrome-style "Detect language → English" for the page's own text. */
data class PageTextState(
    /** The bar is showing. */
    val open: Boolean = false,
    /** The page is (being) shown translated; new text is translated as it appears. */
    val on: Boolean = false,
    /** Chosen source, an ML Kit tag; null = detect. */
    val srcTag: String? = null,
    /** What detection found on this page (shown as "Detected: …"). */
    val detectedTag: String? = null,
    val tgtTag: String = "en",
    val status: Status = Status.IDLE,
) {
    enum class Status { IDLE, DETECTING, DOWNLOADING, TRANSLATING, DONE, SAME_LANGUAGE, UNDETECTED, FAILED }
}

/**
 * Translates the page's text in place with ML Kit (`pt.js` collects and writes the text nodes). Batches go out on
 * screen first; afterwards the page is polled for new text (infinite scroll, lazy lists) while the translation is on.
 * [eval] runs a script in the page and returns its JSON result; [onTextChanged] is told when the page's text (and so
 * possibly its layout) changed, so page-space screen patches can be dropped.
 */
class PageTextTranslation(
    private val scope: CoroutineScope,
    private val translator: PageTranslator,
    private val eval: suspend (String) -> String?,
    private val onTextChanged: () -> Unit,
    private val saveTarget: suspend (String) -> Unit,
) {
    private val _state = MutableStateFlow(PageTextState())
    val state: StateFlow<PageTextState> = _state
    private var job: Job? = null

    fun setTarget(tag: String) { _state.update { it.copy(tgtTag = tag) } }

    /** Menu "Translate page": open the bar and translate at once, as Chrome does. */
    fun open() {
        _state.update { it.copy(open = true) }
        translate()
    }

    fun translate() {
        _state.update { it.copy(on = true) }
        restart(restore = false)
    }

    fun showOriginal() {
        job?.cancel(); job = null
        _state.update { it.copy(on = false, status = PageTextState.Status.IDLE) }
        scope.launch { eval("window.__pt && __pt.restore()"); onTextChanged() }
    }

    fun close() {
        if (_state.value.on) showOriginal()
        _state.update { it.copy(open = false) }
    }

    /** Picking either language translates with it, as in Chrome. */
    fun pickSource(tag: String?) {
        _state.update { it.copy(srcTag = tag, on = true) }
        restart(restore = true)
    }

    fun pickTarget(tag: String) {
        _state.update { it.copy(tgtTag = tag) }
        scope.launch { saveTarget(tag) }
        _state.update { it.copy(on = true) }
        restart(restore = true)
    }

    /** A new page is loading: its text is new, and the old page's detection no longer applies. */
    fun onPageStarted() {
        job?.cancel(); job = null
        _state.update { it.copy(detectedTag = null, status = if (it.on) PageTextState.Status.DETECTING else it.status) }
    }

    /** The new page is up: carry the translation over to it, as Chrome does within a site. */
    fun onPageFinished() { if (_state.value.on) restart(restore = false) }

    private fun restart(restore: Boolean) {
        job?.cancel()
        job = scope.launch {
            if (restore) eval("window.__pt && __pt.restore()")
            try { translatePage() } catch (e: CancellationException) { throw e } catch (e: Exception) { status(PageTextState.Status.FAILED) }
        }
    }

    private suspend fun translatePage() {
        val s = _state.value
        val src = s.srcTag ?: run {
            status(PageTextState.Status.DETECTING)
            val sample = eval("window.__pt ? __pt.sample() : null")?.let { runCatching { JSONObject(it) }.getOrNull() }
            translator.detect(sample?.optString("text").orEmpty(), sample?.optString("lang"))
                .also { found -> _state.update { it.copy(detectedTag = found) } }
                ?: return status(PageTextState.Status.UNDETECTED)
        }
        val tgt = s.tgtTag
        if (src == tgt) return status(PageTextState.Status.SAME_LANGUAGE)
        status(PageTextState.Status.DOWNLOADING)
        translator.prepare(src, tgt)
        status(PageTextState.Status.TRANSLATING)
        val pad = src in UNSPACED && tgt !in UNSPACED
        var first = true
        while (true) {
            val batch = collect()
            if (batch.isEmpty()) {
                if (_state.value.status == PageTextState.Status.TRANSLATING) status(PageTextState.Status.DONE)
                delay(POLL_MS)
                continue
            }
            // Written in as each few texts are done: ML Kit translates one text at a time, so a whole batch would
            // keep the page unchanged for seconds (minutes on an emulator's CPU).
            for (group in batch.chunked(APPLY_GROUP)) {
                val out = translator.translate(src, tgt, group.map { it.second })
                val items = JSONArray()
                group.forEachIndexed { i, (id, _) -> items.put(JSONObject().put("id", id).put("text", out[i])) }
                eval("window.__pt && __pt.apply($items, $pad)")
                if (first) { first = false; onTextChanged() }
            }
        }
    }

    private suspend fun collect(): List<Pair<Int, String>> {
        val arr = eval("window.__pt ? __pt.collect($BATCH_CHARS) : []")?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { it.optInt("id") to it.optString("text") } }
    }

    private fun status(v: PageTextState.Status) = _state.update { it.copy(status = v) }

    private companion object {
        /** Characters per batch: a screenful of titles and links, small enough to show within a second or two. */
        const val BATCH_CHARS = 3_000
        /** Languages written without spaces between words: their inline pieces need one once translated. */
        val UNSPACED = setOf("ja", "zh", "th", "lo", "my", "km")
        /** Texts written into the page together. */
        const val APPLY_GROUP = 6
        /** How often a translated page is checked for new text (cheap in the page when nothing changed). */
        const val POLL_MS = 1_500L
    }
}
