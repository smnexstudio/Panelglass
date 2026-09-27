package com.smnexstudio.panelglass.core.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.smnexstudio.panelglass.core.model.GlossaryEntry
import com.smnexstudio.panelglass.core.model.HistoryEntry
import com.smnexstudio.panelglass.core.model.SfxCacheEntry
import com.smnexstudio.panelglass.core.model.Site
import kotlinx.coroutines.flow.Flow

@Dao
interface SiteDao {
    @Query("SELECT * FROM sites ORDER BY pinned DESC, sortOrder ASC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Site>>

    @Query("SELECT * FROM sites WHERE id = :id")
    suspend fun byId(id: Long): Site?

    @Query("SELECT * FROM sites")
    suspend fun all(): List<Site>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(site: Site): Long

    @Update
    suspend fun update(site: Site)

    @Delete
    suspend fun delete(site: Site)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) + 1 FROM sites")
    suspend fun nextSortOrder(): Int
}

@Dao
interface SfxCacheDao {
    @Query("SELECT translated FROM sfx_cache WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM sfx_cache WHERE `key` IN (:keys)")
    suspend fun getAll(keys: List<String>): List<SfxCacheEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(entries: List<SfxCacheEntry>)

    @Query("DELETE FROM sfx_cache")
    suspend fun clear()
}

@Dao
interface GlossaryDao {
    @Query("SELECT * FROM glossary WHERE seriesKey = :seriesKey ORDER BY source")
    fun observe(seriesKey: String): Flow<List<GlossaryEntry>>

    @Query("SELECT * FROM glossary WHERE seriesKey = :seriesKey")
    suspend fun forSeries(seriesKey: String): List<GlossaryEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: GlossaryEntry)

    @Delete
    suspend fun delete(entry: GlossaryEntry)
}

const val HISTORY_LIMIT = 15

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT $HISTORY_LIMIT")
    fun observeRecent(): Flow<List<HistoryEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntry)

    /** Drops everything but the [HISTORY_LIMIT] most recent visits. */
    @Query("DELETE FROM history WHERE url NOT IN (SELECT url FROM history ORDER BY visitedAt DESC LIMIT $HISTORY_LIMIT)")
    suspend fun trim()

    /** Only the [HISTORY_LIMIT] most recent visits are kept on disk. */
    @Transaction
    suspend fun put(entry: HistoryEntry) {
        insert(entry)
        trim()
    }

    @Delete
    suspend fun delete(entry: HistoryEntry)

    @Query("DELETE FROM history")
    suspend fun clear()
}
