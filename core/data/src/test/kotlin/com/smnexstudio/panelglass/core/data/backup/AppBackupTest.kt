package com.smnexstudio.panelglass.core.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.data.MemoryDataStore
import com.smnexstudio.panelglass.core.data.db.PanelglassDb
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.GlossaryEntry
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Pronoun
import com.smnexstudio.panelglass.core.model.SeriesNotes
import com.smnexstudio.panelglass.core.model.Site
import com.smnexstudio.panelglass.core.model.TranslationNotes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Settings › Backup & restore: what a backup holds, how it is checked, and how a restore merges. Invented data only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppBackupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<PanelglassDb>()

    private class Phone(val db: PanelglassDb, val settings: SettingsRepository) {
        val backup = AppBackup(db, settings).apply { now = { 1_700_000_000_000 } }
    }

    private fun phone(): Phone {
        val db = Room.inMemoryDatabaseBuilder(context, PanelglassDb::class.java).allowMainThreadQueries().build()
        opened += db
        return Phone(db, SettingsRepository(MemoryDataStore()))
    }

    @After fun tearDown() = opened.forEach { it.close() }

    private fun bytes(phone: Phone, file: BackupFile) = ByteArrayOutputStream().also { phone.backup.write(file, it) }.toByteArray()

    private fun read(phone: Phone, text: String) = phone.backup.read(ByteArrayInputStream(text.toByteArray()))

    private fun problem(block: () -> Unit): BackupProblem? = try { block(); null } catch (e: BackupException) { e.problem }

    @Test fun aBackupRoundTripsSitesGlossariesAndNotes() = runBlocking {
        val old = phone()
        old.db.sites().upsert(Site(name = "Reader One", url = "https://one.example/series/", pinned = true, sortOrder = 1, sourceLang = Lang.KO, engineId = EngineId.GEMINI, autoTranslate = true))
        old.db.sites().upsert(Site(name = "Reader Two", url = "https://two.example/", sortOrder = 2, adBlockEnabled = false))
        old.db.glossary().upsert(GlossaryEntry(seriesKey = "one.example/series", source = "ミナ", target = "Mina", pronoun = Pronoun.SHE))
        old.db.seriesNotes().put(SeriesNotes("one.example/series", "Mina is the narrator."))
        old.settings.setTranslationNotes("Keep it short.")

        val file = old.backup.create("1.2.3")
        val new = phone()
        val summary = new.backup.restore(new.backup.read(ByteArrayInputStream(bytes(old, file))))

        assertEquals(BackupSummary(sites = 2, terms = 1, series = 1, translationNotes = true), summary)
        val sites = new.db.sites().all().sortedBy { it.sortOrder }
        assertEquals(listOf("Reader One", "Reader Two"), sites.map { it.name })
        with(sites[0]) {
            assertEquals("https://one.example/series/", url)
            assertTrue(pinned); assertTrue(autoTranslate)
            assertEquals(Lang.KO, sourceLang); assertEquals(EngineId.GEMINI, engineId)
        }
        assertFalse(sites[1].adBlockEnabled)
        val term = new.db.glossary().all().single()
        assertEquals("Mina", term.target); assertEquals(Pronoun.SHE, term.pronoun)
        assertEquals("Mina is the narrator.", new.db.seriesNotes().get("one.example/series"))
        assertEquals("Keep it short.", new.settings.current().translationNotes)
    }

    @Test fun theFileHoldsNothingButItsFourParts() = runBlocking {
        val p = phone()
        p.settings.setEngine(EngineId.GEMINI)
        p.settings.setCustomBaseUrl("https://api.example.com/v1")
        val json = Json.parseToJsonElement(String(bytes(p, p.backup.create("1.0.0")))).jsonObject
        assertEquals(
            setOf("format", "version", "createdAt", "appVersion", "sites", "glossary", "seriesNotes", "translationNotes"),
            json.keys,
        )
        assertFalse("other settings stay out", "api.example.com" in json.toString() || "GEMINI" in json.toString())
    }

    @Test fun aRestoreMergesSitesAndNeverDeletes() = runBlocking {
        val p = phone()
        val keptId = p.db.sites().upsert(Site(name = "Old name", url = "https://one.example/series/", sortOrder = 1))
        p.db.sites().upsert(Site(name = "Only here", url = "https://three.example/", sortOrder = 2))
        val backup = BackupFile(sites = listOf(
            // The same address (case of the host, no trailing slash): updated in place.
            BackupSite(name = "New name", url = "https://ONE.example/series", pinned = true),
            BackupSite(name = "Brand new", url = "https://two.example/"),
        ))
        p.backup.restore(backup)

        val sites = p.db.sites().all().sortedBy { it.sortOrder }
        assertEquals(listOf("New name", "Only here", "Brand new"), sites.map { it.name })
        assertEquals(keptId, sites[0].id)
        assertEquals("keeps its place", 1, sites[0].sortOrder)
        assertTrue(sites[0].pinned)
    }

    @Test fun addressesMustBecomeHttpsAndUnknownChoicesFallBack() = runBlocking {
        val p = phone()
        p.backup.restore(BackupFile(sites = listOf(
            BackupSite(name = "Plain", url = "http://plain.example/", sourceLang = "XX", engineId = "NOT_AN_ENGINE"),
            BackupSite(name = "Script", url = "javascript:alert(1)"),
            BackupSite(name = "File", url = "file:///sdcard/x.html"),
            BackupSite(name = "Planned", url = "https://planned.example/", engineId = EngineId.DEEPL.name),
            BackupSite(name = "  ", url = "https://nameless.example/"),
        )))
        val sites = p.db.sites().all().associateBy { it.url }
        assertEquals(setOf("https://plain.example/", "https://planned.example/", "https://nameless.example/"), sites.keys)
        assertNull(sites.getValue("https://plain.example/").sourceLang)
        assertNull(sites.getValue("https://plain.example/").engineId)
        assertNull("an engine not offered is not set on a site", sites.getValue("https://planned.example/").engineId)
        assertEquals("nameless.example", sites.getValue("https://nameless.example/").name)
    }

    @Test fun glossaryTermsAreUpdatedNotDuplicatedAndCapped() = runBlocking {
        val p = phone()
        p.db.glossary().upsert(GlossaryEntry(seriesKey = "s", source = "ジン", target = "Jin"))
        p.db.seriesNotes().put(SeriesNotes("s", "Old notes."))
        val summary = p.backup.restore(BackupFile(
            glossary = listOf(
                BackupTerm("s", "ジン", "Jinn", Pronoun.HE.name),
                BackupTerm("s", "ア".repeat(100), "A"),
                BackupTerm("s", "   ", "blank source"),
                BackupTerm("t", "ケイ", "Kei", "NOT_A_PRONOUN"),
            ),
            seriesNotes = listOf(BackupNotes("s", "N".repeat(900)), BackupNotes("u", "  ")),
        ))
        val terms = p.db.glossary().all()
        assertEquals(3, terms.size)
        assertEquals("Jinn", terms.single { it.source == "ジン" }.target)
        assertEquals(Pronoun.HE, terms.single { it.source == "ジン" }.pronoun)
        assertEquals(GlossaryEntry.MAX_CHARS, terms.single { it.target == "A" }.source.length)
        assertNull(terms.single { it.source == "ケイ" }.pronoun)
        assertEquals(TranslationNotes.MAX_CHARS, p.db.seriesNotes().get("s")!!.length)
        assertNull("blank notes are not saved", p.db.seriesNotes().get("u"))
        assertEquals(3, summary.terms)
        assertEquals(2, summary.series)
    }

    @Test fun translationNotesAreReplacedOnlyWhenTheFileHasThem() = runBlocking {
        val p = phone()
        p.settings.setTranslationNotes("Mine.")
        p.backup.restore(BackupFile(translationNotes = null))
        assertEquals("Mine.", p.settings.current().translationNotes)
        p.backup.restore(BackupFile(translationNotes = "  Theirs.  "))
        assertEquals("Theirs.", p.settings.current().translationNotes)
        // Notes the user cleared stay cleared, not the default.
        p.backup.restore(BackupFile(translationNotes = ""))
        assertEquals("", p.settings.current().translationNotes)
    }

    @Test fun onlyAPanelglassBackupThisVersionKnowsIsRead() {
        val p = phone()
        assertEquals(BackupProblem.UNREADABLE, problem { read(p, "not json at all") })
        assertEquals(BackupProblem.UNREADABLE, problem { read(p, """{"format":"something-else","version":1}""") })
        assertEquals(BackupProblem.UNREADABLE, problem { read(p, """{"format":"panelglass-backup","sites":"not a list"}""") })
        assertEquals(BackupProblem.NEWER, problem { read(p, """{"format":"panelglass-backup","version":99}""") })
        assertEquals(null, problem { read(p, """{"format":"panelglass-backup","version":1,"future":"ignored"}""") })
    }

    @Test fun anOversizedFileIsRefusedBeforeItIsParsed() {
        val p = phone()
        val huge = ByteArray(AppBackup.MAX_BYTES + 1) { ' '.code.toByte() }
        assertEquals(BackupProblem.UNREADABLE, problem { p.backup.read(ByteArrayInputStream(huge)) })
        val tooMany = BackupFile(sites = List(AppBackup.MAX_SITES + 1) { BackupSite("s$it", "https://s$it.example/") })
        assertEquals(BackupProblem.UNREADABLE, problem { p.backup.read(ByteArrayInputStream(bytes(p, tooMany))) })
    }

    @Test fun theFileNameCarriesTheDay() {
        assertEquals("panelglass-backup-2026-11-18.json", AppBackup.fileName(java.time.LocalDate.of(2026, 11, 18)))
        assertEquals("panelglass-backup-2026-01-02.json", AppBackup.fileName(java.time.LocalDate.of(2026, 1, 2)))
    }
}
