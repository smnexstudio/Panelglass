package com.smnexstudio.panelglass.core.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.model.GlossaryEntry
import com.smnexstudio.panelglass.core.model.Pronoun
import com.smnexstudio.panelglass.core.model.SeriesNotes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An installed version-4 database opened by the version-5 app: glossary entries gain a pronoun, series get notes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration4To5Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-4-5-test.db"

    @Before fun writeVersion4() {
        context.deleteDatabase(name)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        // Room's own v4 schema (the generated PanelglassDb_Impl before pronouns and notes).
        V4_SCHEMA.forEach(db::execSQL)
        db.execSQL("INSERT INTO glossary (seriesKey, source, target) VALUES ('example.org/manga', '先輩', 'senpai')")
        db.version = 4
        db.close()
    }

    @After fun cleanUp() {
        context.deleteDatabase(name)
    }

    private fun open() = Room.databaseBuilder(context, PanelglassDb::class.java, name)
        .addMigrations(*PanelglassDb.MIGRATIONS)
        .allowMainThreadQueries()
        .build()

    @Test fun glossarySurvivesWithoutPronounAndTakesOne() = runBlocking {
        val db = open()
        try {
            val old = db.glossary().forSeries("example.org/manga").single()
            assertEquals("senpai", old.target)
            assertNull(old.pronoun)
            db.glossary().upsert(GlossaryEntry(seriesKey = "example.org/manga", source = "ユキ", target = "Yuki", pronoun = Pronoun.SHE))
            assertEquals(Pronoun.SHE, db.glossary().forSeries("example.org/manga").single { it.source == "ユキ" }.pronoun)
        } finally {
            db.close()
        }
    }

    @Test fun seriesNotesAreStoredPerSeries() = runBlocking {
        val db = open()
        try {
            db.seriesNotes().put(SeriesNotes("example.org/manga/a", "Ren is a woman."))
            assertEquals("Ren is a woman.", db.seriesNotes().get("example.org/manga/a"))
            assertNull(db.seriesNotes().get("example.org/manga/b"))
            db.seriesNotes().delete("example.org/manga/a")
            assertNull(db.seriesNotes().get("example.org/manga/a"))
        } finally {
            db.close()
        }
    }

    private companion object {
        val V4_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `sites` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `iconUrl` TEXT, `pinned` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `sourceLang` TEXT, `targetLang` TEXT, `engineId` TEXT, `adBlockEnabled` INTEGER NOT NULL, `autoTranslate` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `sfx_cache` (`key` TEXT NOT NULL, `translated` TEXT NOT NULL, PRIMARY KEY(`key`))",
            "CREATE TABLE IF NOT EXISTS `glossary` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `seriesKey` TEXT NOT NULL, `source` TEXT NOT NULL, `target` TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `history` (`url` TEXT NOT NULL, `title` TEXT NOT NULL, `host` TEXT NOT NULL, `visitedAt` INTEGER NOT NULL, PRIMARY KEY(`url`))",
            "CREATE TABLE IF NOT EXISTS `studio_manga` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `altTitle` TEXT NOT NULL, `author` TEXT NOT NULL, `artist` TEXT NOT NULL, `summary` TEXT NOT NULL, `genres` TEXT NOT NULL, `status` TEXT NOT NULL, `srcLang` TEXT NOT NULL, `tgtLang` TEXT NOT NULL, `exportTreeUri` TEXT, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `studio_chapter` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `mangaId` INTEGER NOT NULL, `number` REAL, `volume` INTEGER, `title` TEXT NOT NULL, `language` TEXT, `releaseDate` TEXT NOT NULL, `notes` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`mangaId`) REFERENCES `studio_manga`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_studio_chapter_mangaId` ON `studio_chapter` (`mangaId`)",
            "CREATE TABLE IF NOT EXISTS `studio_page` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `chapterId` INTEGER NOT NULL, `index` INTEGER NOT NULL, `file` TEXT NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `stages` TEXT NOT NULL, `failed` TEXT, FOREIGN KEY(`chapterId`) REFERENCES `studio_chapter`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_studio_page_chapterId` ON `studio_page` (`chapterId`)",
            "CREATE TABLE IF NOT EXISTS `studio_bubble` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `pageId` INTEGER NOT NULL, `order` INTEGER NOT NULL, `kind` TEXT NOT NULL, `polygon` TEXT NOT NULL, `sourceText` TEXT NOT NULL, `translatedText` TEXT NOT NULL, `style` TEXT NOT NULL, `region` TEXT, `sfx` TEXT, `ignored` INTEGER NOT NULL, FOREIGN KEY(`pageId`) REFERENCES `studio_page`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_studio_bubble_pageId` ON `studio_bubble` (`pageId`)",
            "CREATE TABLE IF NOT EXISTS `user_font` (`id` TEXT NOT NULL, `displayName` TEXT NOT NULL, `family` TEXT NOT NULL, `files` TEXT NOT NULL, `scripts` TEXT NOT NULL, `role` TEXT NOT NULL, `tags` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        )
    }
}
