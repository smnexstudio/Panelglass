package com.smnexstudio.panelglass.feature.studio.fonts

import com.smnexstudio.panelglass.core.ui.fonts.FindMoreFontsSheet
import com.smnexstudio.panelglass.core.ui.fonts.fontFamilyOf
import com.smnexstudio.panelglass.core.ui.fonts.roleName
import com.smnexstudio.panelglass.core.ui.fonts.scriptName
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import com.smnexstudio.panelglass.core.ui.widthClass
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.smnexstudio.panelglass.core.model.FontRole
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.UserFont
import com.smnexstudio.panelglass.core.render.StudioFonts
import com.smnexstudio.panelglass.core.ui.ChoiceSheet
import com.smnexstudio.panelglass.core.ui.DashedRow
import com.smnexstudio.panelglass.core.ui.Jakarta
import com.smnexstudio.panelglass.core.ui.LocalAppTheme
import com.smnexstudio.panelglass.core.ui.PanelCard
import com.smnexstudio.panelglass.core.ui.PrimaryPill
import com.smnexstudio.panelglass.core.ui.SectionLabel
import com.smnexstudio.panelglass.core.ui.StickerDialog
import com.smnexstudio.panelglass.core.ui.TextAction
import com.smnexstudio.panelglass.core.ui.Tokens
import com.smnexstudio.panelglass.core.ui.themeBackground
import com.smnexstudio.panelglass.feature.studio.StudioField
import com.smnexstudio.panelglass.feature.studio.StudioTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.smnexstudio.panelglass.core.ui.R as UiR

@HiltViewModel
class FontsViewModel @Inject constructor(private val store: UserFontStore) : ViewModel() {
    val fonts: StateFlow<List<UserFont>> = store.fonts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val importing = MutableStateFlow(false)
    val result = MutableStateFlow<FontImportResult?>(null)

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            importing.value = true
            try {
                result.value = store.import(uris)
            } finally {
                importing.value = false
            }
        }
    }

    suspend fun usage(f: UserFont): Int = store.usage(f.id)
    fun rename(f: UserFont, name: String) = viewModelScope.launch { store.rename(f, name) }
    fun setRole(f: UserFont, role: FontRole) = viewModelScope.launch { store.setRole(f, role) }
    fun delete(f: UserFont) = viewModelScope.launch { store.delete(f) }
}

private enum class FontAction { RENAME, ROLE, DELETE }

/**
 * My fonts (docs/STUDIO_PLAN.md › My fonts): the fonts the user added, each shown in its own face with its role and
 * scripts. Add font picks `.ttf` / `.otf` / `.ttc` files; Find more fonts lists free fonts and opens their pages. A
 * font's menu renames it, sets its role, or deletes it after saying how many bubbles use it.
 */
@Composable
fun FontsScreen(onBack: () -> Unit, vm: FontsViewModel = hiltViewModel()) {
    val fonts by vm.fonts.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<UserFont?>(null) }
    var renaming by remember { mutableStateOf<UserFont?>(null) }
    var roleFor by remember { mutableStateOf<UserFont?>(null) }
    var deleting by remember { mutableStateOf<Pair<UserFont, Int>?>(null) }
    var findMore by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.import(it) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val scriptNames = StudioFonts.SCRIPTS.associateWith { scriptName(it) }

    Column(Modifier.fillMaxSize().themeBackground(LocalAppTheme.current)) {
        StudioTopBar(stringResource(UiR.string.studio_my_fonts), onBack, subtitle = stringResource(UiR.string.studio_fonts_subtitle)) {
            if (importing) CircularProgressIndicator(Modifier.padding(end = 12.dp).width(22.dp).height(22.dp), color = Tokens.Ink, strokeWidth = 2.dp)
            else PrimaryPill(
                "+ " + stringResource(UiR.string.studio_add_font_short).uppercase(), letterSpaced = true,
                onClick = { pick.launch(UserFontStore.MIME_TYPES) }, modifier = Modifier.padding(end = 8.dp),
            )
        }
        // A phone lists the fonts; a tablet or a laptop shows them as cards side by side.
        val wide = widthClass().wide
        LazyVerticalGrid(
            if (wide) GridCells.Adaptive(320.dp) else GridCells.Fixed(1), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = if (wide) 12.dp else 0.dp, end = if (wide) 12.dp else 0.dp, bottom = 24.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(UiR.string.studio_fonts_licence_note), style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextAction(stringResource(UiR.string.studio_find_more_fonts), onClick = { findMore = true })
                }
            }
            result?.let { r ->
                item(span = { GridItemSpan(maxLineSpan) }) { ImportSummary(r, Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel(stringResource(UiR.string.studio_my_fonts)) }
            if (fonts.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(UiR.string.studio_fonts_empty), style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft, modifier = Modifier.padding(horizontal = 20.dp))
            }
            items(fonts, key = { it.id }) { f -> FontCard(f, scriptNames) { menuFor = f } }
        }
    }

    menuFor?.let { f ->
        val labels = mapOf(
            FontAction.RENAME to stringResource(UiR.string.studio_font_rename),
            FontAction.ROLE to stringResource(UiR.string.studio_font_role),
            FontAction.DELETE to stringResource(UiR.string.action_delete),
        )
        ChoiceSheet<FontAction?>(
            f.displayName, FontAction.entries, null, render = { it?.let(labels::getValue).orEmpty() },
            onPick = { a ->
                when (a) {
                    FontAction.RENAME -> renaming = f
                    FontAction.ROLE -> roleFor = f
                    FontAction.DELETE -> scope.launch { deleting = f to vm.usage(f) }
                    null -> Unit
                }
            },
            onDismiss = { menuFor = null },
        )
    }
    roleFor?.let { f ->
        val names = FontRole.entries.associateWith { roleName(it) }
        ChoiceSheet(stringResource(UiR.string.studio_font_role), FontRole.entries, f.role, render = { names.getValue(it) }, onPick = { vm.setRole(f, it) }, onDismiss = { roleFor = null })
    }
    renaming?.let { f -> RenameDialog(f, onDismiss = { renaming = null }) { vm.rename(f, it); renaming = null } }
    deleting?.let { (f, used) ->
        val fallback = StudioFonts.find(StudioFonts.defaultFor(Lang.EN))?.name.orEmpty()
        StickerDialog(onDismiss = { deleting = null }) {
            Text(stringResource(UiR.string.studio_font_delete_title, f.displayName), fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 20.sp, color = Tokens.Ink)
            Spacer(Modifier.height(10.dp))
            Text(
                if (used > 0) pluralStringResource(UiR.plurals.studio_font_delete_used, used, used, fallback)
                else stringResource(UiR.string.studio_font_delete_unused),
                style = MaterialTheme.typography.bodyMedium, color = Tokens.InkSoft,
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextAction(stringResource(UiR.string.action_cancel), Tokens.InkSoft) { deleting = null }
                Spacer(Modifier.width(8.dp))
                PrimaryPill(stringResource(UiR.string.action_delete).uppercase(), letterSpaced = true, onClick = { vm.delete(f); deleting = null })
            }
        }
    }
    if (findMore) FindMoreFontsSheet("", null, onDismiss = { findMore = false })
}

