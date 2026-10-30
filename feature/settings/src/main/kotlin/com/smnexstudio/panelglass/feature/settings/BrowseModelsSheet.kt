package com.smnexstudio.panelglass.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smnexstudio.panelglass.core.engine.local.HfCatalog
import com.smnexstudio.panelglass.core.model.UserModel
import com.smnexstudio.panelglass.core.ui.StickerDialog
import com.smnexstudio.panelglass.core.ui.TextAction
import com.smnexstudio.panelglass.core.ui.Tokens
import com.smnexstudio.panelglass.core.ui.Jakarta
import com.smnexstudio.panelglass.core.ui.PrimaryPill
import com.smnexstudio.panelglass.core.ui.R as UiR
import androidx.compose.ui.res.stringResource

/**
 * "Browse models": the Hugging Face catalogue of LiteRT-LM files. The search box searches the `litert-community`
 * author; typing `owner/name` lists that repository's files instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseModelsSheet(
    state: BrowseState,
    onSearch: (String) -> Unit,
    onBack: () -> Unit,
    onPickFile: (HfCatalog.HfListing, HfCatalog.HfFile) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Tokens.Ink, unfocusedBorderColor = Tokens.Border,
        focusedTextColor = Tokens.Ink, unfocusedTextColor = Tokens.Ink, cursorColor = Tokens.Ink,
        focusedPlaceholderColor = Tokens.InkFaint, unfocusedPlaceholderColor = Tokens.InkFaint,
    )
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Tokens.Card) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 28.dp),
        ) {
            Text(
                stringResource(UiR.string.models_browse), style = MaterialTheme.typography.titleLarge,
                color = Tokens.Ink, modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text(stringResource(UiR.string.browse_search_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    shape = RoundedCornerShape(14.dp), colors = fieldColors,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                PrimaryPill(stringResource(UiR.string.models_browse), onClick = { onSearch(query) }, enabled = !state.loading)
            }
            Spacer(Modifier.height(12.dp))

            when {
                state.loading -> Text(
                    stringResource(UiR.string.key_models_loading), fontFamily = Jakarta, fontSize = 13.sp, color = Tokens.InkSoft,
                    modifier = Modifier.padding(start = 4.dp),
                )
                state.error != null -> Text(
                    state.error, fontFamily = Jakarta, fontSize = 13.sp, color = Tokens.Error, modifier = Modifier.padding(start = 4.dp),
                )
                state.listing != null -> {
                    Text(
                        state.listing.repoId, style = MaterialTheme.typography.bodyLarge, color = Tokens.Ink,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                    )
                    TextAction(stringResource(UiR.string.action_back), Tokens.SkyDeep) { onBack() }
                    Spacer(Modifier.height(6.dp))
                    val files = state.listing.files
                    if (files.isEmpty()) {
                        Text(
                            stringResource(UiR.string.browse_only_litertlm), fontFamily = Jakarta, fontSize = 13.sp,
                            color = Tokens.InkSoft, modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    for (f in files) {
                        BrowseRow(
                            title = f.path.substringAfter('/'),
                            subtitle = "${f.bytes / 1_048_576} MB",
                            onClick = { onPickFile(state.listing, f) },
                        )
                    }
                }
                state.repos.isEmpty() -> Text(
                    stringResource(UiR.string.browse_no_results), fontFamily = Jakarta, fontSize = 13.sp,
                    color = Tokens.InkSoft, modifier = Modifier.padding(start = 4.dp),
                )
                else -> for (r in state.repos) {
                    BrowseRow(
                        title = r.id,
                        subtitle = if (r.gated) stringResource(UiR.string.browse_gated) else "${r.downloads} downloads",
                        onClick = { onSearch(r.id) },
                    )
                }
            }
        }
    }
}

/** One tappable line in the browse sheet: a name and a second line under it. */
@Composable
private fun BrowseRow(title: String, subtitle: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .border(1.dp, Tokens.Border, shape)
            .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(title, fontFamily = Jakarta, fontWeight = FontWeight.W600, fontSize = 13.sp, color = Tokens.Ink)
        Text(subtitle, fontFamily = Jakarta, fontSize = 11.5.sp, color = Tokens.InkSoft, modifier = Modifier.padding(top = 2.dp))
    }
    Spacer(Modifier.height(6.dp))
}

/**
 * "Add model": the name the user wants to see, the context budget, and the size. The context budget cannot exceed
 * the KV cache the file was converted with, so it is prefilled from the file name and may only be lowered.
 */
@Composable
fun AddModelDialog(
    title: String,
    sizeBytes: Long,
    suggestedTokens: Int,
    ramWarning: Boolean,
    actionLabel: String,
    onConfirm: (label: String, tokens: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(title) }
    var tokens by remember { mutableStateOf(suggestedTokens.toString()) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Tokens.Ink, unfocusedBorderColor = Tokens.Border,
        focusedTextColor = Tokens.Ink, unfocusedTextColor = Tokens.Ink, cursorColor = Tokens.Ink,
        focusedPlaceholderColor = Tokens.InkFaint, unfocusedPlaceholderColor = Tokens.InkFaint,
    )
    StickerDialog(onDismiss) {
        Text(
            stringResource(UiR.string.add_model_title), fontFamily = Jakarta, fontWeight = FontWeight.W800,
            fontSize = 20.sp, color = Tokens.Ink, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = label, onValueChange = { label = it },
            label = { Text(stringResource(UiR.string.add_model_label)) },
            singleLine = true, shape = RoundedCornerShape(14.dp), colors = fieldColors, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = tokens, onValueChange = { tokens = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text(stringResource(UiR.string.add_model_context)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(14.dp), colors = fieldColors, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "${sizeBytes / 1_048_576} MB",
            fontFamily = Jakarta, fontWeight = FontWeight.W500, fontSize = 11.5.sp, color = Tokens.InkFaint,
        )
        if (ramWarning) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(UiR.string.browse_ram_warning), fontFamily = Jakarta, fontWeight = FontWeight.W500,
                fontSize = 11.5.sp, lineHeight = 16.sp, color = Tokens.Error,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(UiR.string.add_model_experimental), fontFamily = Jakarta, fontWeight = FontWeight.W500,
            fontSize = 11.5.sp, lineHeight = 16.sp, color = Tokens.InkFaint,
        )
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            TextAction(stringResource(UiR.string.action_cancel), Tokens.InkSoft, onDismiss)
            Spacer(Modifier.width(8.dp))
            PrimaryPill(actionLabel.uppercase(), enabled = label.isNotBlank(), letterSpaced = true, onClick = {
                onConfirm(label.trim(), UserModel.clampTokens(tokens.toIntOrNull() ?: suggestedTokens))
                onDismiss()
            })
        }
    }
}
