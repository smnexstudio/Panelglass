package com.smnexstudio.panelglass.core.ocr

import android.content.Context
import com.smnexstudio.panelglass.core.data.download.SystemDownloads
import com.smnexstudio.panelglass.core.model.ModelState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The optional LaMa inpainting model for the Studio's cleanup (docs/STUDIO_PLAN.md › Cleanup): `Carve/LaMa-ONNX`
 * `lama_fp32.onnx` (Apache-2.0, 208 MB, fixed 512×512 input), downloaded on request through Android's DownloadManager
 * ([SystemDownloads]), checked against its SHA-256 at the pinned commit before it is used, as manga-ocr is.
 */
@Singleton
class LamaStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloads: SystemDownloads,
) {
    private val dir: File get() = downloads.finalFile(REL_DIR) ?: File(File(context.filesDir, "models"), "lama")
    val file: File get() = File(dir, FILE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow(onDisk())
    val state: StateFlow<ModelState> = _state
    val isReady: Boolean get() = _state.value is ModelState.Ready

    /** Whether this phone has the memory to run it ([MIN_RAM_GB]); the row and the model are hidden otherwise. */
    val fitsThisPhone: Boolean by lazy {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        // totalMem is a little under the marketed size: a "4 GB" phone reports about 3.6 GiB.
        info.totalMem >= (MIN_RAM_GB * 0.85 * 1024 * 1024 * 1024).toLong()
    }

    init {
        if (downloads.isActive(KEY)) follow() else if (file.length() >= MIN_BYTES && !isGood()) check()
    }

    private fun isGood() = file.length() >= MIN_BYTES && downloads.isVerified(file, SHA256)
    private fun onDisk(): ModelState = when {
        isGood() -> ModelState.Ready(file.length())
        file.length() >= MIN_BYTES -> ModelState.Downloading(EXPECTED_BYTES, EXPECTED_BYTES, SystemDownloads.CHECKING)
        else -> ModelState.Missing
    }

    /** Hashes a file found on disk once (a side-load, an earlier install); one that does not match is deleted. */
    private fun check() {
        _state.value = ModelState.Downloading(EXPECTED_BYTES, EXPECTED_BYTES, SystemDownloads.CHECKING)
        job = scope.launch {
            _state.value = if (downloads.adopt(file, SHA256) != null && isGood()) ModelState.Ready(file.length()) else ModelState.Failed(SystemDownloads.CORRUPT)
        }
    }

    fun download() {
        if (job?.isActive == true || isGood()) return
        try {
            downloads.start(KEY, "$BASE/$FILE", "$REL_DIR/$FILE", "Panelglass · LaMa")
        } catch (e: IOException) {
            _state.value = ModelState.Failed(e.message ?: "Download failed"); return
        }
        follow()
    }

    private fun follow() {
        _state.value = ModelState.Downloading(0, EXPECTED_BYTES)
        job = scope.launch {
            try {
                downloads.await(KEY, "$REL_DIR/$FILE", MIN_BYTES, SHA256) { p -> _state.value = ModelState.Downloading(p.bytes, EXPECTED_BYTES, p.note) }
                _state.value = if (isGood()) ModelState.Ready(file.length()) else ModelState.Failed("Download incomplete; tap Retry")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ModelState.Failed(e.message?.takeIf { it.length < 80 } ?: "Download failed")
            }
        }
    }

    fun cancel() {
        job?.cancel(); job = null
        downloads.cancel(KEY)
        _state.value = if (isGood()) ModelState.Ready(file.length()) else ModelState.Missing
    }

    fun delete() {
        cancel()
        file.delete()
        _state.value = ModelState.Missing
    }

    companion object {
        /** Pinned to a commit: [SHA256] is Hugging Face's for this exact file. */
        const val BASE = "https://huggingface.co/Carve/LaMa-ONNX/resolve/c3c0c9e468934d62e79c329e35d82dd09ff8c444"
        const val FILE = "lama_fp32.onnx"
        const val SHA256 = "1faef5301d78db7dda502fe59966957ec4b79dd64e16f03ed96913c7a4eb68d6"
        const val EXPECTED_BYTES = 208_044_816L
        const val MIN_BYTES = 180L * 1024 * 1024 // the file is 198 MiB; its SHA-256 is the real check
        /** Below this, LaMa beside the app and a model risks the phone killing it (docs/STUDIO_PLAN.md › Memory). */
        const val MIN_RAM_GB = 4
        private const val REL_DIR = "models/lama"
        private const val KEY = "lama:$FILE"
    }
}
