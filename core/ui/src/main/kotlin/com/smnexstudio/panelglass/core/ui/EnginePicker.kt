package com.smnexstudio.panelglass.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smnexstudio.panelglass.core.model.EngineId

/** What the picker needs to know about a keyed provider. */
class KeyedEngineState(val hasKey: Boolean, val model: String)

/**
 * "Select engine" sheet. Built-in engines first (Qwen, Google Translate), then one row per keyed provider
 * under "Custom keys (no quota usage)". Each row has two targets: the check disc selects (a keyed provider
 * without a key goes to [onNeedsKey] first, and the selection completes when the key is saved), while the
 * name of a keyed provider opens its key + model dialog through [onConfigure] without changing the selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnginePickerSheet(
    selected: EngineId,
    keyed: (EngineId) -> KeyedEngineState,
    onSelect: (EngineId) -> Unit,
    onNeedsKey: (EngineId) -> Unit,
    onDismiss: () -> Unit,
    onConfigure: (EngineId) -> Unit = onNeedsKey,
    /** Engines this device cannot run (an on-device model above its RAM); never listed. */
    hidden: Set<EngineId> = emptySet(),
    /** On-device engines whose model is downloaded and verified. */
    ready: Set<EngineId> = emptySet(),
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Tokens.Card) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Text(stringResource(R.string.picker_title), style = MaterialTheme.typography.titleLarge, color = Tokens.Ink, modifier = Modifier.padding(start = 4.dp))
            Text(stringResource(R.string.picker_subtitle), style = MaterialTheme.typography.bodyMedium, color = Tokens.InkSoft, modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 12.dp))

            for (e in EngineId.entries.filter { it.offered && !it.needsKey && it !in hidden }) {
                EngineRow(
                    engine = e, selected = e == selected,
                    subtitle = when {
                        e.isLocalLlm && e in ready -> stringResource(R.string.picker_local_ready)
                        e.isLocalLlm -> stringResource(R.string.picker_local)
                        else -> stringResource(R.string.picker_google)
                    },
                    onBody = null,
                    onCheck = { onSelect(e); onDismiss() },
                )
                Spacer(Modifier.height(8.dp))
            }

            SheetSectionLabel(stringResource(R.string.picker_custom_keys))
            for (e in EngineId.entries.filter { it.offered && it.needsKey }) {
                val k = keyed(e)
                EngineRow(
                    engine = e, selected = e == selected,
                    subtitle = if (!k.hasKey) stringResource(R.string.picker_set_key) else stringResource(R.string.picker_key_saved, k.model.ifBlank { stringResource(R.string.picker_model_not_set) }),
                    onBody = { onConfigure(e) },
                    onCheck = { if (k.hasKey) { onSelect(e); onDismiss() } else onNeedsKey(e) },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** [onBody] is the tile + name + subtitle (null: inert); [onCheck] is the disc at the end, with a 44 dp target. */
@Composable
private fun EngineRow(engine: EngineId, selected: Boolean, subtitle: String, onBody: (() -> Unit)?, onCheck: () -> Unit) {
    val shape = RoundedCornerShape(Radii.row)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) Tokens.YellowTint else Tokens.Card)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Tokens.Yellow else Tokens.Border, shape)
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                .then(if (onBody != null) Modifier.clickable(onClick = onBody) else Modifier)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(engine.shortName, size = 36, radius = 10, tint = engine.tileTint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(engine.uiName(), fontFamily = Jakarta, fontWeight = FontWeight.W600, fontSize = 14.sp, color = Tokens.Ink)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft)
            }
        }
        val checkLabel = if (selected) stringResource(R.string.cd_selected) else stringResource(R.string.cd_select_engine, engine.uiName())
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onCheck)
                .semantics { role = Role.RadioButton; contentDescription = checkLabel },
            contentAlignment = Alignment.Center,
        ) { CheckDisc(selected) }
    }
}

/** A single letter (or two) for the tile. */
val EngineId.shortName: String
    get() = when (this) {
        EngineId.GOOGLE -> "G"; EngineId.GEMINI -> "Ge"; EngineId.CLAUDE -> "C"
        EngineId.OPENAI -> "O"; EngineId.OPENROUTER -> "R"; EngineId.DEEPL -> "D"; EngineId.PAPAGO -> "P"; EngineId.DEEPSEEK -> "S"
        EngineId.QWEN15_LOCAL -> "Q"; EngineId.GEMMA4_LOCAL -> "G4"
    }

/** Provider-tinted tile backgrounds. */
val EngineId.tileTint: Color
    @Composable
    get() = when (this) {
        EngineId.QWEN15_LOCAL -> Tokens.YellowTint
        EngineId.GEMMA4_LOCAL -> Color(0xFFE6F4EA)
        EngineId.GOOGLE -> Color(0xFFE2ECFA)
        EngineId.GEMINI -> Color(0xFFDDE8FF)
        EngineId.CLAUDE -> Color(0xFFF6E3D3)
        EngineId.OPENAI -> Color(0xFFDDF3E1)
        EngineId.OPENROUTER -> Color(0xFFECE3FA)
        EngineId.DEEPL -> Color(0xFFD5EEF7)
        EngineId.PAPAGO -> Color(0xFFDFF5DC)
        EngineId.DEEPSEEK -> Color(0xFFE4E9FF)
    }
