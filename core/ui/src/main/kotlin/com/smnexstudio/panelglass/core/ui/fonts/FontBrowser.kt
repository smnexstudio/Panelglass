package com.smnexstudio.panelglass.core.ui.fonts

import com.smnexstudio.panelglass.core.ui.verticalScrollbar
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smnexstudio.panelglass.core.model.FontRole
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.render.FontEntry
import com.smnexstudio.panelglass.core.render.StudioFonts
import com.smnexstudio.panelglass.core.ui.Jakarta
import com.smnexstudio.panelglass.core.ui.StickerDialog
import com.smnexstudio.panelglass.core.ui.TextAction
import com.smnexstudio.panelglass.core.ui.Tokens
import com.smnexstudio.panelglass.core.ui.uiName
import com.smnexstudio.panelglass.core.ui.R as UiR

/**
 * What the font browser needs from the screen: the favourites (ordered) and a way to star one, a key that changes when
 * the user's fonts change (so the lists are read again), and the way to the My fonts screen.
 */
class FontChoices(
    val favourites: List<String>,
    val userFontsKey: Any?,
    val onFavourite: (String) -> Unit,
    val onManage: () -> Unit,
)

private enum class FontFilter { ALL, DIALOGUE, SFX, CAPTION, FAVOURITES, MINE }

/** A font's name in the UI: the phone's own fonts are named in the UI language. */
@Composable
fun fontName(f: FontEntry): String = when (f.id) {
    "${FontEntry.SYS}sans" -> stringResource(UiR.string.studio_font_sans)
    "${FontEntry.SYS}serif" -> stringResource(UiR.string.studio_font_serif)
    else -> f.name
}

@Composable
fun scriptName(script: String): String = stringResource(
    when (script) {
        "vietnamese" -> UiR.string.script_vietnamese
        "cyrillic" -> UiR.string.script_cyrillic
        "ja" -> UiR.string.script_ja
        "ko" -> UiR.string.script_ko
        "zh-hans" -> UiR.string.script_zh_hans
        "zh-hant" -> UiR.string.script_zh_hant
        "thai" -> UiR.string.script_thai
        "arabic" -> UiR.string.script_arabic
        "devanagari" -> UiR.string.script_devanagari
        else -> UiR.string.script_latin
    },
)

@Composable
fun roleName(r: FontRole): String = stringResource(
    when (r) {
        FontRole.DIALOGUE -> UiR.string.studio_role_dialogue
        FontRole.SFX -> UiR.string.studio_role_sfx
        FontRole.CAPTION -> UiR.string.studio_role_caption
    },
)

/** The font drawn as Compose text (the same typeface the lettering uses, fallback included). */
@Composable
fun fontFamilyOf(id: String?, tgt: Lang, role: FontRole = FontRole.DIALOGUE): FontFamily = remember(id, tgt, role) {
    runCatching { FontFamily(androidx.compose.ui.text.font.Typeface(StudioFonts.typeface(id, tgt, bold = false, italic = false, role = role))) }
        .getOrDefault(FontFamily.Default)
}

/**
 * The style panel's font field: the font's name in its own face, "Auto · <default>" when the bubble follows the
 * language, or "Font removed · using <default>" when its font was deleted. A tap opens the browser.
 */
@Composable
fun FontField(fontId: String?, tgt: Lang, role: FontRole, onClick: () -> Unit) {
    val entry = StudioFonts.find(fontId)
    val default = StudioFonts.find(StudioFonts.defaultFor(tgt, role))
    val label = when {
        fontId == null -> stringResource(UiR.string.studio_color_auto) + " · " + (default?.let { fontName(it) } ?: "")
        entry == null -> stringResource(UiR.string.studio_font_removed, default?.let { fontName(it) } ?: "")
        else -> fontName(entry)
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Tokens.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label, fontFamily = fontFamilyOf(entry?.id, tgt, role), fontSize = 16.sp, color = if (fontId != null && entry == null) Tokens.Error else Tokens.Ink,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Icon(Icons.Filled.ArrowDropDown, null, tint = Tokens.InkSoft)
    }
}

