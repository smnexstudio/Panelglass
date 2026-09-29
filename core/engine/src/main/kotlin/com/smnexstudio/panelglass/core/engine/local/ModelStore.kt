package com.smnexstudio.panelglass.core.engine.local

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

/** One [ModelStore] per on-device model. */
@Singleton
class ModelStores @Inject constructor(
    @ApplicationContext context: Context,
    downloads: SystemDownloads,
) {
    private val stores = LocalModel.entries.associateWith { ModelStore(context, downloads, it) }
    operator fun get(model: LocalModel): ModelStore = stores.getValue(model)
    val all: Collection<ModelStore> get() = stores.values

    init {
        // Models that are no longer offered: give their space back (up to ~1.1 GB).
        CoroutineScope(Dispatchers.IO).launch {
            val dirs = listOfNotNull(File(context.filesDir, "models"), downloads.root()?.let { File(it, "models") })
            for (dir in dirs) for (name in LocalModel.RETIRED_FILES) {
                File(dir, name).delete(); File(dir, "$name.part").delete()
            }
        }
    }
}

/**
 * Owns one on-device model file: where it lives, whether it is present, and how it gets there. Android's
 * DownloadManager ([SystemDownloads]) fetches it in the background, so switching apps or closing Panelglass does not
 * stop it; the store only follows its progress, and picks it up again after a restart.
 *
 * New files live in the app-specific external directory (`models/`); a file already in the older internal
 * `filesDir/models/` (earlier installs, side-loads) is still used where it is.
 */
class ModelStore(
    private val context: Context,
    private val downloads: SystemDownloads,
    val model: LocalModel,
) {
    private val legacyFile: File get() = File(File(context.filesDir, "models"), model.fileName)
    private val relativePath: String get() = "models/" + model.fileName
    /** Where DownloadManager writes and the checked file stays ([SystemDownloads.finalFile]). */
    private val externalFile: File? get() = downloads.finalFile(relativePath)
    private val downloadedFile: File get() = externalFile ?: legacyFile

    /** The model on disk: the older internal copy when there is one, else the downloaded one. */
    val modelFile: File get() = if (legacyFile.isFile) legacyFile else downloadedFile
    private val partFile: File get() = File((externalFile ?: legacyFile).path + ".part")
    private val key: String get() = "model:" + model.name

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<ModelState>(onDisk())
    val state: StateFlow<ModelState> = _state

    val isReady: Boolean get() = _state.value is ModelState.Ready

    init { if (downloads.isActive(key)) follow() else if (unchecked() != null) check() }

    /** Ready only once the file's SHA-256 has been checked where it will be loaded from. */
    private fun isReadyOnDisk() = modelFile.let {
        it.length() >= model.minValidBytes && downloads.isVerified(it, model.sha256)
    }

    /** A complete-looking file that has not been checked yet: an earlier install's or a side-load. */
    private fun unchecked(): File? = if (isReadyOnDisk()) null
        else listOfNotNull(legacyFile, externalFile).firstOrNull { it.length() >= model.minValidBytes }

    private fun onDisk(): ModelState = when {
        isReadyOnDisk() -> ModelState.Ready(modelFile.length())
        else -> unchecked()?.let { ModelState.Downloading(it.length(), it.length(), SystemDownloads.CHECKING) } ?: ModelState.Missing
    }

    fun refresh() {
        if (job?.isActive == true) return
        if (downloads.isActive(key)) { follow(); return }
        if (unchecked() != null) { check(); return }
        _state.value = onDisk()
    }

    /** Hashes a file found on disk once ([SystemDownloads.adopt]); one that does not match is deleted. */
    private fun check() {
        val file = unchecked() ?: return
        _state.value = ModelState.Downloading(file.length(), file.length(), SystemDownloads.CHECKING)
        job = scope.launch {
            val ok = downloads.adopt(file, model.sha256)
            _state.value = if (ok != null) ModelState.Ready(ok.length()) else ModelState.Failed(SystemDownloads.CORRUPT)
        }
    }

    fun download() {
        if (job?.isActive == true || isReadyOnDisk()) return
        try {
            downloads.start(key, model.url, relativePath, "Panelglass · ${model.label}")
        } catch (e: IOException) {
            _state.value = ModelState.Failed(e.message ?: "Download failed"); return
        }
        follow()
    }

    /** Mirrors the system download into [state] until it ends. Stopping this does not stop the download. */
    private fun follow() {
        _state.value = ModelState.Downloading(0, model.bytes)
        job = scope.launch {
            try {
                val file = downloads.await(key, relativePath, model.minValidBytes, model.sha256) { p ->
                    _state.value = ModelState.Downloading(p.bytes, if (p.total > 0) p.total else model.bytes, p.note)
                }
                _state.value = ModelState.Ready(file.length())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ModelState.Failed(e.message?.takeIf { it.length < 80 } ?: "Download failed")
            }
        }
    }

    fun cancel() {
        job?.cancel(); job = null
        downloads.cancel(key)
        partFile.delete()
        _state.value = if (isReadyOnDisk()) ModelState.Ready(modelFile.length()) else ModelState.Missing
    }

    fun delete() {
        cancel()
        legacyFile.delete(); downloadedFile.delete(); externalFile?.delete(); partFile.delete()
        _state.value = ModelState.Missing
    }
}
