package com.smnexstudio.panelglass

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import com.smnexstudio.panelglass.core.ui.NavRail
import com.smnexstudio.panelglass.core.ui.widthClass
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.smnexstudio.panelglass.core.ui.R as UiR
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.smnexstudio.panelglass.core.ui.NavTab
import com.smnexstudio.panelglass.core.ui.PillNavBar
import com.smnexstudio.panelglass.feature.browser.ReaderScreen
import com.smnexstudio.panelglass.feature.browser.ReaderWarmup
import com.smnexstudio.panelglass.feature.library.HistoryScreen
import com.smnexstudio.panelglass.feature.library.LibraryScreen
import com.smnexstudio.panelglass.feature.settings.SettingsScreen
import com.smnexstudio.panelglass.feature.studio.ChapterScreen
import com.smnexstudio.panelglass.feature.studio.ImportScreen
import com.smnexstudio.panelglass.feature.studio.MangaScreen
import com.smnexstudio.panelglass.feature.studio.StudioHomeScreen
import com.smnexstudio.panelglass.feature.studio.export.ExportScreen
import com.smnexstudio.panelglass.feature.studio.fonts.FontsScreen
import com.smnexstudio.panelglass.feature.studio.importer.ImportSource
import com.smnexstudio.panelglass.feature.studio.review.ReviewScreen
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import com.smnexstudio.panelglass.core.ui.AppLocale
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.smnexstudio.panelglass.core.ui.PanelglassTheme
import dagger.hilt.android.AndroidEntryPoint

