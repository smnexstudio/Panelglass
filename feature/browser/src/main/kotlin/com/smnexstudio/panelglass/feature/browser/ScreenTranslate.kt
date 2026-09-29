package com.smnexstudio.panelglass.feature.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.webkit.WebView
import androidx.compose.foundation.Canvas as DrawCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.smnexstudio.panelglass.core.ui.R as UiR
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Patch
import org.json.JSONObject
import org.json.JSONTokener
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Snapshot of the WebView's on-screen pixels together with the scroll offset and zoom they were taken at.
 * `PixelCopy` reads the composited surface (what the user sees, hardware-accelerated content included); a
 * software `draw()` is the fallback for windows it cannot read. Never a page fetch: whatever a reader site does
 * with its images (canvas, blobs, scrambled tiles), these are the pixels the user sees.
 */
@Suppress("DEPRECATION")
suspend fun captureViewport(webView: WebView): ViewportCapture? {
    val w = webView.width; val h = webView.height
    if (w <= 0 || h <= 0) return null
    // Pages still downloading are painted partly or not at all: wait for them (briefly — a stalled image must not
    // hold the translation back). The scroll offset is read after the wait, with the pixels it belongs to.
    var map = viewportMap(webView, webView.scale)
    val waitUntil = System.currentTimeMillis() + IMAGE_WAIT_MS
    while (map.pending > 0 && System.currentTimeMillis() < waitUntil) {
        delay(IMAGE_POLL_MS)
        map = viewportMap(webView, webView.scale)
    }
    if (map.pending > 0) android.util.Log.d("ScreenCapture", "capturing with ${map.pending} image(s) still loading")
    val scrollX = webView.scrollX; val scrollY = webView.scrollY; val scale = webView.scale
    val images = map.images; val overlays = map.overlays
    // Asked again later (main thread, as here): where each page image is by then, at the zoom by then, so patches
    // can follow it.
    val layoutNow: suspend () -> ImageLayout? = {
        val s = webView.scale
        if (webView.width <= 0) null
        else viewportMap(webView, s).let { ImageLayout(it.images, it.keys, webView.scrollX, webView.scrollY, s) }
    }
    val pendingNow: suspend () -> Int = { viewportMap(webView, webView.scale).pending }
    // What the viewport looks like now, small (patches included: the comparison masks them).
    val thumbNow: suspend () -> Thumb? = {
        val tw = Thumb.WIDTH; val th = (Thumb.WIDTH * webView.height / webView.width.coerceAtLeast(1)).coerceAtLeast(1)
        val small = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        try { if (pixelCopy(webView, small)) Thumb.of(small).let { Thumb(it.luma, it.gw, it.gh, webView.width, webView.height) } else null }
        finally { small.recycle() }
    }
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    fun capture() = ViewportCapture(bitmap, scrollX, scrollY, scale, images, overlays, map.keys, layoutNow, map.pending, pendingNow, thumbNow)
    if (pixelCopy(webView, bitmap)) return capture()
    return runCatching { webView.draw(Canvas(bitmap)); capture() }.getOrElse { bitmap.recycle(); null }
}

