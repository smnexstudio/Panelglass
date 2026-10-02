package com.smnexstudio.panelglass.feature.studio.export

import android.app.DownloadManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.smnexstudio.panelglass.feature.studio.StorageSpace
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** A file or folder in an export destination; [id] is the target's own handle (a URI, a path). */
data class ExportEntry(val id: String, val name: String, val isDir: Boolean)

/**
 * Where an export is written, as the exporter needs it: list, create folders, write (replacing a file of the same
 * name) and delete. A seam: the phone writes to Downloads through MediaStore or to a picked folder through the
 * Storage Access Framework, tests to a temp directory. Every call throws [IOException] (or [SecurityException] when a
 * folder's permission is gone).
 */
interface ExportTarget {
    val root: String
    fun list(dir: String): List<ExportEntry>
    fun find(dir: String, name: String): ExportEntry? = list(dir).firstOrNull { it.name == name }
    /** The folder [name] in [dir], created when missing (or when first written to). */
    fun folder(dir: String, name: String): String
    /** Writes [name] in [dir]. */
    suspend fun write(dir: String, name: String, mime: String, body: suspend (OutputStream) -> Unit)
    fun delete(entry: ExportEntry)
}

/**
 * Export destinations. A null destination is the default, `Download/Panelglass` (MediaStore: no permission, no
 * picker); any other is a folder the user picked, kept as a persisted tree URI.
 */
interface ExportTargets {
    /** Keeps read / write access to [uri] (a tree the system picker returned); false when it cannot be kept. */
    fun adopt(uri: String): Boolean
    /** The destination, or null when a picked folder's permission is gone (revoked, the SD card removed). */
    fun open(uri: String?): ExportTarget?
    /** The destination's name for the screen; null when it cannot be read. */
    fun label(uri: String?): String?
    /** Bytes free on the destination's storage; null when it cannot be told. */
    fun freeBytes(uri: String?): Long?

    /** Ways to show [folder] (a folder in the destination) in a files app, best first; the first one that opens wins. */
    fun openIntents(uri: String?, folder: String): List<Intent> = emptyList()
}

@Singleton
class SystemExportTargets @Inject constructor(@ApplicationContext private val context: Context) : ExportTargets {
    private val resolver get() = context.contentResolver
    private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    override fun adopt(uri: String): Boolean = runCatching {
        resolver.takePersistableUriPermission(Uri.parse(uri), flags)
        true
    }.getOrDefault(false)

    private fun granted(tree: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }

    override fun open(uri: String?): ExportTarget? {
        if (uri == null) return DownloadsTarget()
        val tree = Uri.parse(uri)
        if (!granted(tree)) return null
        return runCatching { TreeTarget(tree) }.getOrNull()
    }