/**
 * Every font, searchable (name, tags, role, script names; case and accents ignored), filtered by role, favourites or
 * the user's own, and by default only those that draw [tgt]'s script. Favourites come first, then the user's fonts,
 * then the core set by role, then the phone's own. Each row shows its name and a sample line in its face; the star
 * adds or removes a favourite, a long press shows the font's details. A tap applies it at once (the page shows it).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FontBrowser(
    fontId: String?, tgt: Lang, role: FontRole, choices: FontChoices,
    onFont: (String?) -> Unit, onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(if (role == FontRole.SFX) FontFilter.SFX else FontFilter.ALL) }
    var fitsOnly by remember { mutableStateOf(true) }
    var details by remember { mutableStateOf<FontEntry?>(null) }
    var findMore by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    val roleNames = FontRole.entries.associateWith { roleName(it) }
    val scriptNames = StudioFonts.SCRIPTS.associateWith { scriptName(it) }
    val names = (StudioFonts.entries).associate { it.id to fontName(it) }
    val all = remember(choices.userFontsKey) { StudioFonts.entries }
    val shown = all.filter { f ->
        (!fitsOnly || StudioFonts.fits(f, tgt)) &&
            when (filter) {
                FontFilter.ALL -> true
                FontFilter.DIALOGUE -> f.role == FontRole.DIALOGUE
                FontFilter.SFX -> f.role == FontRole.SFX
                FontFilter.CAPTION -> f.role == FontRole.CAPTION
                FontFilter.FAVOURITES -> f.id in choices.favourites
                FontFilter.MINE -> f.isUser
            } &&
            StudioFonts.matches(query, names[f.id].orEmpty(), f.tags.joinToString(" "), roleNames.getValue(f.role), f.scripts.joinToString(" ") { scriptNames[it].orEmpty() })
    }.sortedWith(
        compareBy<FontEntry>(
            { if (it.id in choices.favourites) 0 else 1 },
            { if (it.id in choices.favourites) choices.favourites.indexOf(it.id) else 0 },
            { if (it.isUser) 0 else if (it.isSystem) 2 else 1 },
            { it.role.ordinal },
        ),
    )

    // Search, filters and Add stay put; only the list scrolls, a few fonts at a time (more on a wider screen), so a
    // long list never pushes "Add your own font" out of reach.
    val width = LocalConfiguration.current.screenWidthDp
    val visibleRows = when {
        width >= 840 -> 9
        width >= 600 -> 7
        else -> 5
    }
    val list = rememberLazyListState()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(UiR.string.cd_back), tint = Tokens.Ink)
            }
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(50)).border(1.5.dp, Tokens.Ink, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, tint = Tokens.InkSoft, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(stringResource(UiR.string.studio_font_search), color = Tokens.InkFaint, fontSize = 15.sp)
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = TextStyle(color = Tokens.Ink, fontSize = 15.sp), cursorBrush = SolidColor(Tokens.Ink),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            val labels = mapOf(
                FontFilter.ALL to stringResource(UiR.string.studio_font_filter_all),
                FontFilter.DIALOGUE to roleNames.getValue(FontRole.DIALOGUE),
                FontFilter.SFX to roleNames.getValue(FontRole.SFX),
                FontFilter.CAPTION to roleNames.getValue(FontRole.CAPTION),
                FontFilter.FAVOURITES to stringResource(UiR.string.studio_font_filter_favourites),
                FontFilter.MINE to stringResource(UiR.string.studio_my_fonts),
            )
            FontFilter.entries.forEach { f -> FilterChip(labels.getValue(f), filter == f) { filter = f } }
            // "Fits Japanese" is a switch, not one of the filters: it narrows whichever filter is on.
            FilterChip(stringResource(UiR.string.studio_font_fits, tgt.uiName()), fitsOnly, outlined = true) { fitsOnly = !fitsOnly }
        }
        Text(
            pluralStringResource(UiR.plurals.studio_font_count, shown.size, shown.size), fontFamily = Jakarta, fontSize = 12.sp,
            color = Tokens.InkSoft, modifier = Modifier.padding(bottom = 4.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().height(FONT_ROW * visibleRows).verticalScrollbar(list), state = list) {
            if (query.isEmpty() && filter == FontFilter.ALL) item(key = "auto") {
                FontRow(
                    stringResource(UiR.string.studio_color_auto), StudioFonts.defaultFor(tgt, role), tgt, role, selected = fontId == null,
                    fits = true, starred = null, onStar = {}, onClick = { onFont(null); onBack() }, onLong = {},
                    note = StudioFonts.find(StudioFonts.defaultFor(tgt, role))?.let { fontName(it) },
                )
            }
            items(shown, key = { it.id }) { f ->
                FontRow(
                    names.getValue(f.id), f.id, tgt, role, selected = f.id == StudioFonts.find(fontId)?.id,
                    fits = StudioFonts.fits(f, tgt), starred = f.id in choices.favourites,
                    onStar = { choices.onFavourite(f.id) }, onClick = { onFont(f.id); onBack() }, onLong = { details = f },
                    note = if (StudioFonts.fits(f, tgt)) roleNames.getValue(f.role) else stringResource(UiR.string.studio_font_no_script, scriptName(StudioFonts.scriptOf(tgt))),
                )
            }
            if (shown.isEmpty()) item(key = "none") {
                Text(
                    stringResource(UiR.string.studio_font_no_match), color = Tokens.InkSoft, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).dashedBorder(Tokens.YellowDeep, 14.dp).clickable(onClick = choices.onManage)
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, null, tint = Tokens.Ink, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(UiR.string.studio_add_own_font), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 14.sp,
                    color = Tokens.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            TextAction(stringResource(UiR.string.studio_find_more_fonts), onClick = { findMore = true })
        }
    }
    details?.let { f -> FontDetails(f, onDismiss = { details = null }) }
    if (findMore) FindMoreFontsSheet(query, tgt, onDismiss = { findMore = false })
}

/** Phones open the font browser as a sheet with room for the list; wider screens show it in the tool panel. */
@Composable
fun fontBrowserInSheet(): Boolean = LocalConfiguration.current.screenWidthDp < 600

