package com.smnexstudio.panelglass.core.data.db

import androidx.room.TypeConverter
import com.smnexstudio.panelglass.core.model.BubbleStyle
import com.smnexstudio.panelglass.core.model.Pt
import com.smnexstudio.panelglass.core.model.SfxEdit
import com.smnexstudio.panelglass.core.model.StudioStage
import com.smnexstudio.panelglass.core.model.TextRegion
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** JSON columns of the Studio tables. Styles and regions are JSON so fields can be added without a schema change. */
class StudioConverters {
    @TypeConverter fun stagesToText(v: Set<StudioStage>): String = v.sortedBy { it.ordinal }.joinToString(",") { it.name }
    @TypeConverter fun textToStages(v: String): Set<StudioStage> =
        v.split(',').mapNotNull { n -> StudioStage.entries.firstOrNull { it.name == n } }.toSet()

    @TypeConverter fun polygonToText(v: List<Pt>): String = json.encodeToString(v)
    @TypeConverter fun textToPolygon(v: String): List<Pt> = json.decodeFromString(v)

    @TypeConverter fun styleToText(v: BubbleStyle): String = json.encodeToString(v)
    @TypeConverter fun textToStyle(v: String): BubbleStyle = runCatching { json.decodeFromString<BubbleStyle>(v) }.getOrDefault(BubbleStyle())

    @TypeConverter fun regionToText(v: TextRegion?): String? = v?.let { json.encodeToString(it) }
    @TypeConverter fun textToRegion(v: String?): TextRegion? = v?.let { runCatching { json.decodeFromString<TextRegion>(it) }.getOrNull() }

    @TypeConverter fun sfxToText(v: SfxEdit?): String? = v?.let { json.encodeToString(it) }
    @TypeConverter fun textToSfx(v: String?): SfxEdit? = v?.let { runCatching { json.decodeFromString<SfxEdit>(it) }.getOrNull() }

    @TypeConverter fun stringMapToText(v: Map<String, String>): String = json.encodeToString(v)
    @TypeConverter fun textToStringMap(v: String): Map<String, String> = json.decodeFromString(v)

    @TypeConverter fun stringSetToText(v: Set<String>): String = json.encodeToString(v)
    @TypeConverter fun textToStringSet(v: String): Set<String> = json.decodeFromString(v)

    @TypeConverter fun stringListToText(v: List<String>): String = json.encodeToString(v)
    @TypeConverter fun textToStringList(v: String): List<String> = json.decodeFromString(v)

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    }
}