/** The WebView's on-screen pixels, scaled into [dest]; false when the window cannot be read. */
private suspend fun pixelCopy(webView: WebView, dest: Bitmap): Boolean {
    val activity = webView.context.activity() ?: return false
    val loc = IntArray(2).also { webView.getLocationInWindow(it) }
    val rect = Rect(loc[0], loc[1], loc[0] + webView.width, loc[1] + webView.height)
    return suspendCancellableCoroutine { cont ->
        try {
            PixelCopy.request(activity.window, rect, dest, { result -> if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) { if (cont.isActive) cont.resume(false) }
    }
}

/**
 * Asks the page where its images are and which fixed/sticky controls float over them (`__mt.viewportMap`), in
 * view pixels. Detections outside the images or under the controls are not comic text. Empty when the bridge is
 * not there (page still loading): then nothing is excluded.
 */
/**
 * Longest reply taken from the page bridge (`__mt`/`__pt`), in characters. Real replies are a few KB (mt.js shortens
 * data: image keys); a larger one is a page feeding the app junk and is dropped before it is parsed.
 */
internal const val MAX_BRIDGE_REPLY = 1_000_000

private class ViewportMap(val images: List<IntRect>, val keys: List<String>, val overlays: List<IntRect>, val pending: Int)

private suspend fun viewportMap(webView: WebView, scale: Float): ViewportMap {
    val none = ViewportMap(emptyList(), emptyList(), emptyList(), 0)
    val raw = suspendCancellableCoroutine<String?> { cont ->
        try { webView.evaluateJavascript("window.__mt ? __mt.viewportMap() : null") { v -> if (cont.isActive) cont.resume(v) } }
        catch (_: Exception) { if (cont.isActive) cont.resume(null) }
    }?.takeIf { it.length <= MAX_BRIDGE_REPLY } ?: return none
    // evaluateJavascript returns a JSON string literal of the JSON text.
    val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return none
    val obj = runCatching { JSONObject(text) }.getOrNull() ?: return none
    fun rects(key: String): List<Pair<IntRect, String>> {
        val arr = obj.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val r = arr.optJSONObject(i) ?: return@mapNotNull null
            val x = (r.optDouble("x") * scale).toInt(); val y = (r.optDouble("y") * scale).toInt()
            val rw = (r.optDouble("w") * scale).toInt(); val rh = (r.optDouble("h") * scale).toInt()
            if (rw <= 0 || rh <= 0) null else Pair(IntRect(x, y, x + rw, y + rh), r.optString("k"))
        }
    }
    val images = rects("images"); val overlays = rects("overlays").map { it.first }; val pending = obj.optInt("pending", 0)
    android.util.Log.d("ScreenCapture", "viewport map: images=${images.size} overlays=${overlays.size} pending=$pending scale=$scale")
    return ViewportMap(images.map { it.first }, images.map { it.second }, overlays, pending)
}

/** Longest a capture waits for on-screen images still downloading, and how often it asks. */
private const val IMAGE_WAIT_MS = 3000L
private const val IMAGE_POLL_MS = 250L

private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/**
 * Patches over the viewport: their CSS rects times the live zoom, minus the live scroll offset, so they ride with
 * the content and grow or shrink with it when the page is zoomed. The layer takes no touches. A small status disc
 * in the corner shows working / done / failed.
 */
@Composable
fun ScreenOverlayLayer(state: ScreenSession, scroll: Pair<Int, Int>, zoom: Float, modifier: Modifier = Modifier) {
    if (!state.active) return
    Box(modifier.fillMaxSize()) {
        // Patches arrive already decoded (off the main thread); this only reads the scroll offset and zoom, so a
        // scroll or zoom step costs one redraw and no recomposition.
        DrawCanvas(Modifier.fillMaxSize().clipToBounds()) {
            val (sx, sy) = scroll
            for (p in state.patches) {
                val r = p.rectAt(zoom)
                val x = r.left - sx; val y = r.top - sy
                if (x + r.width < 0 || y + r.height < 0 || x > size.width || y > size.height) continue
                // Bilinear filtering: a patch drawn at another zoom than it was made at is scaled, not re-rendered.
                drawImage(
                    p.image, dstOffset = IntOffset(x, y), dstSize = IntSize(r.width.coerceAtLeast(1), r.height.coerceAtLeast(1)),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
        StatusDisc(state, Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp))
    }
}

/** 28 dp disc: spinner while a viewport is being translated, a check when it is done, a warning after a failure. */
@Composable
private fun StatusDisc(state: ScreenSession, modifier: Modifier) {
    Box(
        modifier.size(28.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            state.working -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            state.error != null -> Icon(Icons.Filled.Warning, contentDescription = state.error, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
            else -> Icon(Icons.Filled.Check, contentDescription = stringResource(UiR.string.reader_screen_translated), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        }
    }
}

/** WEBP → ImageBitmap, meant for a worker thread. */
fun decodePatch(p: Patch): ImageBitmap? =
    runCatching { BitmapFactory.decodeByteArray(p.webp, 0, p.webp.size)?.asImageBitmap() }.getOrNull()