/** The font browser in a bottom sheet (phones): the page stays in view above it and shows each font as it is tapped. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontBrowserSheet(
    fontId: String?, tgt: Lang, role: FontRole, choices: FontChoices, onFont: (String?) -> Unit, onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Tokens.Card, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 20.dp)) {
            FontBrowser(fontId, tgt, role, choices, onFont, onBack = onDismiss)
        }
    }
}

/** One row of the font list: its name and a sample line in its own face. */
private val FONT_ROW = 54.dp

@Composable
private fun FilterChip(text: String, on: Boolean, outlined: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text, fontFamily = Jakarta, fontWeight = FontWeight.W600, fontSize = 13.sp, color = if (on) Tokens.Card else Tokens.Ink,
        modifier = Modifier.padding(end = 6.dp).clip(shape)
            .background(if (on) Tokens.Ink else if (outlined) Color.Transparent else Tokens.Ink.copy(alpha = 0.06f))
            .then(if (outlined && !on) Modifier.border(1.dp, Tokens.Border, shape) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** A dashed rounded outline, like the "add" rows elsewhere in the app. */
private fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    val r = radius.toPx()
    drawRoundRect(
        color, cornerRadius = CornerRadius(r, r),
        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FontRow(
    name: String, id: String, tgt: Lang, role: FontRole, selected: Boolean, fits: Boolean, starred: Boolean?,
    onStar: () -> Unit, onClick: () -> Unit, onLong: () -> Unit, note: String?,
) {
    val family = fontFamilyOf(id, tgt, role)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) Tokens.Yellow.copy(alpha = 0.35f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLong).padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontFamily = family, fontSize = 17.sp, color = Tokens.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                note?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, fontFamily = Jakarta, fontSize = 11.sp, color = if (fits) Tokens.InkFaint else Tokens.Error, maxLines = 1)
                }
            }
            Text(StudioFonts.sample(tgt), fontFamily = family, fontSize = 14.sp, color = Tokens.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (starred != null) IconButton(onClick = onStar, modifier = Modifier.size(40.dp)) {
            Icon(
                if (starred) Icons.Filled.Star else Icons.Outlined.StarOutline,
                stringResource(if (starred) UiR.string.studio_font_unfavourite else UiR.string.studio_font_favourite),
                tint = if (starred) Tokens.YellowDeep else Tokens.InkSoft,
            )
        }
    }
}

