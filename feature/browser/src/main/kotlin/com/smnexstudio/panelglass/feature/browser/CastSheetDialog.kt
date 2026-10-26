package com.smnexstudio.panelglass.feature.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smnexstudio.panelglass.core.model.CastSheet
import com.smnexstudio.panelglass.core.ui.Jakarta
import com.smnexstudio.panelglass.core.ui.PrimaryPill
import com.smnexstudio.panelglass.core.ui.StickerDialog
import com.smnexstudio.panelglass.core.ui.TextAction
import com.smnexstudio.panelglass.core.ui.Tokens
import com.smnexstudio.panelglass.core.ui.R as UiR

/**
 * The reader's character sheet for the website being read.
 *
 * It is the user's own text, one line per character, and it goes into the prompt as data: the model is told who is
 * in the series so that names, pronouns and tone stay the same from one screen to the next.
 */
@Composable
fun CastSheetDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    StickerDialog(onDismiss) {
        Text(
            stringResource(UiR.string.cast_title),
            fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 20.sp, color = Tokens.Ink,
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = draft, onValueChange = { draft = it.take(CastSheet.MAX) },
            placeholder = { Text(stringResource(UiR.string.cast_hint)) },
            minLines = 3, maxLines = 8,
            supportingText = {
                Text(
                    stringResource(UiR.string.cast_counter, draft.length, CastSheet.MAX),
                )
            },
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Tokens.Ink, unfocusedBorderColor = Tokens.Border, focusedTextColor = Tokens.Ink, unfocusedTextColor = Tokens.Ink,
                cursorColor = Tokens.Ink, focusedPlaceholderColor = Tokens.InkFaint, unfocusedPlaceholderColor = Tokens.InkFaint,
            ),
        )
        Text(
            stringResource(UiR.string.cast_note),
            style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextAction(stringResource(UiR.string.cast_clear), Tokens.InkSoft, onClear)
            Spacer(Modifier.width(8.dp))
            TextAction(stringResource(UiR.string.action_cancel), Tokens.InkSoft, onDismiss)
            Spacer(Modifier.width(8.dp))
            PrimaryPill(
                stringResource(UiR.string.action_save).uppercase(),
                letterSpaced = true, onClick = { onSave(draft) },
            )
        }
    }
}
