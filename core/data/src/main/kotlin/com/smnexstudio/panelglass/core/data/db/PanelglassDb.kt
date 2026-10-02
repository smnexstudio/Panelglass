package com.smnexstudio.panelglass.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.smnexstudio.panelglass.core.model.Bubble
import com.smnexstudio.panelglass.core.model.Chapter
import com.smnexstudio.panelglass.core.model.GlossaryEntry
import com.smnexstudio.panelglass.core.model.HistoryEntry
import com.smnexstudio.panelglass.core.model.Manga
import com.smnexstudio.panelglass.core.model.SfxCacheEntry
import com.smnexstudio.panelglass.core.model.Site
import com.smnexstudio.panelglass.core.model.StudioPage
import com.smnexstudio.panelglass.core.model.UserFont

@Database(
    entities = [
        Site::class, SfxCacheEntry::class, GlossaryEntry::class, HistoryEntry::class,
        Manga::class, Chapter::class, StudioPage::class, Bubble::class, UserFont::class,
    ],
    version = 4,
    exportSchema = false,
)
@TypeConverters(StudioConverters::class)
abstract class PanelglassDb : RoomDatabase() {
    abstract fun sites(): SiteDao
    abstract fun sfxCache(): SfxCacheDao
    abstract fun glossary(): GlossaryDao
    abstract fun history(): HistoryDao
    abstract fun studio(): StudioDao
    abstract fun userFonts(): UserFontDao

    companion object {
        /** Every migration, in order. A version without a path here is rebuilt empty (`fallbackToDestructiveMigration`). */
        val MIGRATIONS: Array<Migration> get() = arrayOf(MIGRATION_3_4)
    }
}

/**
 * 3 → 4: the Studio tables and `user_font`; the unused `url_etag` and `ocr_cache` go. Sites, history, glossary and
 * the SFX cache are kept. The SQL is Room's own for these entities (from the generated `PanelglassDb_Impl`): a
 * difference fails Room's schema check on open.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `url_etag`")
        db.execSQL("DROP TABLE IF EXISTS `ocr_cache`")
        db.execSQL("CREATE TABLE IF NOT EXISTS `studio_manga` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `altTitle` TEXT NOT NULL, `author` TEXT NOT NULL, `artist` TEXT NOT NULL, `summary` TEXT NOT NULL, `genres` TEXT NOT NULL, `status` TEXT NOT NULL, `srcLang` TEXT NOT NULL, `tgtLang` TEXT NOT NULL, `exportTreeUri` TEXT, `createdAt` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `studio_chapter` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `mangaId` INTEGER NOT NULL, `number` REAL, `volume` INTEGER, `title` TEXT NOT NULL, `language` TEXT, `releaseDate` TEXT NOT NULL, `notes` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`mangaId`) REFERENCES `studio_manga`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_studio_chapter_mangaId` ON `studio_chapter` (`mangaId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `studio_page` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `chapterId` INTEGER NOT NULL, `index` INTEGER NOT NULL, `file` TEXT NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `stages` TEXT NOT NULL, `failed` TEXT, FOREIGN KEY(`chapterId`) REFERENCES `studio_chapter`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_studio_page_chapterId` ON `studio_page` (`chapterId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `studio_bubble` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `pageId` INTEGER NOT NULL, `order` INTEGER NOT NULL, `kind` TEXT NOT NULL, `polygon` TEXT NOT NULL, `sourceText` TEXT NOT NULL, `translatedText` TEXT NOT NULL, `style` TEXT NOT NULL, `region` TEXT, `sfx` TEXT, `ignored` INTEGER NOT NULL, FOREIGN KEY(`pageId`) REFERENCES `studio_page`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_studio_bubble_pageId` ON `studio_bubble` (`pageId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `user_font` (`id` TEXT NOT NULL, `displayName` TEXT NOT NULL, `family` TEXT NOT NULL, `files` TEXT NOT NULL, `scripts` TEXT NOT NULL, `role` TEXT NOT NULL, `tags` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
    }
}
