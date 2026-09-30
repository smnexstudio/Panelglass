package com.smnexstudio.panelglass.core.engine.mt

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.smnexstudio.panelglass.core.model.Lang
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** The "Google Translate" (ML Kit) language packs on the phone, and the ones being fetched. Tags are ML Kit tags. */
data class PackState(
    val downloaded: Set<String> = emptySet(),
    /** Asked for and not finished yet, the one in [current] included. */
    val pending: Set<String> = emptySet(),
    /** The pack being fetched right now. */
    val current: String? = null,
    /** The last pack that failed to download, until it is asked for again. */
    val failed: String? = null,
) {
    fun has(tag: String): Boolean = tag == LanguagePackStore.BUILT_IN || tag in downloaded
}

/** The ML Kit model manager behind an interface so JVM tests can script it. */
interface PackManager {
    suspend fun downloaded(): Set<String>
    suspend fun download(tag: String)
    suspend fun delete(tag: String)
}

/** Which of a pair's packs may not be fetched on the fly: the engine asks before its first use of a pair. */
fun interface PackGate {
    /** The tags of [tags] that are missing and must be downloaded by the user first; empty when the pair may run. */
    suspend fun missing(tags: Collection<String>): Set<String>

    companion object {
        /** Everything allowed: what a translator uses when nothing is bound (JVM tests). */
        val OPEN = PackGate { emptySet() }
    }
}

/**
 * Downloads and deletes ML Kit translation packs (~30 MB each; English is built in).
 *
 * Japanese, Korean and Chinese are [AUTOMATIC]: fetched once after install ([ensureAutomatic]) and, should one be
 * missing, fetched on first use. Every other language is downloaded only when the user asks (Settings, or the
 * one-tap Download the reader and the Try box offer on [com.smnexstudio.panelglass.core.model.EngineFailure.PackMissing]),
 * so nobody stores Hindi, Spanish or Arabic without using them. Downloads run one at a time in the store's own scope,
 * so leaving a screen does not cancel them.
 */
@Singleton
class LanguagePackStore @Inject constructor() : PackGate {
    var manager: PackManager = MlKitPackManager()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Mutex()
    private val jobs = ArrayList<Job>()

    private val _state = MutableStateFlow(PackState())
    val state: StateFlow<PackState> = _state

    /** Re-reads what ML Kit has on disk. */
    fun refresh() {
        scope.launch { onDisk() }
    }

    /** Queues [tags] for download (those already present are skipped). */
    fun download(tags: Collection<String>) {
        val want = tags.filter { it != BUILT_IN }.toSet()
        if (want.isEmpty()) return
        _state.update { it.copy(pending = it.pending + want, failed = null) }
        track(scope.launch { fetch(want) })
    }

    /** Downloads [tags] and waits for them: true when every one is on the phone afterwards. */
    suspend fun downloadNow(tags: Collection<String>): Boolean {
        val want = tags.filter { it != BUILT_IN }.toSet()
        if (want.isEmpty()) return true
        _state.update { it.copy(pending = it.pending + want, failed = null) }
        return fetch(want)
    }

    /**
     * Fetches the [AUTOMATIC] packs still missing, over any network. The caller runs it at first start and remembers
     * that it succeeded, so a pack the user deletes later is not brought back.
     */
    suspend fun ensureAutomatic(): Boolean = downloadNow(AUTOMATIC)

    fun delete(tag: String) {
        if (tag == BUILT_IN) return
        scope.launch {
            queue.withLock { runCatching { manager.delete(tag) } }
            onDisk()
        }
    }

    fun cancel() {
        synchronized(jobs) { jobs.forEach { it.cancel() }; jobs.clear() }
        _state.update { it.copy(pending = emptySet(), current = null) }
    }

    override suspend fun missing(tags: Collection<String>): Set<String> {
        val need = tags.filter { it != BUILT_IN && it !in AUTOMATIC }.toSet()
        if (need.isEmpty()) return emptySet()
        return need - onDisk()
    }

    private suspend fun fetch(want: Set<String>): Boolean = queue.withLock {
        var ok = true
        val have = onDisk().toMutableSet()
        for (tag in want) {
            if (tag in have) { _state.update { it.copy(pending = it.pending - tag) }; continue }
            _state.update { it.copy(current = tag) }
            try {
                manager.download(tag)
                have += tag
                _state.update { it.copy(downloaded = it.downloaded + tag, pending = it.pending - tag, current = null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(pending = it.pending - want, current = null) }
                throw e
            } catch (e: Exception) {
                ok = false
                _state.update { it.copy(pending = it.pending - tag, current = null, failed = tag) }
            }
        }
        ok
    }

    private suspend fun onDisk(): Set<String> {
        val have = runCatching { manager.downloaded() }.getOrDefault(_state.value.downloaded)
        _state.update { it.copy(downloaded = have) }
        return have
    }

    private fun track(job: Job) {
        synchronized(jobs) { jobs.removeAll { !it.isActive }; jobs += job }
    }

    companion object {
        /** ML Kit ships English inside the library: never downloaded, never deleted. */
        const val BUILT_IN: String = TranslateLanguage.ENGLISH

        /** Fetched without asking: the languages most manga, manhwa and manhua are read from. */
        val AUTOMATIC: Set<String> = setOf(TranslateLanguage.JAPANESE, TranslateLanguage.KOREAN, TranslateLanguage.CHINESE)

        /** Distinct ML Kit tags for the app's languages (both Chinese variants share one model), in [Lang] order. */
        val ALL_TAGS: List<String> = Lang.entries.map { GoogleTranslateEngine.tag(it) }.distinct()

        fun displayName(tag: String): String = Lang.entries.firstOrNull { GoogleTranslateEngine.tag(it) == tag }?.displayName ?: tag
    }
}

private class MlKitPackManager : PackManager {
    // Lazy: RemoteModelManager needs the ML Kit context, which only exists inside a running app.
    private val rm by lazy { RemoteModelManager.getInstance() }
    private fun model(tag: String) = TranslateRemoteModel.Builder(tag).build()

    override suspend fun downloaded(): Set<String> =
        rm.getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }.toSet()

    override suspend fun download(tag: String) { rm.download(model(tag), DownloadConditions.Builder().build()).await() }
    override suspend fun delete(tag: String) { rm.deleteDownloadedModel(model(tag)).await() }
}
