package com.smnexstudio.panelglass.core.model

/**
 * The LiteRT-LM backend for Qwen. The GPU is ~3× faster but locks ~2.8 GB; on the CPU the weights stay a
 * memory-mapped file the system can reclaim (~0.6 GB pinned). AUTO takes the CPU on phones under 6 GB of RAM
 * (docs/MEMORY_USAGE.md). A GPU the runtime cannot use falls back to the CPU whatever the choice.
 */
enum class QwenBackend { AUTO, GPU, CPU }

data class Settings(
    val defaultSourceLang: Lang = Lang.JA,
    val defaultTargetLang: Lang = Lang.EN,
    /** Explicit provider choice; the on-device model unless the user picks something else. */
    val engineId: EngineId = EngineId.QWEN15_LOCAL,
    /** User-chosen model ids per provider (e.g. `claude-sonnet-4-5`, `openai/gpt-4o-mini`). Not secrets. */
    val models: Map<EngineId, String> = emptyMap(),
    /** BATCHED (default) sends one call per image; SEQUENTIAL does strict 2-back per region. */
    val contextMode: ContextMode = ContextMode.BATCHED,
    val adBlockDefault: Boolean = true,
    val bubbleFont: BubbleFont = BubbleFont.PLUS_JAKARTA_SANS,
    val sfxMode: SfxMode = SfxMode.OVERLAY,
    val freeTextMode: FreeTextMode = FreeTextMode.ERASE,
    val patchQuality: Int = 100,
    val translateOnOpen: Boolean = false,
    val blockListUpdatedAt: Long = 0L,
    val appTheme: AppTheme = AppTheme.DEFAULT,
    /** Where Qwen runs; [QwenBackend.AUTO] decides by the phone's RAM. */
    val qwenBackend: QwenBackend = QwenBackend.AUTO,
) {
    /**
     * The model to call for [id]: the user's choice, else the provider's default; blank when neither exists. A
     * stored id the provider has since retired (an old default a user saved as-is) resolves to the current default
     * rather than to a 404.
     */
    fun modelFor(id: EngineId): String {
        val stored = models[id]?.trim()?.takeIf { it.isNotEmpty() }
        return if (stored == null || stored in RETIRED_MODELS) id.defaultModel.orEmpty() else stored
    }

    companion object {
        /** Former defaults the providers now refuse ("no longer available to new users"). */
        val RETIRED_MODELS = setOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-2.0-flash-lite")
    }

    /** Resolves per-site overrides onto the global defaults. */
    fun resolve(site: Site?): TranslateConfig = TranslateConfig(
        src = site?.sourceLang ?: defaultSourceLang,
        tgt = site?.targetLang ?: defaultTargetLang,
        engineId = site?.engineId ?: engineId,
        sfxMode = sfxMode,
        freeTextMode = freeTextMode,
        bubbleFont = bubbleFont,
        patchQuality = patchQuality,
        contextMode = contextMode,
    )
}
