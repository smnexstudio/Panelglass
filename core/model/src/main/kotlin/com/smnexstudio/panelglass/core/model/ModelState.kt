package com.smnexstudio.panelglass.core.model

/** Lifecycle of a downloadable on-device model (the Qwen translator, the manga-ocr recognizer). */
sealed class ModelState {
    data object Missing : ModelState()
    /** [note]: why a download is not moving right now ("Waiting for network"), null while it runs. */
    data class Downloading(val bytes: Long, val total: Long, val note: String? = null) : ModelState()
    data class Ready(val sizeBytes: Long) : ModelState()
    data class Failed(val reason: String) : ModelState()
}
