package com.smnexstudio.panelglass.feature.studio.cover

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smnexstudio.panelglass.core.model.Chapter
import com.smnexstudio.panelglass.core.model.StudioPage
import com.smnexstudio.panelglass.core.ui.Jakarta
import com.smnexstudio.panelglass.core.ui.PrimaryPill
import com.smnexstudio.panelglass.core.ui.SectionLabel
import com.smnexstudio.panelglass.core.ui.TextAction
import com.smnexstudio.panelglass.core.ui.Tokens
import com.smnexstudio.panelglass.feature.studio.PageThumb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import com.smnexstudio.panelglass.core.ui.R as UiR

/**
 * The manga's cover: a preview to drag (it is cropped to 2 : 3), a picture from the gallery or files, or any page of
 * a chapter; "Use the first page automatically" removes the custom cover.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CoverSheet(
    covers: Covers,
    current: CoverSource?,
    chapters: List<Pair<Chapter, List<StudioPage>>>,
    pageFile: (String) -> File,
    onSave: (CoverSource, Float, Float) -> Unit,
    onAuto: () -> Unit,
    onDismiss: () -> Unit,
) {
    var source by remember { mutableStateOf(current) }
    var biasX by remember { mutableFloatStateOf(0f) }
    var biasY by remember { mutableFloatStateOf(if (current is CoverSource.Page) -1f else 0f) }
    var chapterIndex by remember { mutableStateOf(0) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { source = CoverSource.Picked(uri); biasX = 0f; biasY = 0f }
    }
    val density = LocalDensity.current
    val previewPx = with(density) { 300.dp.roundToPx() }
    val bmp by produceState<Bitmap?>(null, source) { value = source?.let { covers.preview(it, previewPx) } }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Tokens.Card, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(UiR.string.studio_cover_title), style = MaterialTheme.typography.titleLarge, color = Tokens.Ink, modifier = Modifier.weight(1f))
                TextAction(stringResource(UiR.string.action_cancel), Tokens.InkSoft, onDismiss)
            }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CropPreview(bmp, biasX, biasY, onBias = { x, y -> biasX = x; biasY = y }, modifier = Modifier.size(width = 104.dp, height = 156.dp))
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(if (source is CoverSource.Picked) UiR.string.studio_cover_your_image else UiR.string.studio_cover_a_page),
                        fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 15.sp, color = Tokens.Ink,
                    )
                    Text(stringResource(UiR.string.studio_cover_hint), fontFamily = Jakarta, fontSize = 12.sp, color = Tokens.InkSoft)
                }
            }
            Row(
                Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, Tokens.Border, RoundedCornerShape(16.dp))
                    .clickable { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Tokens.YellowTint), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Image, null, tint = Tokens.Ink, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(UiR.string.studio_cover_choose_image), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 15.sp, color = Tokens.Ink)
                    Text(stringResource(UiR.string.studio_cover_choose_image_note), fontFamily = Jakarta, fontSize = 12.sp, color = Tokens.InkSoft)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Tokens.InkFaint)
            }
            if (chapters.isNotEmpty()) {
                SectionLabel(stringResource(UiR.string.studio_cover_use_page), inset = 0.dp)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    chapters.forEachIndexed { i, (c, _) ->
                        val on = i == chapterIndex
                        Text(
                            c.numberLabel?.let { stringResource(UiR.string.studio_chapter_short, it) } ?: "#${i + 1}",
                            fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 12.sp, color = if (on) Tokens.Card else Tokens.Ink,
                            modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(50)).background(if (on) Tokens.Ink else Tokens.Ink.copy(alpha = 0.06f))
                                .clickable { chapterIndex = i }.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                val pages = chapters.getOrNull(chapterIndex)?.second.orEmpty()
                LazyVerticalGrid(
                    GridCells.Fixed(4), modifier = Modifier.fillMaxWidth().height(220.dp).padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(pages, key = { it.id }) { p ->
                        val f = pageFile(p.file)
                        val on = (source as? CoverSource.Page)?.file == f
                        Box(
                            Modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))
                                .border(if (on) 3.dp else 1.dp, if (on) Tokens.Yellow else Tokens.Border, RoundedCornerShape(8.dp))
                                .clickable { source = CoverSource.Page(f); biasX = 0f; biasY = -1f },
                        ) {
                            PageThumb(f, Modifier.matchParentSize(), targetDp = 80)
                            if (on) Box(
                                Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).clip(CircleShape).background(Tokens.Yellow),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.Check, null, tint = Tokens.Ink, modifier = Modifier.size(12.dp)) }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { TextAction(stringResource(UiR.string.studio_cover_auto), onClick = onAuto) }
                PrimaryPill(stringResource(UiR.string.action_save).uppercase(), letterSpaced = true, enabled = source != null && bmp != null, onClick = { source?.let { onSave(it, biasX, biasY) } })
            }
        }
    }
}

/** The 2 : 3 window of [bmp] at the bias, drawn into the box; dragging slides it over the picture. */
@Composable
private fun CropPreview(bmp: Bitmap?, biasX: Float, biasY: Float, onBias: (Float, Float) -> Unit, modifier: Modifier) {
    val image = remember(bmp) { bmp?.asImageBitmap() }
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(Tokens.Border)
            .pointerInput(bmp) {
                var bx = biasX
                var by = biasY
                detectDragGestures(onDragStart = { bx = biasX; by = biasY }) { change, d ->
                    change.consume()
                    val b = bmp ?: return@detectDragGestures
                    val w = CoverCrop.window(b.width, b.height, 0f, 0f)
                    // How far the window can move, in preview pixels: dragging the picture moves the window the other way.
                    val roomX = (b.width - (w[2] - w[0])).toFloat() / (w[2] - w[0]) * this.size.width
                    val roomY = (b.height - (w[3] - w[1])).toFloat() / (w[3] - w[1]) * this.size.height
                    if (roomX > 0f) bx = (bx - d.x * 2f / roomX).coerceIn(-1f, 1f)
                    if (roomY > 0f) by = (by - d.y * 2f / roomY).coerceIn(-1f, 1f)
                    onBias(bx, by)
                }
            },
    ) {
        if (image != null && bmp != null) Canvas(Modifier.matchParentSize()) {
            val w = CoverCrop.window(bmp.width, bmp.height, biasX, biasY)
            drawImage(
                image, srcOffset = IntOffset(w[0], w[1]), srcSize = IntSize(w[2] - w[0], w[3] - w[1]),
                dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()),
            )
        }
    }
}

/** A picture the user picked, decoded small and cropped to fill [modifier]. */
@Composable
internal fun PickedPreview(uri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { 160.dp.roundToPx() }
    val bmp by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    var sample = 1
                    while (maxOf(info.size.width, info.size.height) / (sample * 2) >= px) sample *= 2
                    d.setTargetSampleSize(sample)
                }
            }.getOrNull()
        }
    }
    bmp?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier) }
}
