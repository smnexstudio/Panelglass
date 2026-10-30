package com.smnexstudio.panelglass.feature.studio.translate

import android.content.Context
import android.graphics.Bitmap
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.data.db.PanelglassDb
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.data.repo.ImportTarget
import com.smnexstudio.panelglass.core.data.repo.StudioRepository
import com.smnexstudio.panelglass.core.engine.EngineResolution
import com.smnexstudio.panelglass.core.engine.EngineResolver
import com.smnexstudio.panelglass.core.engine.TranslationEngine
import com.smnexstudio.panelglass.core.model.BrushMode
import com.smnexstudio.panelglass.core.model.BrushStroke
import com.smnexstudio.panelglass.core.model.Bubble
import com.smnexstudio.panelglass.core.model.Chapter
import com.smnexstudio.panelglass.core.model.Pt
import com.smnexstudio.panelglass.core.model.StageRules
import com.smnexstudio.panelglass.core.data.studio.StudioFiles
import com.smnexstudio.panelglass.core.model.ContextPair
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Manga
import com.smnexstudio.panelglass.core.model.ReadState
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.StudioPage
import com.smnexstudio.panelglass.core.model.StudioStage
import com.smnexstudio.panelglass.core.model.TextLine
import com.smnexstudio.panelglass.core.model.TextRegion
import com.smnexstudio.panelglass.core.model.TranslateConfig
import com.smnexstudio.panelglass.core.model.TranslateRequest
import com.smnexstudio.panelglass.core.model.TranslatedItem
import com.smnexstudio.panelglass.core.pipeline.TranslationPipeline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import com.smnexstudio.panelglass.feature.studio.MemoryDataStore
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
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
import java.io.File

