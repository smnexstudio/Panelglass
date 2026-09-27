package com.smnexstudio.panelglass.core.engine.mt

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
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
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** Progress of the "Google Translate" (ML Kit) language packs for every language the app supports. */
data class PackState(
    val downloaded: Set<String> = emptySet(),
    val total: Int = LanguagePackStore.ALL_TAGS.size,
    val downloading: Boolean = false,
    /** The pack being fetched right now, for the progress line. */
    val current: String? = null,
    val failed: String? = null,
) {
    val complete: Boolean get() = downloaded.size >= total
}

/** The ML Kit model manager behind an interface so JVM tests can script it. */
interface PackManager {
    suspend fun downloaded(): Set<String>
    suspend fun download(tag: String)
    suspend fun delete(tag: String)
}

/**
 * Downloads and deletes ML Kit translation packs for all of [Lang] (~30 MB each; English is built in).
 * Runs in its own scope so leaving Settings does not cancel a download; packs already present are skipped,
 * so "Download all" is resumable. Over any network — same rule as the Qwen model.
 */
@Singleton
class LanguagePackStore @Inject constructor() {
    var manager: PackManager = MlKitPackManager()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow(PackState())
    val state: StateFlow<PackState> = _state

    /** Re-reads what ML Kit has on disk. */
    fun refresh() {
        scope.launch {
            val have = runCatching { manager.downloaded() }.getOrDefault(emptySet())
            _state.update { it.copy(downloaded = have intersect ALL_TAGS) }
        }
    }

    fun downloadAll() {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.update { it.copy(downloading = true, failed = null) }
            try {
                val have = runCatching { manager.downloaded() }.getOrDefault(emptySet()).toMutableSet()
                _state.update { it.copy(downloaded = have intersect ALL_TAGS) }
                for (tag in ALL_TAGS) {
                    if (tag in have) continue
                    _state.update { it.copy(current = tag) }
                    manager.download(tag)
                    have += tag
                    _state.update { it.copy(downloaded = have intersect ALL_TAGS) }
                }
                _state.update { it.copy(downloading = false, current = null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(downloading = false, current = null) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(downloading = false, current = null, failed = "Download failed") }
            }
        }
    }

    fun cancel() { job?.cancel(); job = null; _state.update { it.copy(downloading = false, current = null) } }

    /** Removes every pack; the next translation with that pair downloads it again on demand. */
    fun deleteAll() {
        cancel()
        scope.launch {
            for (tag in ALL_TAGS) runCatching { manager.delete(tag) }
            refresh()
        }
    }

    companion object {
        /** Distinct ML Kit tags for the app's languages (both Chinese variants share one model). */
        val ALL_TAGS: Set<String> = Lang.entries.map { GoogleTranslateEngine.tag(it) }.toSet()

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
