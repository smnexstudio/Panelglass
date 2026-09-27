package com.smnexstudio.panelglass.core.engine.local

import android.app.ActivityManager
import android.content.Context
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.QwenBackend

/**
 * The on-device translation models: LiteRT-LM `.litertlm` files, the format Google's AI Edge Gallery uses, downloaded
 * from Hugging Face on first use.
 */
enum class LocalModel(
    val engineId: EngineId,
    val label: String,
    val fileName: String,
    val url: String,
    val bytes: Long,
    /**
     * SHA-256 of the file at the commit pinned in [url] (Hugging Face's `X-Linked-ETag`). A download or a file on
     * disk that does not match is deleted, never loaded. Changing [url] means changing this too.
     */
    val sha256: String,
    /** Token budget shared by prompt and reply; never above the KV cache the file was converted with (`ekv…`). */
    val maxTokens: Int,
    /** Below this much RAM the model is not offered (Gallery's own minimum for the 2.6 GB Gemma 4 file). */
    val minRamGb: Int = 0,
) {
    QWEN15(
        EngineId.QWEN15_LOCAL, "Qwen 2.5 1.5B", "qwen2.5-1.5b-instruct-q8.litertlm",
        "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/19edb84c69a0212f29a6ef17ba0d6f278b6a1614/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        1_597_931_520L, "faa60663b333290c1496c499828b21d3e3254a788cacd8cce917ce0f761a2dc9", maxTokens = 4096,
    ),
    GEMMA4(
        EngineId.GEMMA4_LOCAL, "Gemma 4 E2B", "gemma-4-e2b-it.litertlm",
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it.litertlm",
        2_588_147_712L, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", maxTokens = 4096, minRamGb = 8,
    ),
    ;

    /** A partial or truncated file is refused: well over half the published size. */
    val minValidBytes: Long get() = bytes * 7 / 10

    companion object {
        fun of(id: EngineId): LocalModel? = entries.firstOrNull { it.engineId == id }

        /** Files of models no longer offered (Qwen 2.5 0.5B, Gemma 3 1B): deleted on start to give the space back. */
        val RETIRED_FILES = listOf("qwen2.5-0.5b-instruct-q8.task", "gemma3-1b-it-int4.litertlm")
    }
}

/** Physical RAM, for hiding models this device cannot hold. */
object DeviceMemory {
    /**
     * `totalMem` is what the kernel manages, not the marketed size: a phone sold as 8 GB reports about 7.2–7.6 GiB
     * (firmware and carve-outs take the rest). So "8 GB" is tested as ≥ 7 GiB, "6 GB" as ≥ 5 GiB, and so on.
     */
    fun meets(context: Context, marketedGb: Int): Boolean {
        if (marketedGb <= 0) return true
        return totalBytes(context) >= (marketedGb - 1).toLong() * GIB
    }

    fun totalBytes(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    }

    /**
     * Whether Qwen skips the GPU. [QwenBackend.AUTO] takes the CPU below [AUTO_GPU_MIN_GB]: on the GPU Qwen locks
     * ~2.8 GB, which left an 8 GB phone 0.7 GB free while translating; on the CPU it pins ~0.6 GB (docs/MEMORY_USAGE.md).
     */
    fun qwenOnCpu(context: Context, choice: QwenBackend): Boolean = when (choice) {
        QwenBackend.CPU -> true
        QwenBackend.GPU -> false
        QwenBackend.AUTO -> !meets(context, AUTO_GPU_MIN_GB)
    }

    /** Marketed RAM from which [QwenBackend.AUTO] uses the GPU. */
    const val AUTO_GPU_MIN_GB = 6

    private const val GIB = 1024L * 1024 * 1024
}
