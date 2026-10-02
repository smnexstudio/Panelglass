package com.smnexstudio.panelglass.core.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.model.Chapter
import com.smnexstudio.panelglass.core.model.Manga
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An installed version-3 database (sites, history, glossary) opened by the version-4 app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration3To4Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @Before fun writeVersion3() {
        context.deleteDatabase(name)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        // Room's own v3 schema (the generated PanelglassDb_Impl before the Studio).
        V3_SCHEMA.forEach(db::execSQL)
        db.execSQL("INSERT INTO sites (name, url, pinned, sortOrder, sourceLang, adBlockEnabled, autoTranslate) VALUES ('Kuma', 'https://rawkuma.net/', 1, 0, 'JA', 1, 0)")
        db.execSQL("INSERT INTO history (url, title, host, visitedAt) VALUES ('https://rawkuma.net/a/', 'A', 'rawkuma.net', 42)")
        db.execSQL("INSERT INTO glossary (seriesKey, source, target) VALUES ('rawkuma.net/manga', '先輩', 'senpai')")
        db.execSQL("INSERT INTO sfx_cache (`key`, translated) VALUES ('どん|ja|en', 'BOOM')")
        db.execSQL("INSERT INTO url_etag (url, etag, imageHash, seenAt) VALUES ('https://x/1.jpg', NULL, 'h', 1)")
        db.version = 3
        db.close()
    }

    @After fun cleanUp() {
        context.deleteDatabase(name)
    }

    private fun open() = Room.databaseBuilder(context, PanelglassDb::class.java, name)
        .addMigrations(*PanelglassDb.MIGRATIONS)
        .allowMainThreadQueries()
        .build()

    @Test fun sitesAndHistorySurvive() = runBlocking {
        val db = open()
        try {
            val sites = db.sites().all()
            assertEquals(1, sites.size)
            assertEquals("Kuma", sites[0].name)
            assertTrue(sites[0].pinned)
            assertEquals(listOf("https://rawkuma.net/a/"), db.history().observeRecent().first().map { it.url })
            assertEquals(mapOf("先輩" to "senpai"), db.glossary().forSeries("rawkuma.net/manga").associate { it.source to it.target })
            assertEquals("BOOM", db.sfxCache().get("どん|ja|en"))
        } finally {
            db.close()
        }
    }

    @Test fun studioTablesExistAndOldOnesAreDropped() = runBlocking {
        val db = open()
        try {
            val mangaId = db.studio().insertManga(Manga(title = "Blue Box"))
            db.studio().insertChapter(Chapter(mangaId = mangaId, number = 1f))
            assertEquals(1, db.studio().chapters(mangaId).size)
            val tables = db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            assertTrue(tables.containsAll(listOf("studio_manga", "studio_chapter", "studio_page", "studio_bubble", "user_font")))
            assertFalse("url_etag" in tables)
            assertFalse("ocr_cache" in tables)
        } finally {
            db.close()
        }
    }

    private companion object {
        val V3_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `sites` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `iconUrl` TEXT, `pinned` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `sourceLang` TEXT, `targetLang` TEXT, `engineId` TEXT, `adBlockEnabled` INTEGER NOT NULL, `autoTranslate` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `ocr_cache` (`imageHash` TEXT NOT NULL, `srcLang` TEXT NOT NULL, `regionsJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`imageHash`))",
            "CREATE TABLE IF NOT EXISTS `sfx_cache` (`key` TEXT NOT NULL, `translated` TEXT NOT NULL, PRIMARY KEY(`key`))",
            "CREATE TABLE IF NOT EXISTS `glossary` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `seriesKey` TEXT NOT NULL, `source` TEXT NOT NULL, `target` TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `url_etag` (`url` TEXT NOT NULL, `etag` TEXT, `imageHash` TEXT NOT NULL, `seenAt` INTEGER NOT NULL, PRIMARY KEY(`url`))",
            "CREATE TABLE IF NOT EXISTS `history` (`url` TEXT NOT NULL, `title` TEXT NOT NULL, `host` TEXT NOT NULL, `visitedAt` INTEGER NOT NULL, PRIMARY KEY(`url`))",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        )
    }
}
