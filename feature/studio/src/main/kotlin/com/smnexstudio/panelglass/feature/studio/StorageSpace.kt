package com.smnexstudio.panelglass.feature.studio

import android.os.StatFs
import android.system.ErrnoException
import android.system.OsConstants
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.io.File
import com.smnexstudio.panelglass.core.ui.R as UiR

/**
 * Free space checks for imports and exports. A job is refused before it writes anything when the storage cannot hold
 * it plus [RESERVE], and a write that still runs out of space (the estimate was low, something else filled the
 * storage) is recognised as such ([isFull]) instead of being reported as an unreadable file.
 */
object StorageSpace {
    /** Kept free beyond what a job needs: clean layers, the database, and the rest of the phone. */
    const val RESERVE = 64L * 1024 * 1024

    /** Bytes free where [dir] lives; null when it cannot be read. */
    fun free(dir: File): Long? = runCatching { StatFs(dir.path).availableBytes }.getOrNull()

    /** Whether [free] bytes hold [needed] bytes and the reserve; an unknown free space is not refused. */
    fun enough(needed: Long, free: Long?): Boolean = free == null || free >= needed + RESERVE

    /** Whether [e] (or what caused it) is the storage running out of space. */
    fun isFull(e: Throwable?): Boolean {
        var t = e
        var depth = 0
        while (t != null && depth++ < 8) {
            if (t is ErrnoException && t.errno == OsConstants.ENOSPC) return true
            val m = t.message.orEmpty()
            if ("ENOSPC" in m || "No space left" in m) return true
            t = t.cause
        }
        return false
    }
}

/** "Not enough storage: about X needed, Y free", or "The storage is full" when the numbers are not known. */
@Composable
internal fun noSpaceText(needed: Long, free: Long): String {
    val context = LocalContext.current
    return if (needed > 0) stringResource(
        UiR.string.studio_no_space,
        Formatter.formatShortFileSize(context, needed + StorageSpace.RESERVE),
        Formatter.formatShortFileSize(context, free),
    ) else stringResource(UiR.string.studio_storage_full)
}
