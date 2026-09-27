package com.smnexstudio.panelglass.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.smnexstudio.panelglass.core.model.GlossaryEntry
import com.smnexstudio.panelglass.core.model.HistoryEntry
import com.smnexstudio.panelglass.core.model.OcrCacheEntry
import com.smnexstudio.panelglass.core.model.SfxCacheEntry
import com.smnexstudio.panelglass.core.model.Site
import com.smnexstudio.panelglass.core.model.UrlEtagEntry

@Database(
    entities = [
        Site::class, OcrCacheEntry::class, SfxCacheEntry::class,
        GlossaryEntry::class, UrlEtagEntry::class, HistoryEntry::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class PanelglassDb : RoomDatabase() {
    abstract fun sites(): SiteDao
    abstract fun sfxCache(): SfxCacheDao
    abstract fun glossary(): GlossaryDao
    abstract fun history(): HistoryDao
}
