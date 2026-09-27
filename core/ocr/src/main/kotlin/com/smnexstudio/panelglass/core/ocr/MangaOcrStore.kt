package com.smnexstudio.panelglass.core.ocr

import android.content.Context
import com.smnexstudio.panelglass.core.data.download.SystemDownloads
import com.smnexstudio.panelglass.core.model.ModelState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the optional manga-ocr files (`l0wgear/manga-ocr-2025-onnx`: ViT-Small encoder, decoder, WordPiece
 * vocab — ~140 MB, upstream kha-white/manga-ocr is Apache-2.0). Downloaded like the on-device translation models:
 * Android's DownloadManager ([SystemDownloads]) fetches the three files in the background, so switching apps does not
 * stop it. Files from earlier installs in `filesDir/models/manga-ocr/` are still used where they are. Off until the
 * user downloads it; then [ComicTextDetector] prefers it for Japanese crops.
 */
@Singleton
class MangaOcrStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloads: SystemDownloads,
) {
    class Files(val encoder: File, val decoder: File, val vocab: File)

    private val legacyDir: File get() = File(File(context.filesDir, "models"), "manga-ocr")
    /** Where DownloadManager itself writes (shared storage on Android 8–9, see [SystemDownloads.finalFile]). */
    private val externalDir: File? get() = downloads.root()?.let { File(it, REL_DIR) }
    private val downloadDir: File get() = downloads.finalFile(REL_DIR) ?: legacyDir

    /** The older internal copy when it is complete, else the downloaded one. */
    val dir: File get() = if (isComplete(legacyDir)) legacyDir else downloadDir
    val files: Files get() = filesIn(dir)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow(onDisk())
    val state: StateFlow<ModelState> = _state
    val isReady: Boolean get() = _state.value is ModelState.Ready

    init { if (PARTS.any { downloads.isActive(key(it.name)) }) follow() else if (unchecked().isNotEmpty()) check() }

    private fun filesIn(d: File) = Files(File(d, ENCODER), File(d, DECODER), File(d, VOCAB))
    private fun isComplete(d: File): Boolean = PARTS.all { isGood(File(d, it.name), it) }
    /** Complete and checked against its SHA-256 where it will be loaded from. */
    private fun isGood(f: File, p: Part) = f.length() >= p.min && downloads.isPrivate(f) && downloads.isVerified(f, p.sha256)
    private fun isReadyOnDisk(): Boolean = isComplete(dir)
    private fun sizeOnDisk() = files.let { it.encoder.length() + it.decoder.length() + it.vocab.length() }
    private fun key(name: String) = "manga-ocr:$name"
    private fun have(p: Part) = isGood(File(legacyDir, p.name), p) || isGood(File(downloadDir, p.name), p)

    /** Parts on disk at full size but not checked yet (earlier installs, side-loads, Android 8–9 shared storage). */
    private fun unchecked(): List<Pair<Part, File>> = PARTS.filter { !have(it) }.mapNotNull { p ->
        listOfNotNull(legacyDir, externalDir).map { File(it, p.name) }.firstOrNull { it.length() >= p.min }?.let { p to it }
    }

    private fun onDisk(): ModelState = when {
        isReadyOnDisk() -> ModelState.Ready(sizeOnDisk())
        unchecked().isNotEmpty() -> ModelState.Downloading(EXPECTED_BYTES, EXPECTED_BYTES, SystemDownloads.CHECKING)
        else -> ModelState.Missing
    }

    /** Hashes the parts found on disk once ([SystemDownloads.adopt]); one that does not match is deleted. */
    private fun check() {
        val todo = unchecked()
        _state.value = ModelState.Downloading(EXPECTED_BYTES, EXPECTED_BYTES, SystemDownloads.CHECKING)
        job = scope.launch {
            val bad = todo.count { (p, f) -> downloads.adopt(f, "$REL_DIR/${p.name}", p.sha256) == null }
            _state.value = when {
                bad > 0 -> ModelState.Failed(SystemDownloads.CORRUPT)
                isReadyOnDisk() -> ModelState.Ready(sizeOnDisk())
                else -> ModelState.Missing
            }
        }
    }

    fun download() {
        if (job?.isActive == true || isReadyOnDisk()) return
        try {
            for (p in PARTS) {
                if (have(p)) continue
                downloads.start(key(p.name), "$BASE/${p.name}", "$REL_DIR/${p.name}", "Panelglass · manga-ocr (${p.name})")
            }
        } catch (e: IOException) {
            _state.value = ModelState.Failed(e.message ?: "Download failed"); return
        }
        follow()
    }

    /** Follows every queued file and sums their progress; the files download in parallel. */
    private fun follow() {
        _state.value = ModelState.Downloading(0, EXPECTED_BYTES)
        job = scope.launch {
            val done = ConcurrentHashMap<String, Long>()
            val notes = ConcurrentHashMap<String, String>()
            fun publish() = run { _state.value = ModelState.Downloading(done.values.sum(), EXPECTED_BYTES, notes.values.firstOrNull()) }
            try {
                coroutineScope {
                    PARTS.map { part ->
                        val name = part.name
                        async {
                            val have = File(downloadDir, name)
                            if (!downloads.isActive(key(name))) { done[name] = have.length(); return@async }
                            downloads.await(key(name), "$REL_DIR/$name", part.min, part.sha256) { p ->
                                done[name] = p.bytes
                                p.note?.let { notes[name] = it } ?: notes.remove(name)
                                publish()
                            }.also { done[name] = it.length() }
                        }
                    }.awaitAll()
                }
                _state.value = if (isReadyOnDisk()) ModelState.Ready(sizeOnDisk()) else ModelState.Failed("Download incomplete; tap Retry")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ModelState.Failed(e.message?.takeIf { it.length < 80 } ?: "Download failed")
            }
        }
    }

    fun cancel() {
        job?.cancel(); job = null
        PARTS.forEach { downloads.cancel(key(it.name)) }
        _state.value = if (isReadyOnDisk()) ModelState.Ready(sizeOnDisk()) else ModelState.Missing
    }

    fun delete() {
        cancel()
        legacyDir.listFiles()?.forEach { it.delete() }
        downloadDir.listFiles()?.forEach { it.delete() }
        externalDir?.listFiles()?.forEach { it.delete() }
        _state.value = ModelState.Missing
    }

    /** One file of the model; [sha256] is Hugging Face's for it at the commit pinned in [BASE]. */
    private class Part(val name: String, val min: Long, val sha256: String)

    companion object {
        /** Pinned to a commit: the SHA-256s in [PARTS] are for these exact files. */
        const val BASE = "https://huggingface.co/l0wgear/manga-ocr-2025-onnx/resolve/e8b27bbd3f424fe3877e0bda704d6a920e4f0a33"
        const val ENCODER = "encoder_model.onnx"
        const val DECODER = "decoder_model.onnx"
        const val VOCAB = "vocab.txt"
        private const val REL_DIR = "models/manga-ocr"
        /** Published sizes: 22.4 MB + 118.1 MB + 24 KB. */
        const val EXPECTED_BYTES = 22_356_885L + 118_053_454L + 24_072L
        const val MIN_ENCODER = 10L * 1024 * 1024
        const val MIN_DECODER = 50L * 1024 * 1024
        const val MIN_VOCAB = 1024L
        private val PARTS = listOf(
            Part(ENCODER, MIN_ENCODER, "f87668ae0f62d6f032dac6b213e8c0fea84cd15895ac8cab624cc9a2f49d4a27"),
            Part(DECODER, MIN_DECODER, "6b1fb216d542c4b2a4fa5b9d7ae3522081eb85fb959d2cecd28055af956a8a5e"),
            Part(VOCAB, MIN_VOCAB, "344fbb6b8bf18c57839e924e2c9365434697e0227fac00b88bb4899b78aa594d"),
        )
    }
}
