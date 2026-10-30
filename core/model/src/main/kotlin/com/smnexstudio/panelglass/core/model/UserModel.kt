package com.smnexstudio.panelglass.core.model

import kotlinx.serialization.Serializable

/**
 * A model in the user's own library: downloaded from Hugging Face or imported from a file. It is a LiteRT-LM
 * `.litertlm` file, the format the bundled runtime loads.
 *
 * The file on disk is always named `user-<id>.litertlm`: the name never comes from the remote repository or from the
 * imported file's own name, so a path can never escape the models directory.
 */
@Serializable
data class UserModel(
    val id: String,
    override val label: String,
    override val fileName: String,
    /** The download address; empty for a model imported from a file. */
    override val url: String,
    override val bytes: Long,
    override val sha256: String,
    override val maxTokens: Int,
    /** Where the model came from: a Hugging Face repository id, or "file" for an import. */
    val source: String = "",
) : ModelSpec {
    override val engineId: EngineId get() = EngineId.LOCAL_USER
    override val key: String get() = "usermodel:" + id

    companion object {
        /** The smallest KV cache a converted model can have; below this the runtime cannot run it. */
        const val MIN_TOKENS = 512
        /** The largest: a bigger budget than the file's own cache is never useful and would overflow it. */
        const val MAX_TOKENS = 32768
        /** Used when the file name does not say how big the KV cache is. */
        const val DEFAULT_TOKENS = 2048

        /**
         * The KV cache the file was converted with, read from its name (`…_ekv4096.litertlm` → 4096). The cache is
         * baked into the file: asking for more than it holds fails at load time, so this is a ceiling, not a wish.
         */
        fun maxTokensFromFileName(fileName: String): Int {
            val m = Regex("ekv(\\d+)", RegexOption.IGNORE_CASE).find(fileName) ?: return DEFAULT_TOKENS
            return clampTokens(m.groupValues[1].toIntOrNull() ?: DEFAULT_TOKENS)
        }

        /** Keeps a token budget inside the range the runtime can work with. */
        fun clampTokens(v: Int): Int = v.coerceIn(MIN_TOKENS, MAX_TOKENS)

        /** The id of a model downloaded from the hub. */
        fun hubId(sha256: String): String = "h" + sha256.take(12)

        /** The id of a model imported from a file. */
        fun fileId(sha256: String): String = "f" + sha256.take(12)

        /** The file name a model is stored under; never derived from a remote path. */
        fun fileNameFor(id: String): String = "user-" + id + ".litertlm"
    }
}
