package com.smnexstudio.panelglass.core.data.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemDownloadsIntegrityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val downloads = SystemDownloads(context)
    private val bytes = ByteArray(4096) { (it * 31).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun part(name: String) = File(downloads.root(), "models/$name.part").apply { parentFile?.mkdirs(); writeBytes(bytes) }

    @Test
    fun matchingDownloadIsMovedIntoPlaceAndRemembered() {
        val placed = downloads.place(part("ok.bin"), "models/ok.bin", sha)
        assertEquals(File(downloads.root(), "models/ok.bin"), placed)
        assertTrue(placed.readBytes().contentEquals(bytes))
        assertTrue(downloads.isVerified(placed, sha))
    }

    @Test
    fun mismatchingDownloadIsDeletedNeverPlaced() {
        val src = part("bad.bin")
        try {
            downloads.place(src, "models/bad.bin", "0".repeat(64)); fail("expected IOException")
        } catch (e: IOException) {
            assertEquals(SystemDownloads.CORRUPT, e.message)
        }
        assertFalse(src.exists())
        assertFalse(File(downloads.root(), "models/bad.bin").exists())
    }

    @Test
    fun fileChangedAfterCheckIsCheckedAgainAndDeleted() {
        val placed = downloads.place(part("swap.bin"), "models/swap.bin", sha)
        placed.writeBytes(ByteArray(4096))
        placed.setLastModified(placed.lastModified() + 5_000)
        assertFalse(downloads.isVerified(placed, sha))
        assertNull(downloads.adopt(placed, sha))
        assertFalse(placed.exists())
    }

    @Test
    fun uncheckedFileOnDiskIsAdoptedOnce() {
        val f = File(downloads.root(), "models/side.bin").apply { parentFile?.mkdirs(); writeBytes(bytes) }
        assertFalse(downloads.isVerified(f, sha))
        assertNotNull(downloads.adopt(f, sha))
        assertTrue(downloads.isVerified(f, sha))
    }
}
