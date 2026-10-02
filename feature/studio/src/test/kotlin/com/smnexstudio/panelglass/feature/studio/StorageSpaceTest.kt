package com.smnexstudio.panelglass.feature.studio

import android.system.ErrnoException
import android.system.OsConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** The free space rules imports and exports share. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageSpaceTest {
    @Test fun aJobNeedsItsSizePlusTheReserve() {
        val mb = 1024L * 1024
        assertTrue(StorageSpace.enough(10 * mb, StorageSpace.RESERVE + 10 * mb))
        assertFalse(StorageSpace.enough(10 * mb, StorageSpace.RESERVE + 9 * mb))
        assertFalse("the reserve alone is kept", StorageSpace.enough(0, StorageSpace.RESERVE - 1))
        assertTrue("an unknown free space is not refused", StorageSpace.enough(10 * mb, null))
    }

    @Test fun aFullStorageIsRecognisedThroughCauses() {
        assertTrue(StorageSpace.isFull(IOException("write failed", ErrnoException("write", OsConstants.ENOSPC))))
        assertTrue(StorageSpace.isFull(IOException("write failed: ENOSPC (No space left on device)")))
        assertFalse(StorageSpace.isFull(IOException("write failed", ErrnoException("write", OsConstants.EACCES))))
        assertFalse(StorageSpace.isFull(IOException("Broken pipe")))
        assertFalse(StorageSpace.isFull(null))
    }
}