/** Chapter runs with a scripted detector and engine: order of stages, persistence, resume and failures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StudioTranslatorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: PanelglassDb
    private lateinit var repo: StudioRepository
    private lateinit var settings: SettingsRepository
    private lateinit var root: File
    private val stages = ScriptedStages()
    private val resolver = FakeResolver()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, PanelglassDb::class.java).allowMainThreadQueries().build()
        root = File(context.filesDir, "translator-test").apply { deleteRecursively(); mkdirs() }
        repo = StudioRepository(db, StudioFiles(File(root, "studio")))
        settings = SettingsRepository(PreferenceDataStoreFactory.create { File(root, "settings.preferences_pb") })
    }

    @After fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private fun translator() = StudioTranslator(repo, stages, FakeLoader, resolver, settings)

    /** A chapter of [n] pages named p1..pn; each page has two bubbles "<page>-a", "<page>-b". */
    private suspend fun chapter(n: Int): Long {
        val pages = (1..n).map { i ->
            val (rel, dir) = repo.files.newPageDir()
            File(dir, "original.png").writeText("p$i")
            StudioPage(chapterId = 0, index = 0, file = "$rel/original.png", width = 100, height = 150)
        }
        return repo.import(ImportTarget.NewManga(Manga(title = "M", srcLang = Lang.JA, tgtLang = Lang.EN), Chapter(mangaId = 0)), pages)
    }

    private suspend fun pages(ch: Long) = repo.pageList(ch)
    private suspend fun bubbles(p: StudioPage) = repo.bubbleList(p.id)

    @Test fun everyPageIsReadBeforeAnyIsTranslatedAndContextCarries() = runBlocking {
        val ch = chapter(3)
        val t = translator()
        t.run(ch)
        assertEquals(listOf("detect p1", "detect p2", "detect p3", "translate p1", "translate p2", "translate p3"), stages.log)
        val ps = pages(ch)
        ps.forEach { p ->
            assertTrue(p.stages.containsAll(listOf(StudioStage.DETECTED, StudioStage.READ, StudioStage.TRANSLATED)))
            assertNull(p.failed)
        }
        assertEquals(listOf("p2-a" to "EN:p2-a", "p2-b" to "EN:p2-b"), bubbles(ps[1]).map { it.sourceText to it.translatedText })
        // Page 2 was translated knowing how page 1 ended.
        assertEquals(listOf("p1-a", "p1-b"), stages.priors[1].map { it.source })
        assertEquals(RunPhase.DONE, t.runs.value.getValue(ch).phase)
        assertEquals(3, t.runs.value.getValue(ch).done)
    }

    @Test fun aStoppedRunResumesFromThePageMissingAStage() = runBlocking {
        val ch = chapter(3)
        stages.failOn["p2"] = EngineFailure.Network(EngineId.GOOGLE)
        val t = translator()
        t.run(ch)
        assertEquals(RunPhase.FAILED, t.runs.value.getValue(ch).phase)
        assertTrue(t.runs.value.getValue(ch).failure is EngineFailure.Network)
        val after = pages(ch)
        assertTrue(StudioStage.TRANSLATED in after[0].stages)
        assertFalse(StudioStage.TRANSLATED in after[1].stages)
        assertTrue(StudioTranslator.decode(after[1].failed) is EngineFailure.Network)
        assertFalse("the run stopped at the failure", StudioStage.TRANSLATED in after[2].stages)

        stages.failOn.clear()
        stages.log.clear()
        stages.priors.clear()
        t.run(ch)
        // Nothing is read again, and page 1 is not translated again; page 2 still gets page 1's ending.
        assertEquals(listOf("translate p2", "translate p3"), stages.log)
        assertEquals(listOf("p1-a", "p1-b"), stages.priors[0].map { it.source })
        assertTrue(pages(ch).all { StudioStage.TRANSLATED in it.stages && it.failed == null })
    }

    @Test fun aMissingKeyStopsBeforeAnythingAndNeverSwitchesEngine() = runBlocking {
        val ch = chapter(2)
        settings.setEngine(EngineId.GEMINI)
        resolver.failure = EngineFailure.MissingKey(EngineId.GEMINI)
        val t = translator()
        t.run(ch)
        assertEquals(EngineFailure.MissingKey(EngineId.GEMINI), t.runs.value.getValue(ch).failure)
        assertTrue(stages.log.isEmpty())
        assertEquals(EngineId.GEMINI, settings.current().engineId)
        assertEquals(listOf(EngineId.GEMINI), resolver.asked)
    }

    @Test fun aTimeoutFailsOnlyItsPage() = runBlocking {
        val ch = chapter(3)
        stages.failOn["p2"] = EngineFailure.Unavailable(EngineId.GOOGLE, "Watchdog: translate 50s")
        val t = translator()
        t.run(ch)
        val run = t.runs.value.getValue(ch)
        assertEquals(RunPhase.FAILED, run.phase)
        assertEquals(1, run.failedPages)
        val ps = pages(ch)
        assertTrue(StudioStage.TRANSLATED in ps[0].stages)
        assertFalse(StudioStage.TRANSLATED in ps[1].stages)
        assertTrue(StudioStage.TRANSLATED in ps[2].stages)
    }

    @Test fun anEngineThatReadsCropsFillsTheSourceTextAndATextEngineReadsAgain() = runBlocking {
        val ch = chapter(1)
        settings.setEngine(EngineId.GEMINI)
        translator().run(ch)
        var p = pages(ch).single()
        assertTrue(StudioStage.DETECTED in p.stages && StudioStage.READ in p.stages)
        assertEquals(listOf("read p1-0", "read p1-1"), bubbles(p).map { it.sourceText })
        assertTrue(stages.readFlags.single() == false)

        // A page detected without OCR text only: switching to a text engine reads it again.
        p = p.copy(stages = setOf(StudioStage.DETECTED))
        assertTrue(StudioTranslator.needsReading(p, read = true))
        assertFalse(StudioTranslator.needsReading(p, read = false))
    }

    @Test fun translateAgainRedoesEverythingButKeepsDismissedBubbles() = runBlocking {
        val ch = chapter(2)
        val t = translator()
        t.run(ch)
        val p1 = pages(ch)[0]
        val dismissed = bubbles(p1).first().copy(ignored = true)
        db.studio().updateBubble(dismissed)
        repo.updatePage(repo.getPage(p1.id)!!.let { it.copy(stages = it.stages + StudioStage.REVIEWED) })
        stages.log.clear()
        t.run(ch, redo = true)
        assertEquals(listOf("detect p1", "detect p2", "translate p1", "translate p2"), stages.log)
        val after = bubbles(pages(ch)[0])
        assertEquals(1, after.count { it.ignored })
        assertEquals(3, after.size)
        // A new translation has to be reviewed again.
        assertFalse(StudioStage.REVIEWED in pages(ch)[0].stages)
    }

    /** Lets [passes] pages through, then holds every later page until the run is cancelled (the app killed). */
    private class CuttingGate(private val passes: Int) : RunGate {
        @Volatile var waits = 0
        override val userPaused = MutableStateFlow(false)
        override fun setUserPaused(paused: Boolean) = Unit
        override suspend fun await(engine: EngineId, paused: suspend (PauseReason?) -> Unit) {
            if (++waits > passes) awaitCancellation()
        }
    }

    /**
     * A Translate again cut short (killed or cancelled) is finished by the next ordinary run: the pages it had not
     * redone yet are waiting to be done, and nothing it already redid is done twice.
     */
    @Test fun aTranslateAgainCutShortIsFinishedByTheNextRun() = runBlocking<Unit> {
        val ch = chapter(3)
        translator().run(ch)
        stages.log.clear()
        // The redo reads three pages and translates the first; then the app dies before page 2.
        val gate = CuttingGate(passes = 4)
        val cut = translator().also { it.gate = gate }
        val job = launch(Dispatchers.Default) { cut.run(ch, redo = true) }
        // At the gate before page 2's translation: page 1's is saved.
        withTimeout(5_000) { while (gate.waits <= 4) delay(10) }
        job.cancelAndJoin()
        assertEquals(listOf("detect p1", "detect p2", "detect p3", "translate p1"), stages.log)
        assertEquals("pages 2 and 3 are marked to be done again", listOf(true, false, false), pages(ch).map { StudioStage.TRANSLATED in it.stages })
        stages.log.clear()
        // The next start resumes as an ordinary run (no redo flag): it finishes the redo.
        val next = translator()
        next.run(ch)
        assertEquals(listOf("translate p2", "translate p3"), stages.log)
        assertTrue(pages(ch).all { StudioStage.TRANSLATED in it.stages && StudioStage.CLEANED in it.stages })
        assertEquals(RunPhase.DONE, next.runs.value.getValue(ch).phase)
    }

    /** An engine that cannot be used stops a Translate again before it clears anything. */
    @Test fun aTranslateAgainThatCannotStartClearsNothing() = runBlocking<Unit> {
        val ch = chapter(2)
        translator().run(ch)
        resolver.failure = EngineFailure.MissingKey(EngineId.GEMINI)
        translator().run(ch, redo = true)
        assertTrue(pages(ch).all { StudioStage.TRANSLATED in it.stages })
    }

    @Test fun anEditedSourceTextIsWhatGetsTranslated() = runBlocking {
        val ch = chapter(1)
        val t = translator()
        t.run(ch)
        val p = pages(ch).single()
        val b = bubbles(p).first()
        db.studio().updateBubble(b.copy(sourceText = "fixed"))
        repo.updatePage(p.copy(stages = p.stages - StudioStage.TRANSLATED))
        t.run(ch)
        assertEquals("EN:fixed", bubbles(p).first().translatedText)
    }

    @Test fun oneBubbleIsTranslatedAgainWithTheBubblesBeforeItAsContext() = runBlocking {
        val ch = chapter(1)
        val t = translator()
        t.run(ch)
        val p = pages(ch).single()
        val second = bubbles(p)[1]
        db.studio().updateBubble(second.copy(sourceText = "corrected"))
        stages.log.clear(); stages.priors.clear()
        assertNull(t.retranslateBubble(second.id, p.id))
        assertEquals(listOf("translate p1"), stages.log)
        assertEquals(listOf("p1-a"), stages.priors.single().map { it.source })
        assertEquals("EN:corrected", bubbles(p)[1].translatedText)
        assertEquals("EN:p1-a", bubbles(p)[0].translatedText)
    }

    @Test fun aBoxedBubbleIsReadTranslatedAndAddedLast() = runBlocking {
        val ch = chapter(1)
        val t = translator()
        t.run(ch)
        val p = pages(ch).single()
        val (id, failure) = t.addBubble(p.id, IntRect(5, 100, 95, 140), RegionKind.SFX)
        assertNull(failure)
        val added = bubbles(p).last()
        assertEquals(id, added.id)
        assertEquals(RegionKind.SFX, added.kind)
        assertEquals("boxed", added.sourceText)
        assertEquals("EN:boxed", added.translatedText)
        assertEquals(StudioTranslator.rect(IntRect(5, 100, 95, 140)), added.polygon)
    }

    @Test fun aBoxedBubbleIsKeptWhenTheEngineCannotBeUsed() = runBlocking {
        val ch = chapter(1)
        translator().run(ch)
        val p = pages(ch).single()
        resolver.failure = EngineFailure.MissingKey(EngineId.GEMINI)
        val (id, failure) = translator().addBubble(p.id, IntRect(5, 100, 95, 140), RegionKind.ENCLOSED)
        assertEquals(EngineFailure.MissingKey(EngineId.GEMINI), failure)
        assertEquals(id, bubbles(p).last().id)
        assertEquals("", bubbles(p).last().translatedText)
    }

    @Test fun everyPageIsCleanedAfterEveryPageIsTranslated() = runBlocking {
        val ch = chapter(2)
        val t = translator()
        t.run(ch)
        assertEquals(listOf("p1", "p2"), stages.cleaned.map { it.first })
        pages(ch).forEach { p ->
            assertTrue(StudioStage.CLEANED in p.stages)
            assertTrue(repo.files.file(StudioFiles.layer(p.file, StudioFiles.CLEAN)).isFile)
        }
        // A second run has nothing left to clean.
        stages.cleaned.clear()
        t.run(ch)
        assertTrue(stages.cleaned.isEmpty())
    }

    @Test fun cleaningAPageUsesItsBubblesAndStrokes() = runBlocking {
        val ch = chapter(1)
        val t = translator()
        t.run(ch)
        val p = pages(ch).single()
        val stroke = BrushStroke(BrushMode.CLEAN, 6f, listOf(Pt(10f, 10f), Pt(20f, 20f)))
        StrokeStore.write(repo.files.file(StudioFiles.layer(p.file, StudioFiles.STROKES)), listOf(stroke))
        repo.updatePage(p.copy(stages = StageRules.afterCleanInputChange(p.stages)))
        stages.cleaned.clear()
        assertTrue(t.cleanPage(p.id))
        val (_, bubbles, strokes) = stages.cleaned.single()
        assertEquals(2, bubbles.size)
        assertEquals(listOf(stroke), strokes)
        assertTrue(StudioStage.CLEANED in repo.getPage(p.id)!!.stages)
    }

    // ---- fakes ---------------------------------------------------------------------------------

    // ---- runs in the background: the gate, pause, and a run cut short --------------------------------------------

    /** A gate that holds the run at its first page with [reason] until [open] is completed, and counts every wait. */
    private class HoldingGate(val reason: PauseReason) : RunGate {
        val open = CompletableDeferred<Unit>()
        @Volatile var waits = 0
        override val userPaused = MutableStateFlow(false)
        override fun setUserPaused(paused: Boolean) { userPaused.value = paused }
        override suspend fun await(engine: EngineId, paused: suspend (PauseReason?) -> Unit) {
            waits++
            if (open.isCompleted) return
            paused(reason)
            open.await()
            paused(null)
        }
    }

    /** Settings in memory for started runs: they write them more than once (a file-backed DataStore fails on Windows). */
    private val memorySettings = SettingsRepository(MemoryDataStore())
    private fun backgroundTranslator() = StudioTranslator(repo, stages, FakeLoader, resolver, memorySettings)

    /** Before every page of every phase the run asks the gate; while held it says why and does nothing. */
    @Test fun aHeldRunWaitsBeforeItsNextPageAndSaysWhy() = runBlocking<Unit> {
        val ch = chapter(2)
        val gate = HoldingGate(PauseReason.HOT)
        val t = translator().also { it.gate = gate }
        val job = launch(Dispatchers.Default) { t.run(ch) }
        withTimeout(5_000) { t.runs.first { it[ch]?.paused == PauseReason.HOT } }
        assertTrue("nothing is read while held", stages.log.isEmpty())
        assertEquals(RunPhase.READING, t.runs.value.getValue(ch).phase)
        gate.open.complete(Unit)
        job.join()
        assertEquals(RunPhase.DONE, t.runs.value.getValue(ch).phase)
        assertNull(t.runs.value.getValue(ch).paused)
        assertEquals("2 reads, 2 translations, 2 cleans", 6, gate.waits)
    }

    /** A run cut short (the app killed) is recorded and carries on at the next start; an ended one is forgotten. */
    @Test fun aRunCutShortCarriesOnAtTheNextStart() = runBlocking<Unit> {
        val ch = chapter(2)
        val t = backgroundTranslator()
        val s = memorySettings
        s.setStudioRunChapter(ch) // as a killed process leaves it
        t.resumeInterrupted()
        withTimeout(10_000) { t.runs.first { it[ch]?.phase == RunPhase.DONE } }
        assertTrue(pages(ch).all { StudioStage.TRANSLATED in it.stages })
        withTimeout(5_000) { while (s.studioRunChapter() != null) delay(10) }
        // A later onCreate in the same process (a rotation) finds nothing to resume, even with a record left.
        s.setStudioRunChapter(ch)
        t.dismiss(ch)
        t.resumeInterrupted()
        assertNull(t.runs.value[ch])
    }

    @Test fun nothingIsResumedWhenNoRunWasCutShort() = runBlocking<Unit> {
        val ch = chapter(1)
        val t = backgroundTranslator()
        t.resumeInterrupted()
        assertNull(t.runs.value[ch])
    }

    /** Cancel ends a run held at the gate, forgets it, and clears the user's pause for the next run. */
    @Test fun cancellingAHeldRunEndsItAndForgetsIt() = runBlocking<Unit> {
        val ch = chapter(2)
        val gate = HoldingGate(PauseReason.USER)
        val t = backgroundTranslator().also { it.gate = gate }
        val s = memorySettings
        t.pause()
        t.start(ch)
        withTimeout(5_000) { t.runs.first { it[ch]?.paused == PauseReason.USER } }
        assertEquals(ch, s.studioRunChapter())
        t.cancel(ch)
        assertEquals(RunPhase.CANCELLED, t.runs.value.getValue(ch).phase)
        assertFalse(t.userPaused.value)
        withTimeout(5_000) { while (s.studioRunChapter() != null) delay(10) }
        assertTrue(stages.log.isEmpty())
    }

    private object FakeLoader : PageLoader {
        override fun load(file: File) = LoadedPage(Bitmap.createBitmap(100, 150, Bitmap.Config.ARGB_8888).also { it.density = file.readText().drop(1).toInt() }, 1f)
    }

    /** Pages are told apart by the number the loader writes into the bitmap's density. */
    private class ScriptedStages : StudioStages {
        val log = mutableListOf<String>()
        val priors = mutableListOf<List<ContextPair>>()
        val readFlags = mutableListOf<Boolean>()
        val failOn = mutableMapOf<String, EngineFailure>()

        private fun name(b: Bitmap) = "p${b.density}"

        override suspend fun detect(bitmap: Bitmap, cfg: TranslateConfig): List<TextRegion> {
            val n = name(bitmap)
            log += "detect $n"
            val read = !cfg.engineId.readsCrops
            readFlags += read
            return listOf("a", "b").mapIndexed { i, s ->
                val box = IntRect(10, 10 + i * 60, 90, 60 + i * 60)
                if (!read) TextRegion.unread(box, RegionKind.ENCLOSED).copy(container = null)
                else TextRegion(bbox = box, kind = RegionKind.ENCLOSED, text = "$n-$s", lines = listOf(TextLine(box, "$n-$s")))
            }
        }

        /** Pages cleaned, in order, with the bubbles and strokes each was given (kept apart from [log]). */
        val cleaned = mutableListOf<Triple<String, List<Bubble>, List<BrushStroke>>>()

        override fun clean(bitmap: Bitmap, bubbles: List<Bubble>, strokes: List<BrushStroke>): Bitmap {
            cleaned += Triple(name(bitmap), bubbles, strokes)
            return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        override suspend fun read(bitmap: Bitmap, rect: IntRect, cfg: TranslateConfig, kind: RegionKind): TextRegion {
            log += "read ${name(bitmap)}"
            if (cfg.engineId.readsCrops) return TextRegion.unread(rect, kind)
            return TextRegion(bbox = rect, kind = kind, text = "boxed", lines = listOf(TextLine(rect, "boxed")), container = rect)
        }

        override suspend fun translate(bitmap: Bitmap, regions: List<TextRegion>, cfg: TranslateConfig, prior: List<ContextPair>): TranslationPipeline.StudioTranslation {
            val n = name(bitmap)
            log += "translate $n"
            priors += prior
            failOn[n]?.let { throw EngineException(it) }
            val read = regions.mapIndexed { i, r -> if (r.unread) r.copy(text = "read $n-$i", state = ReadState.READ) else r }
            val out = read.map { "EN:" + it.text }
            return TranslationPipeline.StudioTranslation(read, out, read.zip(out) { r, t -> ContextPair(r.text, t) }.takeLast(2))
        }
    }

    private class FakeResolver : EngineResolver {
        var failure: EngineFailure? = null
        val asked = mutableListOf<EngineId>()
        override suspend fun resolveSelected() = error("the Studio resolves the engine it was given")
        override suspend fun resolve(id: EngineId): EngineResolution {
            asked += id
            return failure?.let { EngineResolution.Failed(it) } ?: EngineResolution.Ready(object : TranslationEngine {
                override val id = id
                override val acceptsImages = false
                override suspend fun translate(req: TranslateRequest): List<TranslatedItem> = error("not called")
            })
        }
    }
}
