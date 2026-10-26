package com.smnexstudio.panelglass.core.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every feature must be reachable from the app.
 *
 * A feature that ships only its engine — the plan, the view model, the strings — compiles, passes every unit test
 * and even lands in the APK, and is still unreachable: no button calls it. That is how the whole-page scan was
 * lost once: the engine and the view model were merged, the screen wiring was not, and nothing complained.
 *
 * This test is the guard. It reads the source and asks two questions:
 *
 *  1. **Is it wired?** The feature's entry point (a public function the view model exposes) has a caller in the UI
 *     layer — a screen, or a shared UI component.
 *  2. **Is it shown?** The strings the feature puts on screen are referenced by code, not only declared.
 *
 * The features are **discovered**, not listed: every public function of every view model is an entry point. That is
 * what makes the guard cover the next feature as well as this one — a new feature cannot be added without being
 * checked.
 *
 * Some public functions are dead on purpose: a setting that was replaced by a successor but whose stored value is
 * still read for migration, and helpers the view model calls on itself. Those are listed in [DELIBERATELY_UNCALLED]
 * with the reason, so each one is a decision rather than an oversight.
 */
class FeatureReachabilityTest {
    private val repo: File = repoRoot()

    /**
     * Public view-model functions that are not called from the UI layer, and why that is correct.
     *
     * Every entry here is a decision: either the setting was replaced by a successor (the old value is still read
     * so an existing phone keeps its choice) or the function is a helper the view model uses on itself.
     */
    private val deliberatelyUncalled = mapOf(
        "setReaderFont" to "called through the font picker's own callback",
        "toggleFontFavourite" to "called through the font picker's own callback",
        "toggleFavourite" to "called through the Studio's own row",
        "clearFailure" to "an internal reset called by the view model itself",
        "deleteTerm" to "called through the series sheet's own row",
        "saveSeriesNotes" to "called through the series sheet's own callback",
        "clearScreenPatches" to "called by the view model itself when the page changes",
        // Found by the guard once its regexes learned to look past the first function of a file: these two have
        // been unreachable for a long time. They are upstream's, and they are kept rather than deleted.
        "move" to "no caller in the Studio's import screen: the reordering it does is not offered there",
        "pickDestination" to "no caller in the Studio's import screen: the destination is chosen another way",
    )

    /** The view models: every public function of one is a feature entry point. */
    private fun viewModels(): List<File> {
        val out = mutableListOf<File>()
        for (dir in listOf("feature")) {
            val base = File(repo, dir)
            if (!base.isDirectory) continue
            base.walkTopDown()
                .filter { it.isFile && it.name.endsWith("ViewModel.kt") && !it.path.contains("/build/") }
                .forEach { out.add(it) }
        }
        return out.sortedBy { it.path }
    }

    /** The UI layer: what a user can actually reach. The view models themselves are not the UI. */
    private fun uiLayer(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (dir in listOf("feature", "core/ui/src/main", "app/src/main")) {
            val base = File(repo, dir)
            if (!base.isDirectory) continue
            base.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") && !it.path.contains("/build/") }
                .forEach { out[it.path] = it.readText() }
        }
        // The view models are not the UI: a view model calling itself is not a user reaching a feature.
        return out.filterKeys { !it.endsWith("ViewModel.kt") }
    }

    @Test
    fun everyViewModelFunctionIsReachableOrDeliberatelyNot() {
        val ui = uiLayer()
        val dead = mutableListOf<String>()

        for (vmFile in viewModels()) {
            val vm = vmFile.readText()
            val feature = vmFile.name.removeSuffix(".kt")
            // A public function of the view model: four spaces of indent, `fun name(`.
            for (m in Regex("^ {4}fun\\s+(\\w+)\\s*\\(", RegexOption.MULTILINE).findAll(vm)) {
                val fn = m.groupValues[1]
                // A function that only returns a value is a getter, not an action.
                // The MULTILINE flag matters: without it `^` only matches the start of the file, and every
                // function but the first one is skipped as a getter.
                val isAction = Regex("^ {4}fun\\s+" + Regex.escape(fn) + "\\s*\\([^)]*\\)\\s*=\\s*viewModelScope\\.launch", RegexOption.MULTILINE).containsMatchIn(vm)
                    || Regex("^ {4}fun\\s+" + Regex.escape(fn) + "\\s*\\([^)]*\\)\\s*\\{", RegexOption.MULTILINE).containsMatchIn(vm)
                if (!isAction) continue
                // The call may end in a trailing lambda (`vm.startFullPage { ... }`).
                val called = ui.values.any { Regex("\\." + Regex.escape(fn) + "\\s*[({]").containsMatchIn(it) }
                if (called) continue
                val reason = deliberatelyUncalled[fn]
                if (reason == null) {
                    dead += "$feature.$fn has no caller in the UI layer — it may be a feature nobody can reach"
                }
            }
        }
        assertEquals(emptyList<String>(), dead, "a feature is not reachable from the app")
    }

    @Test
    fun everyDeliberatelyUncalledFunctionStillExists() {
        // A stale allow-list entry hides a real dead function: the function was renamed or removed and the reason
        // no longer applies.
        val source = viewModels().joinToString("\n") { it.readText() }
        val stale = deliberatelyUncalled.keys.filterNot { source.contains("fun $it(") }
        assertEquals(emptyList<String>(), stale, "an allow-listed function no longer exists: remove it from the list")
    }

    @Test
    fun everyFeatureStringIsShownByCode() {
        val all = mutableMapOf<String, String>()
        for (dir in listOf("feature", "core", "app")) {
            val base = File(repo, dir)
            if (!base.isDirectory) continue
            base.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") && !it.path.contains("/build/") }
                .forEach { all[it.path] = it.readText() }
        }
        val kotlin = all.values.joinToString("\n")

        // Every string the app declares must be shown by code. A string that is only declared is a feature whose
        // screen is missing.
        //
        // A few strings are dead upstream already (they were declared and never used before this branch existed).
        // They are listed rather than removed, because they are not this branch's to clean up.
        val deadUpstream = setOf(
            "studio_add_font", "studio_label_genres", "studio_library", "studio_no_text", "studio_page_title",
        )

        val declared = Regex("""<string\s+name="([a-z0-9_]+)"""").findAll(
            File(repo, "core/ui/src/main/res/values/strings.xml").readText()
        ).map { it.groupValues[1] }.toSet()

        val unused = declared.filterNot { s ->
            s in deadUpstream || Regex("\\b(?:R|UiR)\\.(?:string|plurals)\\.$s\\b").containsMatchIn(kotlin)
        }.sorted()

        assertEquals(emptyList<String>(), unused, "a string is declared but never shown — a feature's screen is missing")
    }

    @Test
    fun theWholePageScanHasItsMenuItem() {
        // The exact thing that was lost: the engine and the view model were merged, the menu item was not.
        val screen = File(repo, "feature/browser/src/main/kotlin/com/smnexstudio/panelglass/feature/browser/ReaderScreen.kt")
        assertTrue(screen.exists(), "the reader screen is gone")
        val src = screen.readText()
        assertTrue(
            src.contains("reader_translate_whole_page"),
            "the reader's menu has no \"Translate whole page\" item: the scan cannot be started",
        )
        assertTrue(src.contains("vm.startFullPage"), "nothing starts the whole-page scan")
    }

    private companion object {
        fun repoRoot(): File {
            var dir: File? = File("").absoluteFile
            repeat(4) {
                if (dir != null && File(dir, "settings.gradle.kts").exists()) return dir
                dir = dir?.parentFile
            }
            return File("").absoluteFile
        }
    }
}
