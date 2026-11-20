package com.smnexstudio.panelglass.core.data.backup

import androidx.room.withTransaction
import com.smnexstudio.panelglass.core.data.db.PanelglassDb
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.GlossaryEntry
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Pronoun
import com.smnexstudio.panelglass.core.model.SeriesNotes
import com.smnexstudio.panelglass.core.model.Site
import com.smnexstudio.panelglass.core.model.TranslationNotes
import com.smnexstudio.panelglass.core.model.WebUrl
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A backup (Settings › Backup & restore): the user's sites, every series' glossary and notes, and the translation
 * notes from Settings. Nothing else: no other settings, no API keys, models or Studio files. Languages, engines and
 * pronouns are kept as names, so a file from another version opens, and a value this version does not know falls
 * back to the default instead of failing the file.
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val createdAt: Long = 0,
    val appVersion: String = "",
    val sites: List<BackupSite> = emptyList(),
    val glossary: List<BackupTerm> = emptyList(),
    val seriesNotes: List<BackupNotes> = emptyList(),
    /** Settings › Languages & notes › Translation notes; null in a file that has none. */
    val translationNotes: String? = null,
) {
    companion object {
        const val FORMAT = "panelglass-backup"
        const val VERSION = 1
    }
}

@Serializable
data class BackupSite(
    val name: String,
    val url: String,
    val pinned: Boolean = false,
    val sortOrder: Int = 0,
    val sourceLang: String? = null,
    val targetLang: String? = null,
    val engineId: String? = null,
    val adBlockEnabled: Boolean = true,
    val autoTranslate: Boolean = false,
)

@Serializable
data class BackupTerm(val seriesKey: String, val source: String, val target: String, val pronoun: String? = null)

@Serializable
data class BackupNotes(val seriesKey: String, val notes: String)

/** What a backup holds, or what a restore wrote: counted for the screen. */
data class BackupSummary(val sites: Int, val terms: Int, val series: Int, val translationNotes: Boolean)

/** Why a file could not be read as a backup. */
enum class BackupProblem {
    /** Not a Panelglass backup, damaged, or larger than any backup could be. */
    UNREADABLE,
    /** Made by a newer Panelglass, whose format this version does not know. */
    NEWER,
}

class BackupException(val problem: BackupProblem) : Exception(problem.name)

