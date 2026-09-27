package com.smnexstudio.panelglass.feature.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.smnexstudio.panelglass.core.ui.R as UiR
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.font.FontWeight
import com.smnexstudio.panelglass.core.ui.Jakarta
import com.smnexstudio.panelglass.core.ui.LocalAppTheme
import com.smnexstudio.panelglass.core.ui.PanelCard
import com.smnexstudio.panelglass.core.ui.TextAction
import com.smnexstudio.panelglass.core.ui.Tokens
import com.smnexstudio.panelglass.core.ui.themeBackground
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(onOpen: (String) -> Unit, vm: LibraryViewModel = hiltViewModel()) {
    val recent by vm.recent.collectAsStateWithLifecycle()
    val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    val theme = LocalAppTheme.current
    Column(Modifier.fillMaxSize().themeBackground(theme)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(UiR.string.history_title), style = MaterialTheme.typography.titleLarge, color = Tokens.Ink, modifier = Modifier.weight(1f))
            if (recent.isNotEmpty()) TextAction(stringResource(UiR.string.action_clear), Tokens.Error) { vm.clearHistory() }
        }
        if (recent.isEmpty()) {
            Text(stringResource(UiR.string.history_empty), style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(recent, key = { it.url }) { h ->
                PanelCard(onClick = { onOpen(h.url) }) {
                    InitialsTile(h.host, 36)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.title, fontFamily = if (theme == com.smnexstudio.panelglass.core.model.AppTheme.PAPER_INK) androidx.compose.ui.text.font.FontFamily.Serif else Jakarta, fontWeight = FontWeight.W600, fontSize = 15.sp, color = Tokens.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(h.host + " · " + fmt.format(Date(h.visitedAt)), fontFamily = Jakarta, fontWeight = FontWeight.W500, fontSize = 12.5.sp, color = Tokens.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { vm.deleteHistory(h) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Close, contentDescription = stringResource(UiR.string.cd_remove), tint = Tokens.InkFaint, modifier = Modifier.size(16.dp)) }
                }
            }
        }
    }
}
