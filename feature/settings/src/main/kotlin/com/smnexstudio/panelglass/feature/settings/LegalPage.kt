package com.smnexstudio.panelglass.feature.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smnexstudio.panelglass.core.ui.CardDivider
import com.smnexstudio.panelglass.core.ui.Jakarta
import com.smnexstudio.panelglass.core.ui.TextAction
import com.smnexstudio.panelglass.core.ui.TokenCard
import com.smnexstudio.panelglass.core.ui.Tokens
import com.smnexstudio.panelglass.core.ui.R as UiR

/** The documents About opens inside the app: the repository's own files, copied into the APK at build time. */
internal enum class LegalDoc(val asset: String, val file: String) {
    PRIVACY("legal/PRIVACY.md", "PRIVACY.md"),
    LICENCES("legal/LICENSES.md", "LICENSES.md"),
}

/** The bundled text of [doc], or null when the build left it out (the page then offers GitHub only). */
internal fun readLegalDoc(context: Context, doc: LegalDoc): String? =
    runCatching { context.assets.open(doc.asset).bufferedReader().use { it.readText() } }.getOrNull()

/**
 * A bundled document as a Settings page: the version that shipped with this app, readable offline, and a link to the
 * same file on GitHub. Its own first heading is the page title, so it is not repeated.
 */
@Composable
internal fun LegalPage(title: String, doc: LegalDoc, onBack: (() -> Unit)?, open: (String) -> Unit) {
    val context = LocalContext.current
    val blocks = remember(doc) {
        readLegalDoc(context, doc)?.let { MarkdownLite.parse(it, SettingsLinks.blobBase) }?.let { b ->
            if ((b.firstOrNull() as? MarkdownLite.Block.Heading)?.level == 1) b.drop(1) else b
        }
    }
    val english = LocalConfiguration.current.locales[0].language == "en"
    SettingsPageFrame(title, onBack) {
        if (!english) PageNote(stringResource(UiR.string.set_doc_in_english))
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            blocks?.forEach { MdBlockView(it) }
        }
        Row(Modifier.padding(start = 10.dp, top = 4.dp)) {
            TextAction(stringResource(UiR.string.set_view_on_github)) { open(SettingsLinks.blob(doc.file)) }
        }
    }
}

@Composable
private fun MdBlockView(block: MarkdownLite.Block) {
    when (block) {
        is MarkdownLite.Block.Heading -> Text(
            annotated(block.spans), fontFamily = Jakarta, fontWeight = FontWeight.W800, color = Tokens.Ink,
            fontSize = if (block.level <= 2) 18.sp else 16.sp,
            modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
        )
        is MarkdownLite.Block.Paragraph -> Text(
            annotated(block.spans), style = MaterialTheme.typography.bodyMedium, color = Tokens.Ink,
            modifier = Modifier.padding(vertical = 5.dp),
        )
        is MarkdownLite.Block.Bullet -> Row(Modifier.padding(vertical = 3.dp)) {
            Text("•", style = MaterialTheme.typography.bodyMedium, color = Tokens.InkSoft, modifier = Modifier.width(16.dp))
            Text(annotated(block.spans), style = MaterialTheme.typography.bodyMedium, color = Tokens.Ink, modifier = Modifier.weight(1f))
        }
        // A phone is too narrow for columns: each row is a card entry, its first cell the title and the others
        // labelled with their column's header.
        is MarkdownLite.Block.Table -> TokenCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            block.rows.forEachIndexed { r, row ->
                if (r > 0) CardDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    row.forEachIndexed { c, cell ->
                        if (c == 0) {
                            Text(annotated(cell), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.W700, color = Tokens.Ink)
                        } else {
                            val label = block.header.getOrNull(c)?.joinToString("") { it.text }.orEmpty()
                            Text(label, style = MaterialTheme.typography.labelMedium, color = Tokens.InkSoft, modifier = Modifier.padding(top = 6.dp))
                            Text(annotated(cell), style = MaterialTheme.typography.bodyMedium, color = Tokens.Ink)
                        }
                    }
                }
            }
        }
    }
}

/** Spans as styled text; links open through the platform's URI handler (the browser). */
@Composable
private fun annotated(spans: List<MarkdownLite.Span>): AnnotatedString {
    val link = TextLinkStyles(SpanStyle(color = Tokens.SkyDeep, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        for (s in spans) {
            val style = SpanStyle(
                fontWeight = if (s.bold) FontWeight.W700 else null,
                fontFamily = if (s.code) FontFamily.Monospace else null,
            )
            if (s.url != null) withLink(LinkAnnotation.Url(s.url, link)) { withStyle(style) { append(s.text) } }
            else withStyle(style) { append(s.text) }
        }
    }
}