@Composable
private fun ImportSummary(r: FontImportResult, modifier: Modifier) {
    Column(modifier) {
        if (r.added.isNotEmpty()) Text(
            pluralStringResource(UiR.plurals.studio_fonts_added, r.added.size, r.added.size),
            style = MaterialTheme.typography.bodyMedium, color = Tokens.Ink,
        )
        r.refused.forEach { (name, why) ->
            Text(
                name + ": " + stringResource(
                    when (why) {
                        FontRefusal.TOO_LARGE -> UiR.string.studio_font_refused_large
                        FontRefusal.NOT_A_FONT -> UiR.string.studio_font_refused_not_font
                        FontRefusal.UNUSABLE -> UiR.string.studio_font_refused_unusable
                        FontRefusal.ALREADY_ADDED -> UiR.string.studio_font_refused_duplicate
                        FontRefusal.NO_SPACE -> UiR.string.studio_storage_full
                    },
                ),
                style = MaterialTheme.typography.bodySmall, color = if (why == FontRefusal.ALREADY_ADDED) Tokens.InkSoft else Tokens.Error,
            )
        }
    }
}

@Composable
private fun RenameDialog(f: UserFont, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(f.displayName) }
    StickerDialog(onDismiss) {
        Text(stringResource(UiR.string.studio_font_rename), fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 20.sp, color = Tokens.Ink)
        Spacer(Modifier.height(10.dp))
        StudioField(name, { name = it }, stringResource(UiR.string.studio_font_name))
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextAction(stringResource(UiR.string.action_cancel), Tokens.InkSoft, onDismiss)
            Spacer(Modifier.width(8.dp))
            PrimaryPill(stringResource(UiR.string.action_save).uppercase(), letterSpaced = true, onClick = { onSave(name) })
        }
    }
}

/** One of the user's fonts: its name, its role, a sample line in its own face, and the scripts it draws. */
@Composable
private fun FontCard(f: UserFont, scriptNames: Map<String, String>, onClick: () -> Unit) {
    val family = fontFamilyOf(f.id, Lang.EN)
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).background(Tokens.Card)
            .border(1.dp, Tokens.Border, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.displayName, fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 14.sp, color = Tokens.Ink, modifier = Modifier.weight(1f), maxLines = 1)
            Text(
                roleName(f.role), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 11.sp, color = Tokens.InkSoft,
                modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).background(Tokens.Ink.copy(alpha = 0.06f)).padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        Text(StudioFonts.sample(Lang.EN), fontFamily = family, fontSize = 22.sp, lineHeight = 28.sp, color = Tokens.Ink, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        Text(
            f.scripts.sortedBy { StudioFonts.SCRIPTS.indexOf(it) }.joinToString(", ") { scriptNames[it].orEmpty() } +
                if (StudioFonts.isBroken(f.id)) " · " + stringResource(UiR.string.studio_font_unusable) else "",
            fontFamily = Jakarta, fontSize = 12.sp, color = if (StudioFonts.isBroken(f.id)) Tokens.Error else Tokens.InkSoft, modifier = Modifier.padding(top = 4.dp),
        )
    }
}