@Singleton
class AppBackup @Inject constructor(
    private val db: PanelglassDb,
    private val settings: SettingsRepository,
) {
    /** The clock of [create]; swapped in tests. */
    internal var now: () -> Long = System::currentTimeMillis

    /** Everything a backup holds, as it is now. */
    suspend fun create(appVersion: String): BackupFile = BackupFile(
        createdAt = now(),
        appVersion = appVersion,
        sites = db.sites().all().sortedBy { it.sortOrder }.map { s ->
            BackupSite(s.name, s.url, s.pinned, s.sortOrder, s.sourceLang?.name, s.targetLang?.name, s.engineId?.name, s.adBlockEnabled, s.autoTranslate)
        },
        glossary = db.glossary().all().map { BackupTerm(it.seriesKey, it.source, it.target, it.pronoun?.name) },
        seriesNotes = db.seriesNotes().all().map { BackupNotes(it.seriesKey, it.notes) },
        translationNotes = settings.current().translationNotes,
    )

    fun write(backup: BackupFile, out: OutputStream) {
        out.write(JSON.encodeToString(BackupFile.serializer(), backup).toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /**
     * Reads a backup file and checks it (size, format, version, counts) before anything is applied.
     * @throws BackupException when it is not one this version can restore.
     */
    fun read(input: InputStream): BackupFile {
        val bytes = input.readAtMost(MAX_BYTES + 1)
        if (bytes.size > MAX_BYTES) throw BackupException(BackupProblem.UNREADABLE)
        val file = runCatching { JSON.decodeFromString(BackupFile.serializer(), bytes.toString(Charsets.UTF_8)) }
            .getOrElse { throw BackupException(BackupProblem.UNREADABLE) }
        if (file.format != BackupFile.FORMAT) throw BackupException(BackupProblem.UNREADABLE)
        if (file.version > BackupFile.VERSION) throw BackupException(BackupProblem.NEWER)
        if (file.sites.size > MAX_SITES || file.glossary.size > MAX_TERMS || file.seriesNotes.size > MAX_SERIES) {
            throw BackupException(BackupProblem.UNREADABLE)
        }
        return file
    }

    /** What [backup] holds, shown before it is restored. */
    fun summary(backup: BackupFile) = BackupSummary(
        sites = backup.sites.size,
        terms = backup.glossary.size,
        series = (backup.glossary.map { it.seriesKey } + backup.seriesNotes.map { it.seriesKey }).distinct().size,
        translationNotes = backup.translationNotes != null,
    )

    /**
     * Merges [backup] into what is saved, in one transaction; nothing is deleted. A site at an address already saved
     * is updated (it keeps its place in the list), others are added at the end; every address goes through
     * [WebUrl.https] and one that cannot be https is skipped. A term of the same series and source is updated, others
     * added; a series' notes are replaced by the backup's. The translation notes are replaced when the file has them.
     * Every text is cut to the limit the app keeps on save.
     */
    suspend fun restore(backup: BackupFile): BackupSummary {
        var sites = 0
        var terms = 0
        val series = HashSet<String>()
        db.withTransaction {
            val saved = db.sites().all().associateBy { addressKey(it.url) }.toMutableMap()
            var order = db.sites().nextSortOrder()
            for (b in backup.sites.sortedBy { it.sortOrder }) {
                val url = WebUrl.https(b.url)?.takeIf { it.length <= MAX_URL_CHARS } ?: continue
                val site = Site(
                    name = b.name.trim().take(MAX_NAME_CHARS).ifEmpty { Site.hostOf(url) },
                    url = url,
                    pinned = b.pinned,
                    sourceLang = enumOrNull<Lang>(b.sourceLang),
                    targetLang = enumOrNull<Lang>(b.targetLang),
                    engineId = enumOrNull<EngineId>(b.engineId)?.takeIf { it.offered },
                    adBlockEnabled = b.adBlockEnabled,
                    autoTranslate = b.autoTranslate,
                )
                val key = addressKey(url)
                val old = saved[key]
                if (old != null) {
                    db.sites().update(site.copy(id = old.id, sortOrder = old.sortOrder, iconUrl = old.iconUrl))
                } else {
                    val new = site.copy(sortOrder = order++)
                    saved[key] = new.copy(id = db.sites().upsert(new))
                }
                sites++
            }

            val glossary = db.glossary().all().associateBy { it.seriesKey to it.source }.toMutableMap()
            for (t in backup.glossary) {
                val entry = GlossaryEntry(
                    seriesKey = t.seriesKey.trim().take(MAX_NAME_CHARS), source = t.source, target = t.target,
                    pronoun = enumOrNull<Pronoun>(t.pronoun),
                ).capped()
                if (entry.seriesKey.isEmpty() || entry.source.isEmpty()) continue
                val kept = glossary[entry.seriesKey to entry.source]?.let { entry.copy(id = it.id) } ?: entry
                db.glossary().upsert(kept)
                glossary[entry.seriesKey to entry.source] = kept
                series += entry.seriesKey
                terms++
            }

            for (n in backup.seriesNotes) {
                val key = n.seriesKey.trim().take(MAX_NAME_CHARS)
                val text = n.notes.trim().take(TranslationNotes.MAX_CHARS)
                if (key.isEmpty() || text.isEmpty()) continue
                db.seriesNotes().put(SeriesNotes(key, text))
                series += key
            }
        }
        // Capped and trimmed by the setter, as when typed in Settings.
        backup.translationNotes?.let { settings.setTranslationNotes(it) }
        return BackupSummary(sites, terms, series.size, backup.translationNotes != null)
    }

    companion object {
        /** A backup of thousands of sites and terms is well under this; anything larger is not one. */
        const val MAX_BYTES = 4 * 1024 * 1024
        const val MAX_SITES = 2_000
        const val MAX_TERMS = 50_000
        const val MAX_SERIES = 10_000
        const val MAX_NAME_CHARS = 200
        const val MAX_URL_CHARS = 2_048

        /** The file's name as the save dialog offers it, with the day it was made. */
        fun fileName(date: LocalDate): String = "panelglass-backup-$date.json"

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

        /** One address of a site: the case of scheme and host and a trailing slash do not matter. */
        internal fun addressKey(url: String): String {
            val scheme = url.substringBefore("://", "")
            val rest = url.substringAfter("://", url)
            val host = rest.substringBefore('/')
            return scheme.lowercase() + "://" + host.lowercase() + rest.substring(host.length).trimEnd('/')
        }

        private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
            name?.let { n -> enumValues<T>().firstOrNull { it.name == n } }

        /** Reads at most [limit] bytes (`readNBytes` needs API 33). */
        private fun InputStream.readAtMost(limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (out.size() < limit) {
                val n = read(buf, 0, minOf(buf.size, limit - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
