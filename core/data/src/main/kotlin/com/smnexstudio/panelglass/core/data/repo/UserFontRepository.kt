package com.smnexstudio.panelglass.core.data.repo

import com.smnexstudio.panelglass.core.data.db.PanelglassDb
import com.smnexstudio.panelglass.core.model.UserFont
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** The fonts the user added (docs/STUDIO_PLAN.md › My fonts): rows only; the files are the importer's. */
@Singleton
class UserFontRepository @Inject constructor(db: PanelglassDb) {
    private val dao = db.userFonts()

    val fonts: Flow<List<UserFont>> = dao.observeAll()
    suspend fun all(): List<UserFont> = dao.all()
    suspend fun save(font: UserFont) = dao.upsert(font)
    suspend fun delete(font: UserFont) = dao.delete(font)

    /** How many bubbles use [id] (they fall back to the language default once it is deleted). */
    suspend fun usage(id: String): Int = dao.bubblesUsing("\"$id\"")
}
