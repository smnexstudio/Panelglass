package com.smnexstudio.panelglass.core.data.download

import android.app.DownloadManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * The bookkeeping around Android's DownloadManager (Robolectric's): a download is remembered by key across app
 * restarts, queued once, forgotten on cancel, and a download the system lost is reported, not waited on forever.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemDownloadsQueueTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dm = context.getSystemService(DownloadManager::class.java)
    private val url = "https://huggingface.co/x/y/resolve/0000000000000000000000000000000000000000/model.bin"

    private fun queued(): Int = dm.query(DownloadManager.Query()).use { it.count }

    @Test fun aDownloadIsQueuedOnceAndRememberedAcrossRestarts() {
        val a = SystemDownloads(context)
        a.start("model", url, "models/model.bin", "Invented model")
        a.start("model", url, "models/model.bin", "Invented model")
        assertEquals("starting a queued key is a no-op", 1, queued())
        assertTrue(a.isActive("model"))
        assertTrue("a new instance (the app restarted) still knows it", SystemDownloads(context).isActive("model"))
        assertFalse(SystemDownloads(context).isActive("other"))
    }

    /** The file is written as `…part` until it is checked, so a half-written model is never loaded. */
    @Test fun aStaleHalfFileIsClearedWhenADownloadStarts() {
        val d = SystemDownloads(context)
        val part = File(d.root(), "models/model.bin.part").apply { parentFile!!.mkdirs(); writeText("half of an earlier attempt") }
        d.start("model", url, "models/model.bin", "Invented model")
        assertFalse(part.exists())
    }

    /**
     * The system writes `name-1.part` when `name.part` is taken; copies left by downloads nobody follows any more are
     * cleared when the model is queued again, and nothing else in the folder is touched.
     */
    @Test fun strayNumberedPartsAreClearedWhenADownloadStarts() {
        val d = SystemDownloads(context)
        val dir = File(d.root(), "models").apply { mkdirs() }
        val strays = listOf("model.bin-1.part", "model.bin-2.part").map { File(dir, it).apply { writeText("an earlier copy") } }
        val other = File(dir, "other.bin-1.part").apply { writeText("another model's download") }
        d.start("model", url, "models/model.bin", "Invented model")
        strays.forEach { assertFalse(it.name, it.exists()) }
        assertTrue(other.exists())
    }

    /** Waiting downloads at one address (ML Kit's packs) are removed; the app's own models are left alone. */
    @Test fun waitingDownloadsAtAnAddressAreDropped() {
        val d = SystemDownloads(context)
        d.start("model", url, "models/model.bin", "Invented model")
        dm.enqueue(DownloadManager.Request(android.net.Uri.parse("https://packs.example.org/edgedl/translate/offline/aa_bb.zip")))
        assertEquals(2, queued())
        assertEquals(1, d.dropWaiting("/edgedl/translate/"))
        assertEquals(1, queued())
        assertTrue("the model's own download is kept", d.isActive("model"))
    }

    @Test fun cancelRemovesTheSystemDownloadAndForgetsIt() {
        val d = SystemDownloads(context)
        d.start("model", url, "models/model.bin", "Invented model")
        d.cancel("model")
        assertFalse(d.isActive("model"))
        assertEquals(0, queued())
        d.cancel("never-started") // harmless
    }

    @Test fun followingADownloadThatWasNeverStartedFails() = runBlocking<Unit> {
        try { SystemDownloads(context).await("nothing", "models/x.bin", 1, "0".repeat(64)) {}; fail("expected an IOException") } catch (e: IOException) {
            assertEquals("No download", e.message)
        }
    }

    /** The user cleared it in the system's Downloads app: reported as cancelled and forgotten, never awaited forever. */
    @Test fun aDownloadTheSystemNoLongerHasIsReportedAndForgotten() = runBlocking<Unit> {
        val d = SystemDownloads(context)
        d.start("model", url, "models/model.bin", "Invented model")
        dm.query(DownloadManager.Query()).use { c -> c.moveToFirst(); dm.remove(c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))) }
        try { d.await("model", "models/model.bin", 1, "0".repeat(64)) {}; fail("expected an IOException") } catch (e: IOException) {
            assertEquals("Download was cancelled", e.message)
        }
        assertFalse(d.isActive("model"))
    }
}
