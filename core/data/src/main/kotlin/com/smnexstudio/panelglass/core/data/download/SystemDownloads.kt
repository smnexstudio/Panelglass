package com.smnexstudio.panelglass.core.data.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Large model files fetched by Android's [DownloadManager], not by an HTTP call in our process. The system service
 * keeps going when the user switches apps or the app is killed, resumes after a dropped connection, retries on its
 * own, and shows its own progress notification. An in-process download died on every app switch.
 *
 * Files land in the app-specific external directory (`getExternalFilesDir`), the only private place DownloadManager
 * may write without a permission; other apps cannot read it. A download is remembered by [key] across restarts so
 * a store can pick its progress up again.
 */
@Singleton
class SystemDownloads @Inject constructor(@ApplicationContext private val context: Context) : Downloads {
    private val dm: DownloadManager get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val ids get() = context.getSharedPreferences("system_downloads", Context.MODE_PRIVATE)

    /** Progress of one download; [note] says why it is paused. */
    data class Progress(val bytes: Long, val total: Long, val note: String? = null)

    /** Root for downloaded models, or null when shared storage is unavailable. */
    fun root(): File? = context.getExternalFilesDir(null)

    /** Whether a download for [key] is queued or running (in this or an earlier app session). */
    override fun isActive(key: String): Boolean = ids.contains(key)