/** A font's scripts, role, licence and, for the user's own, that it lives only on this phone. */
@Composable
private fun FontDetails(f: FontEntry, onDismiss: () -> Unit) {
    val scriptNames = StudioFonts.SCRIPTS.associateWith { scriptName(it) }
    StickerDialog(onDismiss) {
        Text(fontName(f), fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 20.sp, color = Tokens.Ink)
        Spacer(Modifier.height(8.dp))
        val lines = listOfNotNull(
            stringResource(UiR.string.studio_font_role_line, roleName(f.role)),
            stringResource(UiR.string.studio_font_scripts_line, f.scripts.sortedBy { StudioFonts.SCRIPTS.indexOf(it) }.joinToString(", ") { scriptNames[it].orEmpty() }),
            f.licence.takeIf { it.isNotEmpty() }?.let { stringResource(UiR.string.studio_font_licence_line, it) },
            if (f.isUser) stringResource(UiR.string.studio_font_user_note) else null,
        )
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tokens.InkSoft, modifier = Modifier.padding(vertical = 2.dp)) }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
            TextAction(stringResource(UiR.string.studio_done), onClick = onDismiss)
        }
    }
}

/**
 * Free fonts worth adding, filtered by [query] and by [tgt]'s script: a tap opens the font's page in the browser,
 * where the user downloads it; they then add it with Add font. The app downloads nothing itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindMoreFontsSheet(query: String, tgt: Lang?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val roleNames = FontRole.entries.associateWith { roleName(it) }
    val scriptNames = StudioFonts.SCRIPTS.associateWith { scriptName(it) }
    val script = tgt?.let { StudioFonts.scriptOf(it) }
    var list = StudioFonts.suggestions.filter { script == null || script in it.scripts }
    list.filter { StudioFonts.matches(query, it.name, it.tags.joinToString(" "), roleNames.getValue(it.role), it.scripts.joinToString(" ") { s -> scriptNames[s].orEmpty() }) }
        .takeIf { it.isNotEmpty() }?.let { list = it }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Tokens.Card) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(UiR.string.studio_find_more_fonts), style = MaterialTheme.typography.titleLarge, color = Tokens.Ink)
            Text(stringResource(UiR.string.studio_find_more_note), style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft, modifier = Modifier.padding(vertical = 8.dp))
            list.forEach { s ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }.padding(vertical = 10.dp, horizontal = 4.dp),
                ) {
                    Text(s.name, fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 15.sp, color = Tokens.Ink)
                    Text(
                        roleNames.getValue(s.role) + " · " + s.scripts.sortedBy { StudioFonts.SCRIPTS.indexOf(it) }.joinToString(", ") { scriptNames[it].orEmpty() } + " · " + s.licence,
                        fontFamily = Jakarta, fontSize = 12.sp, color = Tokens.InkSoft,
                    )
                }
            }
            Text(stringResource(UiR.string.studio_find_more_commercial), style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft, modifier = Modifier.padding(top = 10.dp))
        }
    }
}
