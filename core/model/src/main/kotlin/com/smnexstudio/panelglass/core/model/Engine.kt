package com.smnexstudio.panelglass.core.model

import kotlinx.serialization.Serializable

/**
 * Translation providers. The on-device model is the default and needs nothing; every other engine is
 * bring-your-own-key and is only used when the user selects it explicitly.
 *
 * [defaultModel] is non-null for providers that host several models: the user names one in Settings
 * (prefilled with this default; `""` means there is no sensible default and the user must type one).
 */
@Serializable
enum class EngineId(
    val displayName: String,
    val keyRequirement: KeyRequirement,
    val defaultModel: String? = null,
    /**
     * Shown in the engine picker and selectable. `false` = a planned engine: its implementation is kept (and unit
     * tested) as a temporary placeholder, marked `@PlannedEngine`, but it is not offered — see README "Planned engines".
     */
    val offered: Boolean = true,
) {
    GEMINI("Gemini", KeyRequirement.USER_KEY, "gemini-flash-latest"),
    CLAUDE("Claude", KeyRequirement.USER_KEY, "claude-haiku-4-5", offered = false),
    OPENAI("OpenAI", KeyRequirement.USER_KEY, "gpt-4.1-mini", offered = false),
    DEEPL("DeepL", KeyRequirement.USER_KEY, offered = false),
    PAPAGO("Papago", KeyRequirement.USER_KEY, offered = false),
    DEEPSEEK("DeepSeek", KeyRequirement.USER_KEY, offered = false),
    /** Google's ML Kit on-device translation: free, no key, language packs downloaded on first use. */
    GOOGLE("Google Translate", KeyRequirement.NONE),
    OPENROUTER("OpenRouter", KeyRequirement.USER_KEY, "", offered = false),
    /** On-device models on LiteRT-LM, translating the on-device OCR text (see `LocalModel` for the files). */
    QWEN15_LOCAL("Qwen 2.5 1.5B (on-device)", KeyRequirement.NONE),
    GEMMA4_LOCAL("Gemma 4 E2B (on-device)", KeyRequirement.NONE),
    ;

    /** An on-device LLM: one generation at a time on this device, fed in small groups of bubbles. */
    val isLocalLlm: Boolean get() = this == QWEN15_LOCAL || this == GEMMA4_LOCAL

    val needsKey: Boolean get() = keyRequirement == KeyRequirement.USER_KEY

    /** Whether the user picks a model name for this provider. */
    val hasModel: Boolean get() = defaultModel != null

    /**
     * Reads the lettering from each bubble's crop itself, in the same call that translates it: the pipeline skips
     * on-device OCR (manga-ocr, ML Kit) for this engine and sends the crops instead. Only a vision model qualifies.
     */
    val readsCrops: Boolean get() = this == GEMINI
}

@Serializable
enum class KeyRequirement { NONE, USER_KEY }

/**
 * Marks an engine implementation that is a **temporary placeholder** for a planned engine: the code is kept and its
 * wire format is unit tested, but the engine is hidden from the UI ([EngineId.offered] = false) and refuses to
 * resolve until it is finished and verified on a device. See README "Planned engines".
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class PlannedEngine(val todo: String)

/** Typed engine failures the UI can act on. Never surface a bare exception string. */
@Serializable
sealed class EngineFailure {
    abstract val engine: EngineId
    @Serializable data class MissingKey(override val engine: EngineId) : EngineFailure()
    @Serializable data class QuotaExceeded(override val engine: EngineId) : EngineFailure()
    @Serializable data class RateLimited(override val engine: EngineId, val retryAfterMs: Long = 2_000) : EngineFailure()
    /**
     * The provider is up but shedding load (HTTP 500/502/503/504/529 — Gemini's "model is currently experiencing high
     * demand"). Transient: retried on the same engine and model, never swapped for another. [reason] is the provider's
     * own message.
     */
    @Serializable data class Overloaded(override val engine: EngineId, val retryAfterMs: Long = 1_000, val reason: String = "") : EngineFailure()
    @Serializable data class Malformed(override val engine: EngineId) : EngineFailure()
    @Serializable data class Network(override val engine: EngineId) : EngineFailure()
    /** The on-device model file is not present yet. */
    @Serializable data class ModelMissing(override val engine: EngineId) : EngineFailure()
    /**
     * A Google Translate (ML Kit) language pack the pair needs is not on the phone, and it is not one fetched
     * automatically: the user downloads it (one tap) before it is used. [tags] are ML Kit language tags.
     */
    @Serializable data class PackMissing(override val engine: EngineId, val tags: List<String>) : EngineFailure()
    @Serializable data class Unavailable(override val engine: EngineId, val reason: String = "") : EngineFailure()
}

class EngineException(val failure: EngineFailure, cause: Throwable? = null) :
    Exception(failure::class.simpleName, cause)

@Serializable
enum class SfxMode(val label: String) {
    SKIP("Skip"),
    GLOSS("Gloss"),
    OVERLAY("Overlay"),
    REPLACE("Replace"),
}

@Serializable
enum class FreeTextMode(val label: String) {
    OFF("Off"),
    OVERLAY("Cover with a panel"),
    ERASE("Erase and replace"),
}

@Serializable
enum class BubbleFont(val label: String) {
    PLUS_JAKARTA_SANS("Plus Jakarta Sans"),
    /** Comic dialogue: friendly, legible hand lettering (Apache-2.0). */
    COMING_SOON("Coming Soon"),
    /** Bold comic capitals for loud text (Apache-2.0). */
    LUCKIEST_GUY("Luckiest Guy"),
    ;

    /**
     * The same font in the Studio's catalogue (`cat:coming_soon`, …), where the reader now takes its fonts from: a
     * choice saved before the catalogue reads back as this id.
     */
    val fontId: String get() = "cat:" + name.lowercase()

    companion object {
        /**
         * The saved name read back: fonts that were replaced map to their successor so the user keeps the same style
         * (Komika Hand and Copilme had no redistribution licence). Null for anything unknown.
         */
        fun fromName(name: String): BubbleFont? = when (name) {
            "KOMIKA" -> COMING_SOON
            "COPILME" -> LUCKIEST_GUY
            else -> entries.firstOrNull { it.name == name }
        }
    }
}

@Serializable
enum class ContextMode(val label: String) {
    BATCHED("Batched (default)"),
    SEQUENTIAL("Sequential (strict 2-back)"),
}