    override fun label(uri: String?): String? {
        if (uri == null) return DOWNLOADS_DIR
        return runCatching {
            val tree = Uri.parse(uri)
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    @Suppress("DEPRECATION") // StatFs of shared storage; nothing is read or written through this path
    override fun freeBytes(uri: String?): Long? {
        if (uri == null) return StorageSpace.free(Environment.getExternalStorageDirectory())
        // A provider reports each root's free bytes; the picked tree lives under the root whose document id prefixes it.
        return runCatching {
            val tree = Uri.parse(uri)
            val treeDoc = DocumentsContract.getTreeDocumentId(tree)
            val cols = arrayOf(DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
            resolver.query(DocumentsContract.buildRootsUri(tree.authority), cols, null, null, null)?.use { c ->
                var best: Pair<Int, Long>? = null
                while (c.moveToNext()) {
                    val doc = c.getString(0) ?: continue
                    if (c.isNull(1) || !treeDoc.startsWith(doc)) continue
                    if (best == null || doc.length > best.first) best = doc.length to c.getLong(1)
                }
                best?.second
            }
        }.getOrNull()
    }

    /**
     * The phone's own storage provider names a folder by its path (`primary:Download/Panelglass/Manga`), which the
     * system Files app opens; a picked folder of another provider opens at the folder itself. The Downloads app is the
     * last resort for the default destination.
     */
    override fun openIntents(uri: String?, folder: String): List<Intent> {
        val out = ArrayList<Intent>()
        fun view(doc: Uri) = Intent(Intent.ACTION_VIEW).setDataAndType(doc, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (uri == null) {
            out += view(DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:$DOWNLOADS_DIR/$folder"))
            out += view(DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:$DOWNLOADS_DIR"))
            out += Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else runCatching {
            val tree = Uri.parse(uri)
            val root = DocumentsContract.getTreeDocumentId(tree)
            if (tree.authority == EXTERNAL_STORAGE) out += view(DocumentsContract.buildDocumentUriUsingTree(tree, "$root/$folder"))
            out += view(DocumentsContract.buildDocumentUriUsingTree(tree, root))
        }
        return out
    }

    /**
     * `Download/Panelglass` through MediaStore's Downloads collection. Folders are relative paths (a file's folder
     * exists once a file is written in it); an app sees only the files it wrote itself, which are all an export
     * replaces. Files are written pending, so other apps never see half a page.
     */
    private inner class DownloadsTarget : ExportTarget {
        private val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        override val root: String = DOWNLOADS_DIR

        override fun list(dir: String): List<ExportEntry> {
            val prefix = "$dir/"
            val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH)
            val files = ArrayList<ExportEntry>()
            val dirs = LinkedHashSet<String>()
            resolver.query(collection, cols, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$prefix%"), null)?.use { c ->
                while (c.moveToNext()) {
                    val rel = c.getString(2) ?: continue
                    if (!rel.startsWith(prefix)) continue // LIKE treats _ and % in names as wildcards
                    if (rel == prefix) files += ExportEntry(ContentUris.withAppendedId(collection, c.getLong(0)).toString(), c.getString(1) ?: continue, false)
                    else dirs += rel.removePrefix(prefix).substringBefore('/')
                }
            } ?: throw IOException("downloads not readable")
            return files + dirs.map { ExportEntry("$dir/$it", it, true) }
        }

        override fun folder(dir: String, name: String): String = "$dir/$name"

        override suspend fun write(dir: String, name: String, mime: String, body: suspend (OutputStream) -> Unit) {
            val existing = find(dir, name)?.takeIf { !it.isDir }?.let { Uri.parse(it.id) }
            val uri = existing ?: resolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: throw IOException("file not created")
            try {
                (resolver.openOutputStream(uri, if (existing != null) "wt" else "w") ?: throw IOException("file not writable"))
                    .buffered().use { body(it) }
            } catch (e: Throwable) {
                if (existing == null) runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            if (existing == null) resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }

        override fun delete(entry: ExportEntry) {
            resolver.delete(Uri.parse(entry.id), null, null)
        }
    }

    /** A picked folder through `DocumentsContract` (no androidx.documentfile). */
    private inner class TreeTarget(private val tree: Uri) : ExportTarget {
        override val root: String = DocumentsContract.getTreeDocumentId(tree)

        private fun uri(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

        override fun list(dir: String): List<ExportEntry> {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            val out = ArrayList<ExportEntry>()
            resolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    out += ExportEntry(c.getString(0), name, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
                }
            } ?: throw IOException("folder not readable")
            return out
        }

        override fun folder(dir: String, name: String): String {
            find(dir, name)?.let { if (it.isDir) return it.id else throw IOException("a file has the folder's name") }
            val made = DocumentsContract.createDocument(resolver, uri(dir), DocumentsContract.Document.MIME_TYPE_DIR, name)
                ?: throw IOException("folder not created")
            return DocumentsContract.getDocumentId(made)
        }

        override suspend fun write(dir: String, name: String, mime: String, body: suspend (OutputStream) -> Unit) {
            val existing = find(dir, name)?.takeIf { !it.isDir }
            val doc = existing?.let { uri(it.id) }
                ?: DocumentsContract.createDocument(resolver, uri(dir), mime, name)
                ?: throw IOException("file not created")
            // "wt" truncates a file being replaced; a provider without it gets plain "w" (on a new file, the same).
            val stream = runCatching { resolver.openOutputStream(doc, "wt") }.getOrNull()
                ?: resolver.openOutputStream(doc, "w")
                ?: throw IOException("file not writable")
            try {
                stream.buffered().use { body(it) }
            } catch (e: Throwable) {
                if (existing == null) runCatching { DocumentsContract.deleteDocument(resolver, doc) }
                throw e
            }
        }

        override fun delete(entry: ExportEntry) {
            DocumentsContract.deleteDocument(resolver, uri(entry.id))
        }
    }

    companion object {
        /** The default destination, a folder of Downloads (shown to the user as it is). */
        val DOWNLOADS_DIR: String = Environment.DIRECTORY_DOWNLOADS + "/Panelglass"
        private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class StudioExportModule {
    @Binds abstract fun targets(impl: SystemExportTargets): ExportTargets
    @Binds abstract fun composer(impl: RenderedPageComposer): PageComposer
}
