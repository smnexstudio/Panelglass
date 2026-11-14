package com.smnexstudio.panelglass.core.data.export

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

/**
 * What the Studio's exports and Settings › Studio › Default folder both say about a destination: the default path,
 * and a picked folder's name. One place, so the export screen and Settings never name the same folder differently.
 */
object ExportFolders {
    /** The default destination, a folder of Downloads (shown to the user as it is: a path, the same in every language). */
    val DOWNLOADS_DIR: String = Environment.DIRECTORY_DOWNLOADS + "/Panelglass"

    /** A picked folder's own name (a persisted SAF tree [uri]); null when it cannot be read (permission gone). */
    fun label(context: Context, uri: String): String? = runCatching {
        val tree = Uri.parse(uri)
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** [label], else the last part of the folder's path (`primary:Comics` → `Comics`) so a folder is never nameless. */
    fun name(context: Context, uri: String): String =
        label(context, uri) ?: Uri.parse(uri).lastPathSegment?.substringAfterLast(':').orEmpty()
}
