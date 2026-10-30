package com.smnexstudio.panelglass.core.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.data.download.SystemDownloads
import com.smnexstudio.panelglass.core.engine.local.ChatPrompt
import com.smnexstudio.panelglass.core.engine.local.LlmRunner
import com.smnexstudio.panelglass.core.engine.local.LocalEngines
import com.smnexstudio.panelglass.core.engine.local.ModelStores
import com.smnexstudio.panelglass.core.engine.local.OfflineModelEngine
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.RegionItem
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.TranslateRequest
import com.smnexstudio.panelglass.core.model.UserModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.MessageDigest

/** The "Offline model" engine: it runs the active model of the user's own library. */
@RunWith(RobolectricTestRunner::class)
class OfflineModelEngineTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val downloads = SystemDownloads(context)
    private val stores = ModelStores(context, downloads)
    private val locals = LocalEngines(context, stores)
    private val engine = OfflineModelEngine(locals, stores)
    private val items = listOf(RegionItem(0, "img", RegionKind.ENCLOSED, "こんにちは"))
    private val req = TranslateRequest(items, Lang.JA, Lang.EN)

    /**
     * A model whose file is really on the phone: the same bytes, hashed and placed where the store looks for it, so
     * the store finds it verified and ready.
     */
    private fun readyModel(id: String = "h0123456789ab"): UserModel {
        val bytes = ByteArray(1024) { 'x'.code.toByte() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val m = UserModel(
            id = id, label = "Qwen 2.5 1.5B", fileName = UserModel.fileNameFor(id),
            url = "", bytes = bytes.size.toLong(), sha256 = sha, maxTokens = 4096, source = "litert-community/Qwen",
        )
        val root = downloads.root()!!
        val tmp = File(root, "models/test-${id}.tmp")
        tmp.parentFile?.mkdirs()
        tmp.writeBytes(bytes)
        downloads.place(tmp, "models/" + m.fileName, m.sha256)
        return m
    }

    @Test
    fun withoutAnActiveModelTheEngineSaysTheModelIsMissing() = runBlocking {
        engine.activeModel = { null }
        val e = try { engine.translate(req); null } catch (e: EngineException) { e }
        assertTrue(e?.failure is EngineFailure.ModelMissing)
        assertEquals(EngineId.LOCAL_USER, (e?.failure as EngineFailure.ModelMissing).engine)
    }

    @Test
    fun anActiveModelWhoseFileIsNotThereSaysTheModelIsMissing() = runBlocking {
        engine.activeModel = { UserModel("hmissing", "L", "user-hmissing.litertlm", "", 1000L, "a".repeat(64), 4096) }
        val e = try { engine.translate(req); null } catch (e: EngineException) { e }
        assertTrue(e?.failure is EngineFailure.ModelMissing)
    }

    @Test
    fun aReadyModelTranslatesThroughItsOwnEngine() = runBlocking {
        val m = readyModel()
        engine.activeModel = { m }
        val runner = ScriptedRunner(listOf("0: Good morning.").iterator())
        locals.forUser(m).runnerFactory = { runner }

        val out = engine.translate(req)

        assertEquals(listOf("Good morning."), out.map { it.text })
        // The prompt is the on-device one: numbered lines in, numbered lines out.
        assertTrue(runner.prompts.single().user.contains("0: こんにちは"))
    }

    @Test
    fun theEngineIsTextOnlyAndUsesTheContext() {
        assertEquals(EngineId.LOCAL_USER, engine.id)
        assertTrue(!engine.acceptsImages)
        assertTrue(engine.usesContext)
    }

    @Test
    fun aUsersModelGetsItsOwnEngineAndKeepsIt() {
        val m = readyModel()
        val a = locals.forUser(m)
        val b = locals.forUser(m)
        // The same model is the same engine, so its runtime is not loaded twice.
        assertTrue(a === b)
        // A different model is a different engine.
        assertTrue(a !== locals.forUser(readyModel("h999999999999")))
    }

    /** A runner that answers with fixed lines, like the other engine tests. */
    private class ScriptedRunner(private val replies: Iterator<String>) : LlmRunner {
        val prompts = ArrayList<ChatPrompt>()
        override suspend fun complete(prompt: ChatPrompt): String { prompts += prompt; return replies.next() }
    }
}
