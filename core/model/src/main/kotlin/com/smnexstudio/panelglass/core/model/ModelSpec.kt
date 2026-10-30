package com.smnexstudio.panelglass.core.model

/**
 * What an on-device LiteRT-LM model needs to be downloaded and loaded: the built-in ones ([com.smnexstudio.panelglass.core.engine.local.LocalModel])
 * and the user's own library share the same store and the same engine.
 */
interface ModelSpec {
    val engineId: EngineId
    val label: String
    val fileName: String
    val url: String
    val bytes: Long
    val sha256: String
    /** Token budget shared by prompt and reply; never above the KV cache the file was converted with (`ekv…`). */
    val maxTokens: Int
    /** Stable per-model key, used for download bookkeeping and logs. */
    val key: String

    /** A partial or truncated file is refused: well over half the published size. */
    val minValidBytes: Long get() = bytes * 7 / 10
}
