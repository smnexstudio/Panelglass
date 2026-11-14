package com.smnexstudio.panelglass.feature.settings

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.data.repo.ImportTarget
import com.smnexstudio.panelglass.core.model.AppTheme
import com.smnexstudio.panelglass.core.model.Chapter
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.CustomProvider
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Manga
import com.smnexstudio.panelglass.core.model.QwenBackend
import com.smnexstudio.panelglass.core.model.SfxMode
import com.smnexstudio.panelglass.core.model.StudioPage
import com.smnexstudio.panelglass.core.model.TranslationNotes
import com.smnexstudio.panelglass.core.ui.uiName
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import com.smnexstudio.panelglass.core.ui.R as UiR

/**
 * The Settings hub and its pages over a real view model ([SettingsRig]: in-memory database and DataStore, no network):
 * the hub says what each page holds and finds any setting by name; every page's rows save their choice, and Back
 * returns to the hub. Compose on Robolectric (JUnit 4 via vintage), at phone width: one page at a time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsScreenTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    /**
     * Closes the database only after the compose rule has disposed the screen (rules wrap `@After`): a screen still
     * collecting from a closed database would fail a later test.
     */
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() { override fun after() = rig.close() }).around(compose)
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var rig: SettingsRig

    @Before fun setUp() { rig = SettingsRig(context) }

    private val DEFAULT_FIRST_LINE = TranslationNotes.DEFAULT.lineSequence().first()

    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun current() = runBlocking { rig.settings.current() }

    /** The node's own click: a sheet or dialog still animating in under Robolectric would miss an injected touch. */
    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    private fun screen(openKeyFor: String? = null) {
        val vm = rig.vm()
        compose.setContent { SettingsScreen(openKeyFor = openKeyFor, vm = vm) }
    }

    /** Opens a page from the hub by its row's title. */
    private fun open(titleRes: Int) = compose.onNodeWithText(s(titleRes)).tap()

    private fun back() = compose.onNodeWithContentDescription(s(UiR.string.cd_back)).tap()

    /** Waits for [done], letting real time pass between checks (DataStore and Room answer on their own threads). */
    private fun eventually(done: () -> Boolean) {
        repeat(100) {
            compose.waitForIdle()
            if (done()) return
            Thread.sleep(50)
        }
        compose.waitForIdle()
        assertTrue("condition not met within 5 s", done())
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()

    /** The notes editor (the Try box has a text field too, on another page). */
    private fun notesField() = compose.onNode(hasSetTextAction() and hasText(DEFAULT_FIRST_LINE, substring = true))

    /** The switch row labelled [label] (the row is the switch: one control carrying its label). */
    private fun switchOf(label: String) = compose.onNode(isToggleable() and hasText(label))

    @Test fun theHubSaysWhatEachPageHolds() {
        screen()
        compose.onNodeWithText(s(UiR.string.settings_title)).assertExists()
        assertTrue(shown(EngineId.GOOGLE.uiName(context)))
        assertTrue(shown(Lang.JA.uiName() + " → " + Lang.EN.uiName()))
        assertTrue(shown(s(UiR.string.studio_sfx_replace)))
        assertTrue(shown(s(UiR.string.update_version, "")))
    }

    @Test fun searchFindsARowAndOpensItsPage() {
        screen()
        compose.onNode(hasSetTextAction()).performTextReplacement(s(UiR.string.qwen_backend).take(6))
        compose.onNodeWithText(s(UiR.string.qwen_backend)).tap()
        eventually { shown(s(UiR.string.set_models_translation)) }
    }

    /** The system Back during a search clears it and stays in Settings; it never leaves for another tab. */
    @Test fun backClearsTheSearchFirst() {
        screen()
        compose.onNode(hasSetTextAction()).performTextReplacement(s(UiR.string.qwen_backend).take(6))
        eventually { shown(s(UiR.string.set_search_results)) }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        eventually { !shown(s(UiR.string.set_search_results)) && shown(s(UiR.string.section_translation)) }
        assertFalse(compose.activity.isFinishing)
    }

    /** A page opened from the results returns to them. */
    @Test fun backFromAResultReturnsToTheResults() {
        screen()
        compose.onNode(hasSetTextAction()).performTextReplacement(s(UiR.string.qwen_backend).take(6))
        compose.onNodeWithText(s(UiR.string.qwen_backend)).tap()
        eventually { shown(s(UiR.string.set_models_translation)) }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        eventually { shown(s(UiR.string.set_search_results)) }
        assertFalse(compose.activity.isFinishing)
    }

    @Test fun searchSaysWhenNothingMatches() {
        screen()
        compose.onNode(hasSetTextAction()).performTextReplacement("zzqqzz")
        eventually { shown(s(UiR.string.set_search_none, "zzqqzz")) }
    }

    @Test fun backReturnsToTheHub() {
        screen()
        open(UiR.string.nav_sites)
        eventually { shown(s(UiR.string.set_lettering)) }
        back()
        eventually { shown(s(UiR.string.set_group_app)) }
    }

    /** TalkBack can name every button and switch, on the hub and on every page. */
    @Test fun everyControlHasALabel() {
        screen()
        compose.waitForIdle()
        assertEquals("hub", emptyList<String>(), compose.unlabelledControls())
        for (page in listOf(
            UiR.string.set_page_engine, UiR.string.set_page_languages, UiR.string.set_page_models, UiR.string.nav_sites,
            UiR.string.nav_studio, UiR.string.settings_storage, UiR.string.set_page_appearance, UiR.string.set_page_about,
        )) {
            open(page)
            compose.waitForIdle()
            assertEquals(s(page), emptyList<String>(), compose.unlabelledControls())
            back()
        }
    }

    @Test fun aThemeTappedIsSavedAndMarked() {
        screen()
        open(UiR.string.set_page_appearance)
        compose.onNodeWithText(s(UiR.string.theme_tag_panel_pop)).tap()
        eventually { current().appTheme == AppTheme.PANEL_POP }
        compose.onAllNodesWithText(s(UiR.string.theme_tag_panel_pop)).onFirst().assertExists()
    }

    @Test fun studioRunsCanWaitForTheCharger() {
        screen()
        open(UiR.string.nav_studio)
        assertFalse(current().studioOnlyWhileCharging)
        switchOf(s(UiR.string.studio_only_charging)).tap()
        eventually { current().studioOnlyWhileCharging }
    }

    @Test fun theSourceLanguagePickedIsSaved() {
        screen()
        open(UiR.string.set_page_languages)
        compose.onNodeWithText(s(UiR.string.lang_translate_from)).tap()
        compose.onNodeWithText(Lang.KO.uiName()).tap()
        eventually { current().defaultSourceLang == Lang.KO }
        eventually { shown(Lang.KO.uiName() + " → " + Lang.EN.uiName()) }
    }

    @Test fun theSwapButtonTradesTheLanguages() {
        screen()
        open(UiR.string.set_page_languages)
        compose.onNodeWithContentDescription(s(UiR.string.set_swap)).tap()
        eventually { current().defaultSourceLang == Lang.EN && current().defaultTargetLang == Lang.JA }
    }

    @Test fun soundEffectsCanBeKept() {
        screen()
        open(UiR.string.set_page_languages)
        compose.onNodeWithText(s(UiR.string.studio_sfx_keep)).tap()
        eventually { current().sfxMode == SfxMode.SKIP }
        eventually { shown(s(UiR.string.studio_sfx_keep_note)) }
    }

    @Test fun notesAreSavedAsTheyAreTyped() {
        screen()
        open(UiR.string.set_page_languages)
        notesField().performTextReplacement("Keep honorifics.\nShort lines.")
        compose.mainClock.advanceTimeBy(1_000) // the editor saves half a second after typing stops
        eventually { current().translationNotes == "Keep honorifics.\nShort lines." }
    }

    /** Notes the user empties stay empty, and Reset brings the shipped default back. */
    @Test fun clearedNotesStayEmptyUntilReset() {
        screen()
        open(UiR.string.set_page_languages)
        notesField().performTextReplacement("")
        compose.mainClock.advanceTimeBy(1_000)
        eventually { current().translationNotes == "" }
        compose.onNodeWithText(s(UiR.string.set_notes_reset)).tap()
        eventually { current().translationNotes == TranslationNotes.DEFAULT }
    }

    @Test fun adBlockingAndTheDailyUpdateCheckAreSwitches() {
        screen()
        val adBlock = current().adBlockDefault
        val autoCheck = current().autoUpdateCheck
        open(UiR.string.nav_sites)
        switchOf(s(UiR.string.block_ads)).tap()
        eventually { current().adBlockDefault == !adBlock }
        back()
        open(UiR.string.set_page_about)
        switchOf(s(UiR.string.update_auto)).tap()
        eventually { current().autoUpdateCheck == !autoCheck }
    }

    /** Privacy and Licences are the bundled documents, read in the app; Back returns to About, not the hub. */
    @Test fun privacyAndLicencesOpenInsideTheApp() {
        screen()
        open(UiR.string.set_page_about)
        open(UiR.string.set_privacy)
        eventually { shown("What stays on your phone") && shown(s(UiR.string.set_view_on_github)) }
        back()
        eventually { shown(s(UiR.string.update_check)) }
        open(UiR.string.set_licences)
        eventually { shown("Models in the app") }
        back()
        eventually { shown(s(UiR.string.set_licences_note)) }
    }

    @Test fun theBlockListsCanBeUpdatedNow() {
        screen()
        open(UiR.string.nav_sites)
        compose.onNodeWithText(s(UiR.string.set_update_now)).tap()
        eventually { rig.blockListUpdates == 1 }
    }

    @Test fun qwenCanBeToldToRunOnTheCpu() {
        screen()
        open(UiR.string.set_page_models)
        compose.onNodeWithText(s(UiR.string.qwen_backend_cpu)).tap()
        eventually { current().qwenBackend == QwenBackend.CPU }
    }

    /** An engine's row is its choice: tapping an on-device model selects it. */
    /** Only an engine's check disc selects it; its name does not. */
    @Test fun theCheckDiscSelectsAnEngine() {
        screen()
        open(UiR.string.set_page_engine)
        compose.onNodeWithText(EngineId.QWEN15_LOCAL.uiName(context)).assertHasNoClickAction()
        compose.onNodeWithContentDescription(EngineId.QWEN15_LOCAL.uiName(context)).tap()
        eventually { current().engineId == EngineId.QWEN15_LOCAL }
    }

    /** A cloud engine's name opens its setup and closes it again, selecting nothing and asking for no key. */
    @Test fun aCloudEnginesNameOpensItsSetupWithoutSelectingIt() {
        screen()
        open(UiR.string.set_page_engine)
        compose.onNodeWithText(EngineId.CUSTOM.uiName(context)).tap()
        eventually { shown(s(UiR.string.key_base_url)) }
        assertFalse(shown(s(UiR.string.key_title, EngineId.CUSTOM.uiName(context))))
        assertEquals(EngineId.GOOGLE, current().engineId)
        compose.onNodeWithText(EngineId.CUSTOM.uiName(context)).tap()
        eventually { !shown(s(UiR.string.key_base_url)) }
    }

    /** A keyed engine without a key opens its key dialog and the selection waits for the key. */
    @Test fun aKeyedEngineWithoutAKeyAsksForIt() {
        screen()
        open(UiR.string.set_page_engine)
        compose.onNodeWithContentDescription(EngineId.GEMINI.uiName(context)).tap()
        eventually { shown(s(UiR.string.key_title, EngineId.GEMINI.uiName(context))) }
        assertEquals(EngineId.GOOGLE, current().engineId)
    }

    /** The reader hands a keyed engine over: its key dialog opens and the selection waits for the key. */
    @Test fun arrivingForAKeyOpensTheKeyDialogWithoutSwitching() {
        screen(openKeyFor = EngineId.GEMINI.name)
        eventually { shown(s(UiR.string.key_title, EngineId.GEMINI.uiName(context))) }
        assertEquals(EngineId.GOOGLE, current().engineId)
    }

    /** Custom AI's providers: a preset fills in its address, Custom asks for one, and Save stores the preset's. */
    @Test fun aCustomAiProviderFillsInItsAddress() {
        screen(openKeyFor = EngineId.CUSTOM.name)
        eventually { shown(CustomProvider.DEFAULT.baseUrl) }
        assertTrue(shown(s(UiR.string.key_base_url_set_by, CustomProvider.DEFAULT.displayName)))

        compose.onNodeWithText(CustomProvider.GROK.displayName).tap()
        eventually { shown(CustomProvider.GROK.baseUrl) }
        assertFalse(shown(CustomProvider.DEFAULT.baseUrl))

        compose.onNodeWithText(s(UiR.string.key_provider_custom)).tap()
        eventually { shown(s(UiR.string.key_base_url_note)) }
        assertFalse(shown(CustomProvider.GROK.baseUrl))

        compose.onNodeWithText(CustomProvider.OPENROUTER.displayName).tap()
        eventually { shown(CustomProvider.OPENROUTER.baseUrl) }
        val fields = compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))
        fields[0].performTextInput("test-key")
        fields[1].performTextInput("test-model")
        compose.onNodeWithText(s(UiR.string.action_save).uppercase()).tap()
        eventually { current().customBaseUrl == CustomProvider.OPENROUTER.baseUrl && current().modelFor(EngineId.CUSTOM) == "test-model" }
        assertEquals(CustomProvider.OPENROUTER.baseUrl, current().customBaseUrl)
        assertEquals("test-model", current().modelFor(EngineId.CUSTOM))
    }

    @Test fun clearingHistoryEmptiesIt() {
        runBlocking { rig.history.record("https://comics.example.org/ch/1", "A visit") }
        screen()
        open(UiR.string.nav_sites)
        compose.onAllNodesWithText(s(UiR.string.action_clear)).onFirst().tap()
        eventually { runBlocking { rig.history.recent.first().isEmpty() } }
    }

    /** Storage lists each Studio manga; Delete asks first and then removes it. */
    @Test fun aStudioMangaIsDeletedAfterConfirming() {
        runBlocking {
            val (rel, dir) = rig.studio.files.newPageDir()
            File(dir, "original.png").writeText("page")
            rig.studio.import(
                ImportTarget.NewManga(Manga(title = "Stardust"), Chapter(mangaId = 0, number = 1f)),
                listOf(StudioPage(chapterId = 0, index = 0, file = "$rel/original.png", width = 10, height = 10)),
            )
        }
        screen()
        open(UiR.string.settings_storage)
        eventually { shown("Stardust") }
        compose.onNodeWithText(s(UiR.string.action_delete)).tap()
        eventually { shown(s(UiR.string.settings_studio_delete_title, "Stardust")) }
        compose.onNodeWithText(s(UiR.string.action_delete).uppercase()).tap()
        eventually { runBlocking { rig.studio.allMangas().isEmpty() } }
        eventually { shown(s(UiR.string.settings_studio_empty)) }
    }
}
