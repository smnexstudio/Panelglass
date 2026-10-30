package com.smnexstudio.panelglass.core.engine.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.smnexstudio.panelglass.core.data.download.SystemDownloads
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.model.UserModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's own model library: the models they downloaded from Hugging Face or imported from a file, and which of
 * them the "Offline model" engine runs.
 *
 * A model is only ever added from a `.litertlm` file whose SHA-256 matches what was promised, so a truncated or
 * tampered download is refused instead of loaded.
 */
@Singleton
class UserModelLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val stores: ModelStores,
    private val engines: LocalEngines,
    private val downloads: SystemDownloads,
    private val catalog: HfCatalog,
) {
    /** The library, as saved. */
    val models: Flow<List<UserModel>> = settings.userModels

    /**
     * Adds a model from the Hugging Face catalogue and starts its download.
     *
     * The address is checked against the hub before it is saved: only a `https://huggingface.co/` file is ever
     * fetched, whatever the listing said.
     */
    suspend fun addFromHub(
        l: HfCatalog.HfListing,
        f: HfCatalog.HfFile,
        label: String,
        maxTokens: Int,
    ): Result<UserModel> = withContext(Dispatchers.IO) {
        // The repository must look like one, and the address must be on the hub: nothing else is ever fetched.
        if (!catalog.looksLikeRepoId(l.repoId)) return@withContext Result.failure(IOException("Not a Hugging Face repository"))
        val url = catalog.downloadUrl(l, f)
        if (!catalog.isHubUrl(url)) return@withContext Result.failure(IOException("Not a Hugging Face address"))
        if (!hasRoom(f.bytes)) return@withContext Result.failure(IOException(NOT_ENOUGH_SPACE))
        val sha = f.sha256.lowercase()
        val id = UserModel.hubId(sha)
        val existing = settings.userModels.first()
        if (existing.any { it.id == id }) return@withContext Result.failure(IOException(ALREADY_ADDED))
        val m = UserModel(
            id = id,
            label = label.trim().ifEmpty { l.repoId.substringAfter('/') },
            fileName = UserModel.fileNameFor(id),
            url = url,
            bytes = f.bytes,
            sha256 = sha,
            maxTokens = UserModel.clampTokens(maxTokens),
            source = l.repoId,
        )
        settings.saveUserModels(existing + m)
        stores.forUser(m).download()
        Result.success(m)
    }

    /**
     * Imports a `.litertlm` file from the phone's storage. The file is copied into the models directory while its
     * SHA-256 is computed, and only kept when the copy matches what was read.
     */
    suspend fun importFile(
        uri: Uri,
        label: String,
        maxTokens: Int,
        onProgress: (Long, Long) -> Unit,
    ): Result<UserModel> = withContext(Dispatchers.IO) {
        val info = info(uri) ?: return@withContext Result.failure(IOException(NOT_LITERTLM))
        if (!info.first.endsWith(".litertlm")) return@withContext Result.failure(IOException(NOT_LITERTLM))
        val size = info.second
        if (!hasRoom(size)) return@withContext Result.failure(IOException(NOT_ENOUGH_SPACE))

        val root = downloads.root() ?: return@withContext Result.failure(IOException("Storage unavailable"))
        val tmp = File(root, "models/user-import.tmp")
        try {
            tmp.parentFile?.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                        read += n
                        onProgress(read, size)
                    }
                }
            } ?: return@withContext Result.failure(IOException("Could not read the file"))

            val sha = hex(digest.digest())
            val id = UserModel.fileId(sha)
            val existing = settings.userModels.first()
            if (existing.any { it.id == id }) {
                tmp.delete()
                return@withContext Result.failure(IOException(ALREADY_ADDED))
            }
            val m = UserModel(
                id = id,
                label = label.trim().ifEmpty { info.first.removeSuffix(".litertlm") },
                fileName = UserModel.fileNameFor(id),
                url = "",
                bytes = tmp.length(),
                sha256 = sha,
                maxTokens = UserModel.clampTokens(maxTokens),
                source = "file",
            )
            // Moves the file into place and marks it verified; a file that does not match is deleted.
            downloads.place(tmp, "models/" + m.fileName, m.sha256)
            settings.saveUserModels(existing + m)
            Result.success(m)
        } catch (e: Exception) {
            tmp.delete()
            Result.failure(e)
        }
    }

    /** Removes a model: its file, its engine and its place in the library. */
    suspend fun remove(m: UserModel) {
        engines.dropUser(m.id)
        stores.dropUser(m.id)
        val remaining = settings.userModels.first().filterNot { it.id == m.id }
        settings.saveUserModels(remaining)
        if (settings.current().activeUserModelId == m.id) settings.setActiveUserModel("")
    }

    /** The model the "Offline model" engine runs. */
    suspend fun setActive(id: String) = settings.setActiveUserModel(id)

    /** The display name and size of a file the user picked, or null when it is not a `.litertlm` file. */
    fun info(uri: Uri): Pair<String, Long>? {
        var name: String? = null
        var size = 0L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = c.getString(nameIndex)
                if (sizeIndex >= 0 && !c.isNull(sizeIndex)) size = c.getLong(sizeIndex)
            }
        }
        val n = name ?: return null
        return n to size
    }

    /**
     * Whether there is room for a file of [bytes]: the file itself plus some headroom, so the phone does not fill up
     * completely with a model on it.
     */
    private fun hasRoom(bytes: Long): Boolean {
        val root = downloads.root() ?: return false
        return root.usableSpace >= bytes + HEADROOM
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        /** Free space kept on top of the file itself. */
        const val HEADROOM = 200L * 1024 * 1024
        private const val BUFFER = 1 shl 20
        const val NOT_ENOUGH_SPACE = "Not enough storage"
        const val ALREADY_ADDED = "Already added"
        const val NOT_LITERTLM = "Only a .litertlm model file can be added"
    }
}
