package com.smnexstudio.panelglass.feature.settings

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.data.repo.ImportTarget
import com.smnexstudio.panelglass.core.model.Chapter
import com.smnexstudio.panelglass.core.model.EngineId
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import com.smnexstudio.panelglass.core.ui.R as UiR

/**
 * The Settings screen over a real view model ([SettingsRig]: in-memory database and DataStore, no network): each row
 * opens its choices and the choice is saved, the notes dialog, the switches, history and the Studio's storage. Compose
 * on Robolectric (JUnit 4 via vintage).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsScreenTest {
    private val compose = createComposeRule()
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

    /** The notes dialog's field (the Try box has a text field too). */
    private fun notesField() = compose.onNode(hasSetTextAction() and hasText(DEFAULT_FIRST_LINE, substring = true))

    /** The switch row labelled [label] (the row is the switch: one control carrying its label). */
    private fun switchOf(label: String) = compose.onNode(isToggleable() and hasText(label))

    @Test fun theRowsShowTheSavedChoices() {
        screen()
        compose.onNodeWithText(s(UiR.string.settings_title)).assertExists()
        compose.onNodeWithText(EngineId.GOOGLE.uiName(context)).assertExists()
        compose.onNodeWithText(Lang.JA.uiName()).assertExists()
        compose.onNodeWithText(s(UiR.string.studio_sfx_replace)).assertExists()
        compose.onNodeWithText(DEFAULT_FIRST_LINE).assertExists()
    }

    /** The theme cards: the one tapped is saved and marked as selected. */
    @Test fun aThemeTappedIsSavedAndMarked() {
        screen()
        compose.onNodeWithText(s(UiR.string.theme_tag_panel_pop)).tap()
        eventually { current().appTheme == com.smnexstudio.panelglass.core.model.AppTheme.PANEL_POP }
        compose.onNodeWithContentDescription(s(UiR.string.cd_selected), useUnmergedTree = true).assertExists()
    }

    /** TalkBack can name every button and switch on the screen. */
    @Test fun everyControlHasALabel() {
        screen()
        compose.waitForIdle()
        assertEquals(emptyList<String>(), compose.unlabelledControls())
    }

    @Test fun theSourceLanguagePickedIsSaved() {
        screen()
        compose.onNodeWithText(s(UiR.string.lang_source)).tap()
        compose.onNodeWithText(Lang.KO.uiName()).tap()
        eventually { current().defaultSourceLang == Lang.KO }
    }

    @Test fun soundEffectsCanBeKept() {
        screen()
        compose.onNodeWithText(s(UiR.string.studio_role_sfx)).tap()
        compose.onNodeWithText(s(UiR.string.studio_sfx_keep)).tap()
        eventually { current().sfxMode == SfxMode.SKIP }
        eventually { shown(s(UiR.string.studio_sfx_keep)) }
    }

    @Test fun notesAreSavedAndTheirFirstLineShown() {
        screen()
        compose.onNodeWithText(s(UiR.string.action_edit)).tap()
        notesField().performTextReplacement("Keep honorifics.\nShort lines.")
        compose.onNodeWithText(s(UiR.string.action_save).uppercase()).tap()
        eventually { current().translationNotes == "Keep honorifics.\nShort lines." }
        eventually { shown("Keep honorifics.") }
    }

    @Test fun cancellingTheNotesDialogSavesNothing() {
        screen()
        compose.onNodeWithText(s(UiR.string.action_edit)).tap()
        notesField().performTextReplacement("Not saved")
        compose.onNodeWithText(s(UiR.string.action_cancel)).tap()
        compose.waitForIdle()
        assertEquals(TranslationNotes.DEFAULT, current().translationNotes)
    }

    /** Notes the user empties stay empty: the row says what notes are for, and the default does not come back. */
    @Test fun clearedNotesStayEmpty() {
        screen()
        compose.onNodeWithText(s(UiR.string.action_edit)).tap()
        notesField().performTextReplacement("")
        compose.onNodeWithText(s(UiR.string.action_save).uppercase()).tap()
        eventually { current().translationNotes == "" }
        eventually { shown(s(UiR.string.settings_notes_empty)) }
    }

    @Test fun adBlockingAndTheDailyUpdateCheckAreSwitches() {
        screen()
        val adBlock = current().adBlockDefault
        val autoCheck = current().autoUpdateCheck
        switchOf(s(UiR.string.block_ads)).tap()
        eventually { current().adBlockDefault == !adBlock }
        switchOf(s(UiR.string.update_auto)).tap()
        eventually { current().autoUpdateCheck == !autoCheck }
    }

    @Test fun qwenCanBeToldToRunOnTheCpu() {
        screen()
        compose.onNodeWithText(s(UiR.string.compute_backend)).tap()
        compose.onNodeWithText(s(UiR.string.compute_backend_cpu_note)).tap()
        eventually { current().qwenBackend == QwenBackend.CPU }
    }

    /** The engine row opens the picker; an on-device model's disc selects it. */
    @Test fun theEngineRowOpensThePicker() {
        screen()
        compose.onNodeWithText(EngineId.GOOGLE.uiName(context)).tap()
        compose.onNodeWithContentDescription(s(UiR.string.cd_select_engine, EngineId.QWEN15_LOCAL.uiName(context)), useUnmergedTree = true).tap()
        eventually { current().engineId == EngineId.QWEN15_LOCAL }
    }

    /** The reader hands a keyed engine over: its key dialog opens and the selection waits for the key. */
    @Test fun arrivingForAKeyOpensTheKeyDialogWithoutSwitching() {
        screen(openKeyFor = EngineId.GEMINI.name)
        eventually { shown(s(UiR.string.key_title, EngineId.GEMINI.uiName(context))) }
        assertEquals(EngineId.GOOGLE, current().engineId)
    }

    @Test fun clearingHistoryEmptiesIt() {
        runBlocking { rig.history.record("https://comics.example.org/ch/1", "A visit") }
        screen()
        compose.onNodeWithText(s(UiR.string.action_clear)).tap()
        eventually { runBlocking { rig.history.recent.first().isEmpty() } }
    }

    /** Studio storage lists each manga; Delete asks first and then removes it. */
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
        eventually { shown("Stardust") }
        compose.onNodeWithText(s(UiR.string.action_delete)).tap()
        eventually { shown(s(UiR.string.settings_studio_delete_title, "Stardust")) }
        compose.onNodeWithText(s(UiR.string.action_delete).uppercase()).tap()
        eventually { runBlocking { rig.studio.allMangas().isEmpty() } }
        eventually { shown(s(UiR.string.settings_studio_empty)) }
    }
}
