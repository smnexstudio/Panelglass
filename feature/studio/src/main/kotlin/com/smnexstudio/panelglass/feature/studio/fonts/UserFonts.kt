package com.smnexstudio.panelglass.feature.studio.fonts

import com.smnexstudio.panelglass.feature.studio.importer.readAtMost
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.smnexstudio.panelglass.core.data.repo.UserFontRepository
import com.smnexstudio.panelglass.core.model.FontRole
import com.smnexstudio.panelglass.core.model.UserFont
import com.smnexstudio.panelglass.core.render.FontEntry
import com.smnexstudio.panelglass.core.render.FontNameTable
import com.smnexstudio.panelglass.core.render.FontProbe
import com.smnexstudio.panelglass.core.render.StudioFonts
import com.smnexstudio.panelglass.feature.studio.StorageSpace
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Why a picked file was not added. */
enum class FontRefusal { TOO_LARGE, NOT_A_FONT, UNUSABLE, ALREADY_ADDED, NO_SPACE }

/** What one pick did: the fonts added or extended, and each refused file with its reason. */
data class FontImportResult(val added: List<UserFont>, val refused: List<Pair<String, FontRefusal>>)

/**
 * The user's own fonts (docs/STUDIO_PLAN.md › My fonts). A picked file is copied into `filesDir/fonts/user/<sha256>.<ext>`
 * (never used from its picker URI), and kept only when it is at most [MAX_BYTES], starts with a font signature and
 * loads and draws on this phone. Its family and style come from its own `name` table; a family's Regular / Bold /
 * Italic files become one font, whichever pick they come in. The same file twice is one font (found by its hash).
 * The rows feed [StudioFonts] for as long as the app runs, so the editor and the renderer see every change at once.
 */
@Singleton
class UserFontStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: UserFontRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    val dir: File = File(context.filesDir, "fonts/user")

    /** Keeps [StudioFonts] in step with the table; called once at app start. */
    fun start() {
        StudioFonts.userDir = dir
        scope.launch { repo.fonts.collect { StudioFonts.setUserFonts(it) } }
    }

    val fonts = repo.fonts

    suspend fun usage(id: String): Int = repo.usage(id)

    suspend fun rename(font: UserFont, name: String) = repo.save(font.copy(displayName = name.trim().ifEmpty { font.displayName }))
    suspend fun setRole(font: UserFont, role: FontRole) = repo.save(font.copy(role = role))

    /** Deletes the font and its files; bubbles using it letter with the language default from now on. */
    suspend fun delete(font: UserFont) = lock.withLock {
        repo.delete(font)
        withContext(Dispatchers.IO) { font.files.values.forEach { File(dir, it).delete() } }
    }

    suspend fun import(uris: List<Uri>): FontImportResult = lock.withLock {
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val existing = repo.all().toMutableList()
            val known = existing.flatMap { it.files.values }.map { it.substringBefore('.') }.toMutableSet()
            val added = LinkedHashMap<String, UserFont>()
            val refused = ArrayList<Pair<String, FontRefusal>>()
            for (uri in uris) {
                val name = displayName(uri) ?: uri.lastPathSegment ?: "?"
                val staged = try {
                    context.contentResolver.openInputStream(uri)?.use { stage(it) } ?: Staged.Refused(FontRefusal.NOT_A_FONT)
                } catch (e: IOException) {
                    Staged.Refused(if (StorageSpace.isFull(e)) FontRefusal.NO_SPACE else FontRefusal.NOT_A_FONT)
                } catch (e: SecurityException) {
                    Staged.Refused(FontRefusal.NOT_A_FONT)
                }
                when (staged) {
                    is Staged.Refused -> refused += name to staged.reason
                    is Staged.Ok -> {
                        if (staged.sha in known) {
                            staged.tmp.delete()
                            refused += name to FontRefusal.ALREADY_ADDED
                            continue
                        }
                        val target = File(dir, "${staged.sha}.${staged.kind.extension}")
                        if (!staged.tmp.renameTo(target)) { staged.tmp.delete(); refused += name to FontRefusal.NO_SPACE; continue }
                        val scripts = FontProbe.probe(target)
                        if (scripts == null) {
                            target.delete()
                            refused += name to FontRefusal.UNUSABLE
                            continue
                        }
                        known += staged.sha
                        val names = staged.names ?: FontNameTable.Names(name.substringBeforeLast('.'), "Regular", name)
                        // The same family (added now or before) gains this style; otherwise a new font.
                        val family = (added.values + existing).firstOrNull { it.family.equals(names.family, ignoreCase = true) && names.slot !in it.files }
                        val font = if (family != null) {
                            family.copy(files = family.files + (names.slot to target.name), scripts = family.scripts + scripts)
                        } else UserFont(
                            id = FontEntry.USER + staged.sha, displayName = names.family, family = names.family,
                            files = mapOf(names.slot to target.name), scripts = scripts, role = FontRole.DIALOGUE,
                            addedAt = System.currentTimeMillis(),
                        )
                        added[font.id] = font
                        existing.removeAll { it.id == font.id }
                        repo.save(font)
                    }
                }
            }
            FontImportResult(added.values.toList(), refused)
        }
    }

    private sealed interface Staged {
        class Refused(val reason: FontRefusal) : Staged
        class Ok(val tmp: File, val sha: String, val kind: FontNameTable.Kind, val names: FontNameTable.Names?) : Staged
    }

    /** Copies at most [MAX_BYTES] into a temp file, hashing as it goes; checks the signature and reads the names. */
    private fun stage(input: InputStream): Staged {
        val tmp = File.createTempFile("incoming", ".font", dir)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            tmp.outputStream().buffered().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) { tmp.delete(); return Staged.Refused(FontRefusal.TOO_LARGE) }
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
            val head = tmp.inputStream().use { it.readAtMost(4) }
            val kind = FontNameTable.kind(head) ?: run { tmp.delete(); return Staged.Refused(FontRefusal.NOT_A_FONT) }
            val names = FontNameTable.names(tmp.readBytes())
            return Staged.Ok(tmp, digest.digest().joinToString("") { "%02x".format(it) }, kind, names)
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    companion object {
        const val MAX_BYTES = 50L * 1024 * 1024
        /** What the picker offers; the signature decides, so a font a file manager types as binary is accepted too. */
        val MIME_TYPES = arrayOf(
            "font/ttf", "font/otf", "font/collection", "font/sfnt", "application/x-font-ttf", "application/x-font-otf",
            "application/font-sfnt", "application/vnd.ms-opentype", "application/octet-stream",
        )
    }
}