import androidx.compose.runtime.collectAsState
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.model.Settings
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var readerWarmup: ReaderWarmup
    private var startUrl by mutableStateOf<String?>(null)
    private var startEngine by mutableStateOf<String?>(null)
    private var startExtras by mutableStateOf<LaunchExtras?>(null)

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLocale.reapplyDefault(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        startUrl = intent?.getStringExtra("url") ?: intent?.dataString
        startEngine = scriptedExtra(intent) { it.getStringExtra("engine") }
        startExtras = scriptedExtra(intent) { LaunchExtras.of(it) }
        setContent {
            val settings by settingsRepo.settings.collectAsState(initial = Settings())
            PanelglassTheme(theme = settings.appTheme) {
                PanelglassMain(startUrl, startEngine, startExtras)
            }
        }
        // The reader's graph, bridge script and a spare WebView (with Chromium's first start) are prepared once the
        // first frame is up, so the site tap does not pay for them inside its transition.
        window.decorView.post { readerWarmup.schedule(this) }
    }

    override fun onDestroy() {
        readerWarmup.release(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = intent.getStringExtra("url") ?: intent.dataString
        if (!url.isNullOrBlank()) {
            startUrl = url
            startEngine = scriptedExtra(intent) { it.getStringExtra("engine") }
            startExtras = scriptedExtra(intent) { LaunchExtras.of(it) }
        }
    }

    /**
     * The activity is exported, so any app can start it: in a release build the scripted-run extras are ignored,
     * or another app could switch the saved engine and auto-start a paid provider on a page of its choosing.
     */
    private fun <T> scriptedExtra(intent: Intent?, read: (Intent) -> T?): T? =
        if (intent != null && (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) read(intent) else null
}

private object Routes {
    const val SITES = "sites"
    const val HISTORY = "history"
    const val STUDIO = "studio"
    const val STUDIO_MANGA = "studio/manga/{id}"
    const val STUDIO_CHAPTER = "studio/chapter/{id}"
    /** [ImportSource] name; `manga` preselects where a new chapter goes, `chapter` appends to that chapter. */
    const val STUDIO_IMPORT = "studio/import?source={source}&manga={manga}&chapter={chapter}"
    const val STUDIO_REVIEW = "studio/review/{chapterId}?page={page}"
    const val STUDIO_FONTS = "studio/fonts"
    const val STUDIO_EXPORT = "studio/export/{id}?chapter={chapter}"
    fun studioExport(mangaId: Long, chapterId: Long? = null) = "studio/export/$mangaId" + (chapterId?.let { "?chapter=$it" } ?: "")
    fun studioManga(id: Long) = "studio/manga/$id"
    fun studioReview(chapterId: Long, page: Int) = "studio/review/$chapterId?page=$page"
    fun studioChapter(id: Long) = "studio/chapter/$id"
    fun studioImport(source: ImportSource, mangaId: Long? = null, chapterId: Long? = null) =
        "studio/import?source=${source.name}" + (mangaId?.let { "&manga=$it" } ?: "") + (chapterId?.let { "&chapter=$it" } ?: "")
    /** `key` names a provider whose key dialog should open on arrival (the reader's "set API key" hand-off). */
    const val SETTINGS = "settings?key={key}"
    const val READER = "reader?url={url}&engine={engine}&src={src}&tgt={tgt}&start={start}&site={site}&ext={ext}"
    fun settings(keyFor: String? = null) = "settings" + (if (!keyFor.isNullOrBlank()) "?key=" + Uri.encode(keyFor) else "")
    /**
     * [siteId]: the library site that was tapped; the reader keeps its settings while it stays on that host.
     * [external]: the URL came from another app's intent, so the reader never translates it on open.
     */
    fun reader(url: String, engine: String? = null, launch: LaunchExtras? = null, siteId: Long? = null, external: Boolean = false) =
        "reader?url=" + Uri.encode(url) + (if (!engine.isNullOrBlank()) "&engine=" + Uri.encode(engine) else "") +
            (launch?.src?.let { "&src=" + Uri.encode(it) } ?: "") + (launch?.tgt?.let { "&tgt=" + Uri.encode(it) } ?: "") +
            (if (launch?.start == true) "&start=true" else "") + (if (siteId != null) "&site=$siteId" else "") +
            (if (external) "&ext=true" else "")
}

/** Scripted-run extras on the launch intent: `src`/`tgt` (`Lang` names) and `start` (auto-Start). Session only. */
data class LaunchExtras(val src: String?, val tgt: String?, val start: Boolean) {
    companion object {
        fun of(intent: Intent?) = LaunchExtras(
            intent?.getStringExtra("src"), intent?.getStringExtra("tgt"), intent?.getBooleanExtra("start", false) == true,
        )
    }
}

@Composable
private fun PanelglassMain(startUrl: String? = null, startEngine: String? = null, startExtras: LaunchExtras? = null) {
    val nav = rememberNavController()
    LaunchedEffect(startUrl, startEngine, startExtras) {
        if (!startUrl.isNullOrBlank()) {
            // Every start URL comes from an intent, which any installed app can send.
            nav.navigate(Routes.reader(startUrl, startEngine, startExtras, external = true)) {
                launchSingleTop = true
            }
        }
    }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    // The Studio's list screens keep the bar; the import flow (and later the review editor) takes the full height.
    val showBar = route != null && !route.startsWith("reader") && !route.startsWith("studio/import") && !route.startsWith("studio/review") && !route.startsWith("studio/export")

    // Phones keep the bottom pill bar; tablets, laptops and Chromebook windows get the rail on the left instead.
    val wide = widthClass().wide
    val tabs = listOf(
        NavTab(Routes.SITES, stringResource(UiR.string.nav_sites), Icons.AutoMirrored.Filled.ViewList),
        NavTab(Routes.HISTORY, stringResource(UiR.string.nav_history), Icons.Filled.History),
        NavTab(Routes.STUDIO, stringResource(UiR.string.nav_studio), Icons.Filled.AutoStories),
        NavTab("settings", stringResource(UiR.string.nav_settings), Icons.Filled.Settings),
    )
    val selectedTab = if (route?.startsWith(Routes.STUDIO) == true) Routes.STUDIO else route?.substringBefore('?')
    val onTab: (String) -> Unit = { r ->
        // A tab whose screens are already under this one (Settings opened from a Studio chapter's "Change") is gone
        // back to, where it was: the usual tab switch would save that very stack away and leave Settings in place.
        val below = nav.currentBackStack.value.dropLast(1).lastOrNull { e -> e.destination.route?.let { it == r || it.startsWith("$r/") } == true }
        if (below != null && route?.startsWith(r) != true) nav.popBackStack(below.destination.id, inclusive = false)
        else nav.navigate(if (r == "settings") Routes.settings() else r) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }
    }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AnimatedVisibility(
            visible = showBar && wide,
            enter = slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(250)) + fadeIn(animationSpec = tween(250)),
            exit = slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(200)) + fadeOut(animationSpec = tween(200)),
        ) {
            NavRail(tabs, selectedTab, onTab)
        }
        Scaffold(
            modifier = Modifier.weight(1f),
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                AnimatedVisibility(
                    visible = showBar && !wide,
                    enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(250)) + fadeIn(animationSpec = tween(250)),
                    exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(200)) + fadeOut(animationSpec = tween(200)),
                ) {
                    PillNavBar(tabs = tabs, selected = selectedTab, onSelect = onTab)
                }
            },
        ) { padding ->
            // Only the tab screens sit above the pill bar. The reader pads its own bottom bar for the nav bar, and
            // padding the whole host would resize the WebView (a full page relayout) when the pill bar finishes sliding out.
            val tabPadding = Modifier.statusBarsPadding().padding(bottom = padding.calculateBottomPadding())
            NavHost(
                nav, startDestination = Routes.SITES,
                modifier = Modifier.fillMaxSize(),
                enterTransition = { fadeIn(animationSpec = tween(220)) },
                exitTransition = { fadeOut(animationSpec = tween(180)) },
                popEnterTransition = { fadeIn(animationSpec = tween(220)) },
                popExitTransition = { fadeOut(animationSpec = tween(180)) },
            ) {
                composable(Routes.SITES) {
                    Column(tabPadding) {
                        LibraryScreen(
                            onOpen = { nav.navigate(Routes.reader(it)) },
                            onOpenSite = { site -> nav.navigate(Routes.reader(site.url, siteId = site.id)) },
                        )
                    }
                }
                composable(Routes.HISTORY) { Column(tabPadding) { HistoryScreen(onOpen = { nav.navigate(Routes.reader(it)) }) } }
                composable(Routes.STUDIO) {
                    Column(tabPadding) {
                        StudioHomeScreen(
                            onOpenManga = { nav.navigate(Routes.studioManga(it)) },
                            onImport = { nav.navigate(Routes.studioImport(it)) },
                            onOpenFonts = { nav.navigate(Routes.STUDIO_FONTS) },
                            onReview = { chapterId, page -> nav.navigate(Routes.studioReview(chapterId, page)) },
                        )
                    }
                }
                composable(Routes.STUDIO_MANGA, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                    Column(tabPadding) {
                        MangaScreen(
                            onBack = { nav.popBackStack() },
                            onOpenChapter = { nav.navigate(Routes.studioChapter(it)) },
                            onImport = { source, mangaId -> nav.navigate(Routes.studioImport(source, mangaId = mangaId)) },
                            onExport = { nav.navigate(Routes.studioExport(it)) },
                        )
                    }
                }
                composable(Routes.STUDIO_CHAPTER, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                    Column(tabPadding) {
                        ChapterScreen(
                            onBack = { nav.popBackStack() },
                            onAddImages = { nav.navigate(Routes.studioImport(ImportSource.IMAGES, chapterId = it)) },
                            onOpenSettings = { engine -> nav.navigate(Routes.settings(engine?.name)) },
                            onReview = { chapterId, page -> nav.navigate(Routes.studioReview(chapterId, page)) },
                            onExport = { mangaId, chapterId -> nav.navigate(Routes.studioExport(mangaId, chapterId)) },
                        )
                    }
                }
                composable(
                    Routes.STUDIO_REVIEW,
                    arguments = listOf(
                        navArgument("chapterId") { type = NavType.StringType },
                        navArgument("page") { type = NavType.StringType; defaultValue = "0" },
                    ),
                ) {
                    // Full height: no pill bar, and the editor pads for the keyboard itself.
                    Column(Modifier.statusBarsPadding()) { ReviewScreen(onBack = { nav.popBackStack() }, onOpenFonts = { nav.navigate(Routes.STUDIO_FONTS) }) }
                }
                composable(
                    Routes.STUDIO_EXPORT,
                    arguments = listOf(
                        navArgument("id") { type = NavType.StringType },
                        navArgument("chapter") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) {
                    // Full height: the flow keeps its total and buttons pinned at the bottom.
                    Column(Modifier.statusBarsPadding()) { ExportScreen(onBack = { nav.popBackStack() }) }
                }
                composable(Routes.STUDIO_FONTS) { Column(tabPadding) { FontsScreen(onBack = { nav.popBackStack() }) } }
                composable(
                    Routes.STUDIO_IMPORT,
                    arguments = listOf(
                        navArgument("source") { type = NavType.StringType; defaultValue = ImportSource.IMAGES.name },
                        navArgument("manga") { type = NavType.StringType; defaultValue = "" },
                        navArgument("chapter") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { entry ->
                    val appending = !entry.arguments?.getString("chapter").isNullOrBlank()
                    Column(Modifier.statusBarsPadding()) {
                        ImportScreen(
                            onBack = { nav.popBackStack() },
                            onDone = { chapterId ->
                                // Pages added to a chapter go back to it; a new chapter opens in its place.
                                if (appending) nav.popBackStack()
                                else nav.navigate(Routes.studioChapter(chapterId)) { popUpTo(Routes.STUDIO_IMPORT) { inclusive = true } }
                            },
                        )
                    }
                }
                composable(Routes.SETTINGS, arguments = listOf(navArgument("key") { type = NavType.StringType; defaultValue = "" })) { entry ->
                    val keyFor = entry.arguments?.getString("key").orEmpty().ifBlank { null }
                    Column(tabPadding) { SettingsScreen(openKeyFor = keyFor, onManageFonts = { nav.navigate(Routes.STUDIO_FONTS) }) }
                }
                // Slide only, never a fade: an alpha layer over the WebView forces a full-screen offscreen buffer each
                // frame and the page's hardware draw does not land in it, so the reader showed up blank until the
                // animation ended.
                composable(
                    Routes.READER,
                    arguments = listOf(
                        navArgument("url") { type = NavType.StringType; defaultValue = "" },
                        navArgument("engine") { type = NavType.StringType; defaultValue = "" },
                        navArgument("src") { type = NavType.StringType; defaultValue = "" },
                        navArgument("tgt") { type = NavType.StringType; defaultValue = "" },
                        navArgument("start") { type = NavType.StringType; defaultValue = "" },
                        navArgument("site") { type = NavType.StringType; defaultValue = "" },
                        navArgument("ext") { type = NavType.StringType; defaultValue = "" },
                    ),
                    enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(240)) },
                    exitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(200)) },
                    // Back from Settings: the reader is already there under the fading tab screen.
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(200)) },
                ) { entry ->
                    val url = entry.arguments?.getString("url").orEmpty()
                    val engine = entry.arguments?.getString("engine").orEmpty()
                    ReaderScreen(
                        initialUrl = url,
                        initialEngine = engine.ifBlank { null },
                        initialSrc = entry.arguments?.getString("src").orEmpty().ifBlank { null },
                        initialTgt = entry.arguments?.getString("tgt").orEmpty().ifBlank { null },
                        startNow = entry.arguments?.getString("start") == "true",
                        initialSiteId = entry.arguments?.getString("site")?.toLongOrNull(),
                        initialExternal = entry.arguments?.getString("ext") == "true",
                        onBack = { nav.popBackStack() },
                        onOpenSettings = { nav.navigate(Routes.settings()) },
                        onOpenKeySheet = { engine -> nav.navigate(Routes.settings(engine)) },
                    )
                }
            }
        }
    }
}
