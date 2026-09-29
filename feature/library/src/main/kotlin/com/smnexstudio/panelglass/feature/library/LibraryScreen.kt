package com.smnexstudio.panelglass.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.smnexstudio.panelglass.core.ui.R as UiR
import com.smnexstudio.panelglass.core.ui.uiName
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Site
import com.smnexstudio.panelglass.core.model.WebUrl
import com.smnexstudio.panelglass.core.ui.CardDivider
import com.smnexstudio.panelglass.core.ui.ChoiceSheet
import com.smnexstudio.panelglass.core.ui.DashedRow
import com.smnexstudio.panelglass.core.ui.IconTile
import com.smnexstudio.panelglass.core.ui.Jakarta
import com.smnexstudio.panelglass.core.ui.PrimaryPill
import com.smnexstudio.panelglass.core.ui.SearchField
import com.smnexstudio.panelglass.core.ui.SectionLabel
import com.smnexstudio.panelglass.core.ui.StickerDialog
import com.smnexstudio.panelglass.core.ui.TextAction
import com.smnexstudio.panelglass.core.ui.ToggleCardRow
import com.smnexstudio.panelglass.core.ui.TokenCard
import com.smnexstudio.panelglass.core.ui.Tokens
import com.smnexstudio.panelglass.core.ui.ValueRow