    /**
     * Queues [url] into `root()/[relativePath]`. The file is written as `…part` and renamed by [await] once complete,
     * so a half-written file is never mistaken for a model. Starting an already queued key is a no-op.
     */
    override fun start(key: String, url: String, relativePath: String, title: String, description: String) {
        if (isActive(key)) return
        if (!url.startsWith("https://", ignoreCase = true)) throw IOException("Only HTTPS downloads are allowed")
        val root = root() ?: throw IOException("Storage unavailable")
        File(root, "$relativePath.part").apply { parentFile?.mkdirs(); delete() }
        dropStrayParts(File(root, relativePath))
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setDescription(description)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, null, "$relativePath.part")
            // Wi-Fi only unless the user allowed mobile data (DownloadNetwork; the system then shows "waiting for Wi-Fi").
            .setAllowedOverMetered(!DownloadNetwork.wifiOnly)
            .setAllowedOverRoaming(false)
        ids.edit().putLong(key, dm.enqueue(req)).putBoolean(key + WIFI, DownloadNetwork.wifiOnly).apply()
    }

    private fun forget(key: String) = ids.edit().remove(key).remove(key + WIFI).apply()

    /**
     * Removes this app's downloads still waiting (nothing received) whose address contains [urlPart]; returns how
     * many. ML Kit queues its language packs through this same system service, under this app, and keeps the
     * networks it asked for: when Wi-Fi only is turned off, its waiting packs are removed here and asked for again.
     */
    fun dropWaiting(urlPart: String): Int {
        val waiting = DownloadManager.STATUS_PENDING or DownloadManager.STATUS_PAUSED
        val ids = ArrayList<Long>()
        runCatching {
            dm.query(DownloadManager.Query().setFilterByStatus(waiting))?.use { c ->
                while (c.moveToNext()) {
                    val uri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)).orEmpty()
                    val bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    if (urlPart in uri && bytes == 0L) ids += c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                }
            }
        }
        if (ids.isNotEmpty()) runCatching { dm.remove(*ids.toLongArray()) }
        return ids.size
    }

    /** Whether [key] was queued for Wi-Fi only (the setting then; a queued download keeps its networks). */
    fun queuedWifiOnly(key: String): Boolean = ids.getBoolean(key + WIFI, false)

    /** The phone is on a metered network (mobile data) or none, so a Wi-Fi-only download cannot run. */
    private fun offWifi(): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        return cm.activeNetwork == null || cm.isActiveNetworkMetered
    }

    override fun cancel(key: String) {
        val id = ids.getLong(key, -1L)
        if (id >= 0) runCatching { dm.remove(id) }
        forget(key)
    }

    /**
     * Follows [key] until it ends, reporting progress about once a second. On success the `.part` file is renamed to
     * `root()/[relativePath]` and returned; on failure an [IOException] carries a short reason. Cancelling the caller
     * only stops following: the download itself continues until [cancel].
     */
    override suspend fun await(key: String, relativePath: String, minBytes: Long, sha256: String, onProgress: (Progress) -> Unit): File {
        val id = ids.getLong(key, -1L)
        if (id < 0) throw IOException("No download")
        while (true) {
            val row = query(id)
            if (row == null) { forget(key); throw IOException("Download was cancelled") }
            when (row.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    forget(key)
                    val root = root() ?: throw IOException("Storage unavailable")
                    // The system may write `name-1.part` when `name.part` exists (an earlier, removed download): take
                    // the file it reports, as long as it is ours, beside the one asked for.
                    val asked = File(root, "$relativePath.part")
                    val part = row.file?.takeIf { it.parentFile?.canonicalPath == asked.parentFile?.canonicalPath } ?: asked
                    if (part.length() < minBytes) { part.delete(); throw IOException("Incomplete file") }
                    onProgress(Progress(row.total, row.total, CHECKING))
                    return place(part, relativePath, sha256).also { dropStrayParts(it) }
                }
                DownloadManager.STATUS_FAILED -> {
                    forget(key)
                    runCatching { dm.remove(id) }
                    throw IOException(failure(row.reason))
                }
                else -> onProgress(Progress(row.bytes, row.total, when {
                    // Queued for Wi-Fi and the phone is not on it: the system only reports "pending" with no reason.
                    row.status != DownloadManager.STATUS_RUNNING && queuedWifiOnly(key) && offWifi() -> WAITING_FOR_WIFI
                    row.status == DownloadManager.STATUS_PAUSED -> paused(row.reason)
                    else -> null
                }))
            }
            delay(POLL_MS)
        }
    }

    // ---- integrity ----------------------------------------------------------------------------------------------

    /**
     * Where a verified file for [relativePath] ends up: the app-specific external directory, private to this app
     * (Android 10+; the app needs Android 12).
     */
    override fun finalFile(relativePath: String): File? = root()?.let { File(it, relativePath) }

    /** Moves [src] to [finalFile] of [relativePath] if its SHA-256 is [sha256], else deletes it and throws. */
    fun place(src: File, relativePath: String, sha256: String): File {
        val target = finalFile(relativePath) ?: throw IOException("Storage unavailable")
        target.parentFile?.mkdirs()
        if (!hashOf(src).equals(sha256, ignoreCase = true)) { src.delete(); throw IOException(CORRUPT) }
        if (src != target) {
            target.delete()
            if (!src.renameTo(target)) { src.delete(); throw IOException("Could not move the file into place") }
        }
        markVerified(target, sha256)
        return target
    }

    /**
     * Checks a file already on disk (an earlier install, a side-load) once: the result is remembered against its
     * size and modification time, so a multi-gigabyte model is not hashed on every launch. A file that fails is
     * deleted. Returns the file to load, or null.
     */
    override fun adopt(file: File, sha256: String): File? {
        if (isVerified(file, sha256)) return file
        return try {
            if (hashOf(file).equals(sha256, ignoreCase = true)) file.also { markVerified(it, sha256) }
            else { file.delete(); null }
        } catch (e: IOException) { null }
    }

    fun isVerified(file: File, sha256: String): Boolean =
        file.isFile && verified.getString(file.canonicalPath, null) == stamp(file, sha256)

    private fun markVerified(file: File, sha256: String) =
        verified.edit().putString(file.canonicalPath, stamp(file, sha256)).apply()

    private fun stamp(file: File, sha256: String) = sha256.lowercase() + ":" + file.length() + ":" + file.lastModified()

    private val verified get() = context.getSharedPreferences("verified_files", Context.MODE_PRIVATE)

    private fun hashOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER)
            while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /**
     * Removes `<name>-N.part` files left beside [placed] (`<name>`): copies the system wrote under another name for a
     * download that was removed or never followed. Each can be a full model.
     */
    private fun dropStrayParts(placed: File) {
        val stem = placed.name
        placed.parentFile?.listFiles { f -> f.isFile && f.name.startsWith("$stem-") && f.name.endsWith(".part") }
            ?.forEach { it.delete() }
    }

    /** [file] is where the system wrote the download (`COLUMN_LOCAL_URI`), when it says. */
    private class Row(val status: Int, val reason: Int, val bytes: Long, val total: Long, val file: File? = null)

    private fun query(id: Long): Row? = dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
        if (!c.moveToFirst()) return null
        Row(
            status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
            reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
            bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
            total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            file = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                ?.let { Uri.parse(it) }?.takeIf { it.scheme == "file" }?.path?.let(::File),
        )
    }

    private fun paused(reason: Int): String = when (reason) {
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "waiting for network"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "connection lost, retrying"
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> WAITING_FOR_WIFI
        else -> "paused"
    }

    private fun failure(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Storage unavailable"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS, DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Server refused the download"
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_CANNOT_RESUME -> "Connection failed; tap Retry"
        in 400..599 -> "HTTP $reason"
        else -> "Download failed"
    }

    companion object {
        private const val POLL_MS = 1_000L
        private const val BUFFER = 1 shl 20
        /** Progress note while a finished download is being hashed. */
        const val CHECKING = "checking the file"
        const val CORRUPT = "File failed its integrity check; tap Retry"
        /** Progress note of a Wi-Fi-only download while the phone is off Wi-Fi. */
        const val WAITING_FOR_WIFI = "waiting for Wi-Fi"
        /** Suffix of the remembered "queued for Wi-Fi only" flag beside a download's id. */
        private const val WIFI = "#wifi"
    }
}
