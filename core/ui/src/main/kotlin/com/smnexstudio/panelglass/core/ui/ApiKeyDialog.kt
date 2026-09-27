package com.smnexstudio.panelglass.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.smnexstudio.panelglass.core.model.EngineId

/**
 * White card with a 2.5 dp Ink border and 24 dp radius, sitting on an 8 dp-offset Yellow "sticker" shadow.
 * Content is the caller's; [ApiKeyDialog] is the one use today.
 */
@Composable
fun StickerDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Box(Modifier.padding(end = 8.dp, bottom = 8.dp)) {
            val shape = RoundedCornerShape(24.dp)
            Box(Modifier.matchParentSize().offset(x = 8.dp, y = 8.dp).clip(shape).background(Tokens.Yellow))
            Column(
                Modifier.fillMaxWidth().clip(shape).background(Tokens.Card).border(2.5.dp, Tokens.Ink, shape).padding(22.dp),
            ) { content() }
        }
    }
}

/**
 * "<Provider> Key": the key (masked), and for providers that take one, the model id (a deliberate addition to
 * the mock — OpenRouter is unusable without it). Save hands back `null` for the key when the saved one is kept.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeyDialog(
    engine: EngineId,
    hasKey: Boolean,
    currentModel: String,
    onSave: (key: String?, model: String?) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
    /** (id, display name) pairs for the model dropdown; empty leaves the field as plain text. */
    modelChoices: List<Pair<String, String>> = emptyList(),
    modelsLoading: Boolean = false,
    modelsError: String? = null,
    /** Asks for the provider's model list, with the key typed so far (null = the saved key). Null: no listing. */
    onLoadModels: ((typedKey: String?) -> Unit)? = null,
) {
    var key by remember(engine) { mutableStateOf("") }
    var model by remember(engine) { mutableStateOf(currentModel) }
    var menuOpen by remember(engine) { mutableStateOf(false) }
    LaunchedEffect(engine) { if (hasKey) onLoadModels?.invoke(null) }
    val canSave = (key.isNotBlank() || hasKey) && (!engine.hasModel || model.isNotBlank())
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Tokens.Ink, unfocusedBorderColor = Tokens.Border,
        focusedTextColor = Tokens.Ink, unfocusedTextColor = Tokens.Ink, cursorColor = Tokens.Ink,
        focusedPlaceholderColor = Tokens.InkFaint, unfocusedPlaceholderColor = Tokens.InkFaint,
    )
    StickerDialog(onDismiss) {
        Text(
            stringResource(R.string.key_title, engine.uiName()), fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 20.sp, color = Tokens.Ink,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = key, onValueChange = { key = it },
            placeholder = { Text(stringResource(if (hasKey) R.string.key_hint_saved else R.string.key_hint)) },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            shape = RoundedCornerShape(14.dp), colors = fieldColors, modifier = Modifier.fillMaxWidth(),
        )
        if (engine.hasModel) {
            Spacer(Modifier.height(10.dp))
            // Editable dropdown: pick from the key's own model list, or type any id. Typing filters the list.
            val shown = remember(modelChoices, model) {
                val q = model.trim().lowercase()
                if (q.isEmpty() || modelChoices.any { it.first == model.trim() }) modelChoices
                else modelChoices.filter { (id, name) -> q in id.lowercase() || q in name.lowercase() }
            }
            ExposedDropdownMenuBox(expanded = menuOpen && shown.isNotEmpty(), onExpandedChange = { menuOpen = it }) {
                OutlinedTextField(
                    // Typing is free text and never opens the list (it would cover SAVE); a tap on the field or its arrow does.
                    value = model, onValueChange = { model = it },
                    placeholder = { Text(engine.defaultModel.orEmpty().ifEmpty { "provider/model-name" }) },
                    label = { Text(stringResource(R.string.key_model)) },
                    singleLine = true,
                    trailingIcon = if (modelChoices.isNotEmpty()) ({ ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    shape = RoundedCornerShape(14.dp), colors = fieldColors,
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
                )
                ExposedDropdownMenu(
                    expanded = menuOpen && shown.isNotEmpty(), onDismissRequest = { menuOpen = false },
                    containerColor = Tokens.Card, modifier = Modifier.heightIn(max = 280.dp),
                ) {
                    for ((id, name) in shown) DropdownMenuItem(
                        text = {
                            Column {
                                Text(id, fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 14.sp, color = Tokens.Ink)
                                if (name != id) Text(name, fontFamily = Jakarta, fontSize = 11.5.sp, color = Tokens.InkFaint)
                            }
                        },
                        onClick = { model = id; menuOpen = false },
                    )
                }
            }
            if (onLoadModels != null) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            modelsLoading -> stringResource(R.string.key_models_loading)
                            modelsError != null -> modelsError
                            modelChoices.isNotEmpty() -> pluralStringResource(R.plurals.key_models_count, modelChoices.size, modelChoices.size)
                            else -> stringResource(R.string.key_models_hint)
                        },
                        fontFamily = Jakarta, fontSize = 11.5.sp, lineHeight = 15.sp,
                        color = if (modelsError != null) Tokens.Error else Tokens.InkFaint, modifier = Modifier.weight(1f),
                    )
                    TextAction(stringResource(R.string.key_refresh), Tokens.SkyDeep) { onLoadModels(key.trim().ifEmpty { null }) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.key_note, engine.uiName()),
            fontFamily = Jakarta, fontWeight = FontWeight.W500, fontSize = 11.5.sp, lineHeight = 16.sp, color = Tokens.InkFaint,
        )
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            if (hasKey && onRemove != null) { TextAction(stringResource(R.string.key_remove), Tokens.Error) { onRemove(); onDismiss() }; Spacer(Modifier.width(4.dp)) }
            TextAction(stringResource(R.string.action_cancel), Tokens.InkSoft, onDismiss)
            Spacer(Modifier.width(8.dp))
            PrimaryPill(stringResource(R.string.action_save).uppercase(), enabled = canSave, letterSpaced = true, onClick = {
                onSave(key.trim().ifEmpty { null }, if (engine.hasModel) model.trim() else null); onDismiss()
            })
        }
    }
}
