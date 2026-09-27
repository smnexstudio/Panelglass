package com.smnexstudio.panelglass.core.model

import kotlinx.serialization.Serializable

/**
 * Languages the pipeline can read from or write to.
 *
 * [rtlReading] drives region ordering within an image (top-right first for Japanese),
 * [mayBeVertical] tells the clusterer to look for one-character-wide columns.
 */
@Serializable
enum class Lang(
    val code: String,
    val displayName: String,
    val rtlReading: Boolean = false,
    val mayBeVertical: Boolean = false,
) {
    JA("ja", "Japanese", rtlReading = true, mayBeVertical = true),
    KO("ko", "Korean"),
    ZH("zh", "Chinese", mayBeVertical = true),
    ZH_TW("zh-TW", "Chinese (Traditional)", rtlReading = true, mayBeVertical = true),
    EN("en", "English"),
    ES("es", "Spanish"),
    FR("fr", "French"),
    DE("de", "German"),
    IT("it", "Italian"),
    PT("pt", "Portuguese"),
    RU("ru", "Russian"),
    ID("id", "Indonesian"),
    VI("vi", "Vietnamese"),
    TH("th", "Thai"),
    AR("ar", "Arabic"),
    HI("hi", "Hindi"),
    ;

    /** Source-side languages that have a dedicated recognizer / SFX dictionary. */
    val isCjk: Boolean get() = this == JA || this == KO || this == ZH || this == ZH_TW

    companion object {
        val sourceChoices: List<Lang> = listOf(JA, KO, ZH, ZH_TW, EN, ES, FR, DE, IT, PT, RU, ID, VI)
        val targetChoices: List<Lang> = entries
    }
}