import com.smnexstudio.panelglass.core.ui.LocalAppTheme
import com.smnexstudio.panelglass.core.ui.PanelCard
import com.smnexstudio.panelglass.core.ui.themeBackground
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpen: (url: String) -> Unit,
    /** A saved site, after its languages were confirmed: the reader keeps that site's settings. */
    onOpenSite: (Site) -> Unit,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val sites by vm.siteList.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val theme = LocalAppTheme.current
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var configuring by remember { mutableStateOf<Site?>(null) }
    var opening by remember { mutableStateOf<Site?>(null) }

    val filtered = if (query.isBlank()) sites else sites.filter { it.name.contains(query, true) || it.host.contains(query, true) }
    val pinned = filtered.filter { it.pinned }
    val rest = filtered.filter { !it.pinned }

    Column(Modifier.fillMaxSize().themeBackground(theme)) {
        when (theme) {
            com.smnexstudio.panelglass.core.model.AppTheme.PAPER_INK -> {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = painterResource(com.smnexstudio.panelglass.core.ui.R.drawable.ic_panelglass_logo),
                        contentDescription = stringResource(UiR.string.cd_logo),
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Panelglass",
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                            fontWeight = FontWeight.W800,
                            fontSize = 26.sp,
                            color = Tokens.Ink,
                        )
                        Text(
                            stringResource(UiR.string.library_subtitle),
                            fontFamily = Jakarta,
                            fontSize = 13.sp,
                            color = Tokens.InkFaint,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            else -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = painterResource(com.smnexstudio.panelglass.core.ui.R.drawable.ic_panelglass_logo),
                        contentDescription = stringResource(UiR.string.cd_logo),
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Panelglass",
                        fontFamily = Jakarta,
                        fontWeight = FontWeight.W800,
                        fontSize = 22.sp,
                        color = Tokens.Ink,
                    )
                }
            }
        }
        SearchField(
            value = query, onValueChange = { query = it }, placeholder = stringResource(UiR.string.library_search),
            onGo = { if (looksLikeUrl(query)) onOpen(query) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            if (pinned.isNotEmpty()) {
                item { SectionLabel(stringResource(UiR.string.library_pinned), pinned = true) }
                items(pinned, key = { "p" + it.id }) { s -> SiteCard(s, onOpen = { opening = s }, onLong = { configuring = s }, onPin = { vm.togglePin(s) }) }
            }
            item { SectionLabel(stringResource(UiR.string.library_all_sites)) }
            item { DashedRow(stringResource(UiR.string.library_add_site_row), onClick = { adding = true }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            items(rest, key = { "a" + it.id }) { s -> SiteCard(s, onOpen = { opening = s }, onLong = { configuring = s }, onPin = { vm.togglePin(s) }) }
            if (sites.isEmpty()) item {
                Text(
                    stringResource(UiR.string.library_empty),
                    style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    if (adding) AddSiteDialog(
        initialUrl = if (looksLikeUrl(query)) query else "",
        defaultSrc = settings.defaultSourceLang,
        defaultTgt = settings.defaultTargetLang,
        onDismiss = { adding = false },
    ) { n, u, src, tgt ->
        // Only a pair that differs from Settings is pinned to the site; otherwise it follows Settings.
        vm.add(n, u, src.takeIf { it != settings.defaultSourceLang }, tgt.takeIf { it != settings.defaultTargetLang })
        adding = false
    }

    opening?.let { site ->
        OpenSiteDialog(
            site = site,
            initialSrc = site.sourceLang ?: settings.defaultSourceLang,
            initialTgt = site.targetLang ?: settings.defaultTargetLang,
            onDismiss = { opening = null },
        ) { src, tgt ->
            opening = null
            vm.openWith(site, src, tgt) { onOpenSite(site) }
        }
    }

    configuring?.let { site ->
        ModalBottomSheet(onDismissRequest = { configuring = null }, containerColor = Tokens.Card) {
            SiteConfigSheet(
                site = site,
                defaultSrc = settings.defaultSourceLang,
                defaultTgt = settings.defaultTargetLang,
                onSave = { vm.update(it); configuring = null },
                onDelete = { vm.delete(site); configuring = null },
            )
        }
    }
}

internal fun looksLikeUrl(s: String) = WebUrl.https(s) != null

/** Theme-styled site row/card. */
@Composable
private fun SiteCard(site: Site, onOpen: () -> Unit, onLong: () -> Unit, onPin: () -> Unit) {
    val theme = LocalAppTheme.current
    PanelCard(onClick = onOpen, onLongClick = onLong) {
        IconTile(site.name, size = 44, radius = 12)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                site.name,
                fontFamily = if (theme == com.smnexstudio.panelglass.core.model.AppTheme.PAPER_INK) androidx.compose.ui.text.font.FontFamily.Serif else Jakarta,
                fontWeight = FontWeight.W700,
                fontSize = 15.sp,
                color = Tokens.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                site.host,
                fontFamily = Jakarta,
                fontWeight = FontWeight.W500,
                fontSize = 12.5.sp,
                color = Tokens.InkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (theme == com.smnexstudio.panelglass.core.model.AppTheme.PAPER_INK && site.pinned) {
            Box(
                Modifier
                    .padding(end = 4.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3A4562)),
            )
        }
        IconButton(onClick = onPin, modifier = Modifier.size(40.dp)) {
            Icon(
                if (site.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                contentDescription = stringResource(if (site.pinned) UiR.string.cd_unpin else UiR.string.cd_pin),
                modifier = Modifier.size(20.dp),
                tint = if (site.pinned) {
                    if (theme == com.smnexstudio.panelglass.core.model.AppTheme.PAPER_INK) Color(0xFF3A4562) else Tokens.Orange
                } else Tokens.InkFaint,
            )
        }
    }
}

/** Kept for History, which shows the host's initial next to each visit. */
@Composable
fun InitialsTile(name: String, size: Int = 36) = IconTile(name, size = size, radius = 10)

@Composable
private fun AddSiteDialog(
    initialUrl: String,
    defaultSrc: Lang,
    defaultTgt: Lang,
    onDismiss: () -> Unit,
    onAdd: (String, String, Lang, Lang) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(initialUrl) }
    var src by remember { mutableStateOf(defaultSrc) }
    var tgt by remember { mutableStateOf(defaultTgt) }
    var pickSrc by remember { mutableStateOf(false) }
    var pickTgt by remember { mutableStateOf(false) }

    StickerDialog(onDismiss) {
        Text(stringResource(UiR.string.site_add_title), fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 20.sp, color = Tokens.Ink)
        Spacer(Modifier.height(14.dp))
        TokenField(url, { url = it }, stringResource(UiR.string.site_url))
        Spacer(Modifier.height(8.dp))
        TokenField(name, { name = it }, stringResource(UiR.string.site_name_optional))
        Spacer(Modifier.height(10.dp))
        SectionLabel(stringResource(UiR.string.section_translation), Modifier.padding(bottom = 4.dp), inset = 0.dp)
        TokenCard {
            ValueRow(stringResource(UiR.string.lang_translate_from), src.uiName(), onClick = { pickSrc = true })
            CardDivider()
            ValueRow(stringResource(UiR.string.lang_translate_to), tgt.uiName(), onClick = { pickTgt = true })
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextAction(stringResource(UiR.string.action_cancel), Tokens.InkSoft, onDismiss)
            Spacer(Modifier.width(8.dp))
            PrimaryPill(stringResource(UiR.string.action_add).uppercase(), enabled = looksLikeUrl(url), letterSpaced = true, onClick = { onAdd(name, url, src, tgt) })
        }
    }

    if (pickSrc) ChoiceSheet(stringResource(UiR.string.lang_source), Lang.sourceChoices, src, { it.uiName() }, onPick = { src = it }, onDismiss = { pickSrc = false })
    if (pickTgt) ChoiceSheet(stringResource(UiR.string.lang_target), Lang.targetChoices, tgt, { it.uiName() }, onPick = { tgt = it }, onDismiss = { pickTgt = false })
}

/** Asked on every site tap: the languages to read in, preset to the site's saved pair or the Settings defaults. */
@Composable
private fun OpenSiteDialog(
    site: Site,
    initialSrc: Lang,
    initialTgt: Lang,
    onDismiss: () -> Unit,
    onRead: (Lang, Lang) -> Unit,
) {
    var src by remember(site) { mutableStateOf(initialSrc) }
    var tgt by remember(site) { mutableStateOf(initialTgt) }
    var pickSrc by remember { mutableStateOf(false) }
    var pickTgt by remember { mutableStateOf(false) }

    StickerDialog(onDismiss) {
        Text(site.name, fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 20.sp, color = Tokens.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(site.host, fontFamily = Jakarta, fontSize = 12.5.sp, color = Tokens.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        SectionLabel(stringResource(UiR.string.site_read_in), Modifier.padding(bottom = 4.dp), inset = 0.dp)
        TokenCard {
            ValueRow(stringResource(UiR.string.lang_translate_from), src.uiName(), onClick = { pickSrc = true })
            CardDivider()
            ValueRow(stringResource(UiR.string.lang_translate_to), tgt.uiName(), onClick = { pickTgt = true })
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextAction(stringResource(UiR.string.action_cancel), Tokens.InkSoft, onDismiss)
            Spacer(Modifier.width(8.dp))
            PrimaryPill(stringResource(UiR.string.site_read).uppercase(), letterSpaced = true, onClick = { onRead(src, tgt) })
        }
    }

    if (pickSrc) ChoiceSheet(stringResource(UiR.string.lang_source), Lang.sourceChoices, src, { it.uiName() }, onPick = { src = it }, onDismiss = { pickSrc = false })
    if (pickTgt) ChoiceSheet(stringResource(UiR.string.lang_target), Lang.targetChoices, tgt, { it.uiName() }, onPick = { tgt = it }, onDismiss = { pickTgt = false })
}

@Composable
private fun TokenField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Tokens.Ink, unfocusedBorderColor = Tokens.Border, focusedTextColor = Tokens.Ink, unfocusedTextColor = Tokens.Ink,
            cursorColor = Tokens.Ink, focusedLabelColor = Tokens.InkSoft, unfocusedLabelColor = Tokens.InkFaint,
        ),
    )
}

/** Per-site settings: name, url, translation languages, ad-block. */
@Composable
fun SiteConfigSheet(
    site: Site,
    defaultSrc: Lang,
    defaultTgt: Lang,
    onSave: (Site) -> Unit,
    onDelete: () -> Unit,
) {
    var draft by remember(site) { mutableStateOf(site) }
    var name by remember(site) { mutableStateOf(site.name) }
    var url by remember(site) { mutableStateOf(site.url) }
    var pickSrc by remember { mutableStateOf(false) }
    var pickTgt by remember { mutableStateOf(false) }

    val srcDisplay = draft.sourceLang?.uiName() ?: stringResource(UiR.string.lang_default, defaultSrc.uiName())
    val tgtDisplay = draft.targetLang?.uiName() ?: stringResource(UiR.string.lang_default, defaultTgt.uiName())

    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Text(site.name, style = MaterialTheme.typography.titleLarge, color = Tokens.Ink)
        Text(site.host, style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft)
        Spacer(Modifier.height(12.dp))
        TokenField(name, { name = it }, stringResource(UiR.string.site_name))
        Spacer(Modifier.height(8.dp))
        TokenField(url, { url = it }, stringResource(UiR.string.site_url))
        SectionLabel(stringResource(UiR.string.section_translation), inset = 0.dp)
        TokenCard {
            ValueRow(stringResource(UiR.string.lang_translate_from), srcDisplay, onClick = { pickSrc = true })
            CardDivider()
            ValueRow(stringResource(UiR.string.lang_translate_to), tgtDisplay, onClick = { pickTgt = true })
        }
        SectionLabel(stringResource(UiR.string.section_browsing), inset = 0.dp)
        TokenCard {
            ToggleCardRow(stringResource(UiR.string.site_pin_to_top), draft.pinned) { draft = draft.copy(pinned = it) }
            CardDivider()
            ToggleCardRow(stringResource(UiR.string.block_ads), draft.adBlockEnabled) { draft = draft.copy(adBlockEnabled = it) }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextAction(stringResource(UiR.string.action_delete), Tokens.Error, onDelete)
            Spacer(Modifier.width(8.dp))
            PrimaryPill(stringResource(UiR.string.action_save).uppercase(), letterSpaced = true, onClick = { onSave(draft.copy(name = name.trim().ifEmpty { site.name }, url = WebUrl.https(url) ?: site.url)) })
        }
    }

    if (pickSrc) ChoiceSheet(stringResource(UiR.string.lang_source), Lang.sourceChoices, draft.sourceLang ?: defaultSrc, { it.uiName() }, onPick = { draft = draft.copy(sourceLang = it) }, onDismiss = { pickSrc = false })
    if (pickTgt) ChoiceSheet(stringResource(UiR.string.lang_target), Lang.targetChoices, draft.targetLang ?: defaultTgt, { it.uiName() }, onPick = { draft = draft.copy(targetLang = it) }, onDismiss = { pickTgt = false })
}
