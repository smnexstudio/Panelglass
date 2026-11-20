package com.smnexstudio.panelglass.feature.settings

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.data.backup.AppBackup
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.IOException
import com.smnexstudio.panelglass.core.model.GlossaryEntry
import com.smnexstudio.panelglass.core.model.Site
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Settings › Backup & restore through the view model, with real files: a backup is saved, read back and checked, and
 * merged only once the user confirms. Invented sites and terms only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsBackupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var rig: SettingsRig
    private lateinit var file: File

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        rig = SettingsRig(context)
        file = File(rig.root, "backup.json")
    }

    @After fun tearDown() {
        rig.close()
        Dispatchers.resetMain()
    }

    private suspend fun SettingsViewModel.settled(): BackupUi =
        withTimeout(5_000) { backup.first { it !is BackupUi.Working && it !is BackupUi.Idle } }

    @Test fun aSavedBackupIsCheckedAndAppliedOnlyAfterConfirming() = runBlocking<Unit> {
        rig.db.sites().upsert(Site(name = "Reader", url = "https://reader.example/"))
        rig.glossary.upsert(GlossaryEntry(seriesKey = "reader.example", source = "ハル", target = "Haru"))
        val vm = rig.vm()

        vm.saveBackup(Uri.fromFile(file))
        val saved = vm.settled() as BackupUi.Saved
        assertEquals(1, saved.summary.sites)
        assertTrue(file.readText().contains("panelglass-backup"))

        // The phone's own data changes after the backup; the restore adds back what the file holds.
        rig.db.sites().delete(rig.db.sites().all().single())
        vm.openBackup(Uri.fromFile(file))
        val pending = vm.settled() as BackupUi.Confirm
        assertEquals(1, pending.summary.sites)
        assertTrue("nothing is applied before Restore", rig.db.sites().all().isEmpty())

        vm.confirmRestore()
        val restored = vm.settled() as BackupUi.Restored
        assertEquals(1, restored.summary.sites)
        assertEquals(listOf("https://reader.example/"), rig.db.sites().all().map { it.url })
    }

    @Test fun cancellingAPendingRestoreChangesNothing() = runBlocking<Unit> {
        file.writeText("""{"format":"panelglass-backup","version":1,"sites":[{"name":"New","url":"https://new.example/"}]}""")
        val vm = rig.vm()
        vm.openBackup(Uri.fromFile(file))
        vm.settled() as BackupUi.Confirm
        vm.dismissBackup()
        assertEquals(BackupUi.Idle, vm.backup.value)
        assertTrue(rig.db.sites().all().isEmpty())
    }

    @Test fun aBackupThatCannotBeWrittenSaysSo() = runBlocking<Unit> {
        val vm = rig.vm()
        // A folder is no file: nothing can be written there.
        vm.saveBackup(Uri.fromFile(rig.root))
        assertEquals(BackupUi.Failed(BackupFailure.WRITE), vm.settled())
    }

    @Test fun aRestoreThePhoneCannotSaveIsNotBlamedOnTheFile() = runBlocking<Unit> {
        file.writeText("""{"format":"panelglass-backup","version":1,"translationNotes":"Short lines."}""")
        // The app's own storage refuses the write: the file was fine.
        val vm = rig.vm(AppBackup(rig.db, SettingsRepository(UnwritableStore)))
        vm.openBackup(Uri.fromFile(file))
        vm.settled() as BackupUi.Confirm
        vm.confirmRestore()
        assertEquals(BackupUi.Failed(BackupFailure.RESTORE), vm.settled())
    }

    /** Preferences that read as empty and refuse every write, as a full or failing disk would. */
    private object UnwritableStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("disk full")
    }

    @Test fun aFileThatIsNoBackupOrFromANewerVersionIsRefused() = runBlocking<Unit> {
        val vm = rig.vm()
        file.writeText("these are not the bytes you are looking for")
        vm.openBackup(Uri.fromFile(file))
        assertEquals(BackupUi.Failed(BackupFailure.UNREADABLE), vm.settled())

        vm.dismissBackup()
        file.writeText("""{"format":"panelglass-backup","version":99}""")
        vm.openBackup(Uri.fromFile(file))
        assertEquals(BackupUi.Failed(BackupFailure.NEWER), vm.settled())

        vm.dismissBackup()
        vm.openBackup(Uri.fromFile(File(rig.root, "missing.json")))
        assertEquals(BackupUi.Failed(BackupFailure.UNREADABLE), vm.settled())
    }
}
