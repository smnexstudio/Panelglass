package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.engine.local.HfCatalog
import com.smnexstudio.panelglass.core.engine.local.UserModelLibrary
import com.smnexstudio.panelglass.core.model.UserModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The user's model library: what is saved, what is active, and what a corrupt store reads as.
 * Robolectric (JUnit 4 via vintage): the library needs a Context.
 */
@RunWith(RobolectricTestRunner::class)
class UserModelLibraryTest {
    private val ds = MemoryDataStore()
    private val settings = testSettings(ds)
    private val catalog = HfCatalog(testSupport())

    private fun model(id: String = "h0123456789ab") = UserModel(
        id = id, label = "Qwen 2.5 1.5B", fileName = UserModel.fileNameFor(id),
        url = "https://huggingface.co/a/b/resolve/c/d.litertlm", bytes = 1_000L,
        sha256 = "a".repeat(64), maxTokens = 4096, source = "litert-community/Qwen",
    )

    @Test
    fun theLibraryIsSavedAndReadBack() = runBlocking {
        settings.saveUserModels(listOf(model()))
        assertEquals(listOf("h0123456789ab"), settings.userModels.first().map { it.id })
    }

    @Test
    fun anEmptyLibraryIsSavedAsNone() = runBlocking {
        settings.saveUserModels(listOf(model()))
        settings.saveUserModels(emptyList())
        assertTrue(settings.userModels.first().isEmpty())
    }

    @Test
    fun corruptJsonReadsAsAnEmptyLibrary() = runBlocking {
        settings.saveUserModels(listOf(model()))
        // A half-written value from an older version.
        ds.updateData { it.toMutablePreferences().apply { set(androidx.datastore.preferences.core.stringPreferencesKey("userModels"), "{not json") } }
        assertTrue(settings.userModels.first().isEmpty())
    }

    @Test
    fun theActiveModelIsSetAndCleared() = runBlocking {
        settings.saveUserModels(listOf(model()))
        settings.setActiveUserModel("h0123456789ab")
        assertEquals("h0123456789ab", settings.current().activeUserModelId)
        assertEquals("h0123456789ab", settings.activeUserModel()?.id)
        settings.setActiveUserModel("")
        assertEquals("", settings.current().activeUserModelId)
        assertNull(settings.activeUserModel())
    }

    @Test
    fun anActiveModelThatIsGoneReadsAsNoModel() = runBlocking {
        settings.saveUserModels(listOf(model()))
        settings.setActiveUserModel("h0123456789ab")
        settings.saveUserModels(emptyList())
        assertNull(settings.activeUserModel())
    }

    @Test
    fun theSameModelTwiceIsRefused() = runBlocking {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val library = UserModelLibrary(
            ctx, settings, testModelStores(ctx), testLocalEngines(ctx), testDownloads(ctx), catalog,
        )
        val listing = HfCatalog.HfListing("litert-community/Qwen", "sha", emptyList())
        val file = HfCatalog.HfFile("m.litertlm", 1000L, "a".repeat(64))
        val first = library.addFromHub(listing, file, "Qwen", 4096)
        assertTrue(first.isSuccess)
        val second = library.addFromHub(listing, file, "Qwen", 4096)
        assertTrue(second.isFailure)
        assertEquals(UserModelLibrary.ALREADY_ADDED, second.exceptionOrNull()?.message)
    }

    @Test
    fun onlyAHubAddressIsEverDownloaded() = runBlocking {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val library = UserModelLibrary(
            ctx, settings, testModelStores(ctx), testLocalEngines(ctx), testDownloads(ctx), catalog,
        )
        // A listing whose repository id would push the address off the hub.
        val listing = HfCatalog.HfListing("../../evil", "sha", emptyList())
        val file = HfCatalog.HfFile("m.litertlm", 1000L, "a".repeat(64))
        val r = library.addFromHub(listing, file, "x", 2048)
        assertTrue(r.isFailure)
    }
}
