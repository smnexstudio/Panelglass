package com.smnexstudio.panelglass.core.model

/**
 * The servers the "Custom AI" key sheet offers by name. Picking one fills in its OpenAI-compatible base address and
 * locks it; [CUSTOM] lets the user type any address. Nothing extra is stored: the provider is read back from the saved
 * address ([of]), so an address saved before the picker existed shows as the provider it belongs to.
 *
 * [displayName] is a brand name, never translated (CUSTOM's label is a string resource). [modelHint] is only the model
 * field's placeholder: the user still types the model id.
 */
enum class CustomProvider(val displayName: String, val baseUrl: String, val modelHint: String) {
    DEEPSEEK("DeepSeek", "https://api.deepseek.com", "deepseek-chat"),
    GROK("Grok", "https://api.x.ai/v1", "grok-4"),
    GPT("GPT", "https://api.openai.com/v1", "gpt-4o-mini"),
    CLAUDE("Claude", "https://api.anthropic.com/v1", "claude-sonnet-5-5"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "provider/model-name"),
    CUSTOM("", "", "provider/model-name");

    /** False for [CUSTOM], whose address the user types. */
    val isPreset: Boolean get() = this != CUSTOM

    companion object {
        /** The providers with a fixed address, in the order the sheet shows them; [CUSTOM] comes after them. */
        val presets: List<CustomProvider> = entries.filter { it.isPreset }

        /** What a sheet with no address saved yet starts on. */
        val DEFAULT = DEEPSEEK

        /**
         * The preset whose address [baseUrl] is, else [CUSTOM]. Compared after [CustomEndpoint.normalize], so case in
         * the host, a trailing slash or a pasted `…/chat/completions` still match; another path on the same host (a
         * user's own `/v1` on DeepSeek) is that user's own address and stays [CUSTOM].
         */
        fun of(baseUrl: String): CustomProvider {
            val base = CustomEndpoint.normalize(baseUrl)?.let(::canonical) ?: return CUSTOM
            return presets.firstOrNull { canonical(it.baseUrl) == base } ?: CUSTOM
        }

        /** The address with its scheme and host in lower case (the path keeps its case). */
        private fun canonical(base: String): String {
            val schemeEnd = base.indexOf("://")
            val hostEnd = base.indexOf('/', schemeEnd + 3).let { if (it < 0) base.length else it }
            return base.substring(0, hostEnd).lowercase() + base.substring(hostEnd)
        }
    }
}
