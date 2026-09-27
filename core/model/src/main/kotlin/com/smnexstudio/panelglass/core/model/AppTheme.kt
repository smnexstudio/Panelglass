package com.smnexstudio.panelglass.core.model

enum class AppTheme(val id: String, val label: String, val subtitle: String) {
    DEFAULT("default", "Default", "Warm off-white and signature yellow"),
    PANEL_POP("panel_pop", "Panel Pop", "Neo-brutalist comic · bold outlines"),
    SOFT_BLOOM("soft_bloom", "Soft Bloom", "Pastel · rounded & diffused lavender"),
    PAPER_INK("paper_ink", "Paper & Ink", "Minimal editorial · warm paper & serif");

    companion object {
        fun fromId(id: String?): AppTheme =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) || it.name.equals(id, ignoreCase = true) } ?: DEFAULT
    }
}
