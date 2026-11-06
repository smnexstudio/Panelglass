package com.smnexstudio.panelglass.feature.studio.translate

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.smnexstudio.panelglass.core.ui.R as UiR

/**
 * The Studio run's notification: the page in progress or why the run waits, its progress bar, Pause or Resume and
 * Cancel, and a tap that opens the chapter. Robolectric (JUnit 4 via vintage).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RunNotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    @Test fun theStatusNamesThePageInProgress() {
        assertEquals(s(UiR.string.studio_reading_progress, 3, 20), RunNotifications.status(context, ChapterRun(1, RunPhase.READING, 2, 20)))
        assertEquals(s(UiR.string.studio_translating_progress, 7, 20), RunNotifications.status(context, ChapterRun(1, RunPhase.TRANSLATING, 6, 20)))
        assertEquals(s(UiR.string.studio_cleaning_progress, 1, 20), RunNotifications.status(context, ChapterRun(1, RunPhase.CLEANING, 0, 20)))
    }

    /** Why the run waits comes first; a pause asked for shows at once, before the page in progress ends. */
    @Test fun theStatusSaysWhyTheRunWaits() {
        val run = ChapterRun(1, RunPhase.TRANSLATING, 6, 20)
        assertEquals(s(UiR.string.studio_paused_hot), RunNotifications.status(context, run.copy(paused = PauseReason.HOT)))
        assertEquals(s(UiR.string.studio_paused_battery, RunRules.LOW_BATTERY), RunNotifications.status(context, run.copy(paused = PauseReason.BATTERY_LOW)))
        assertEquals(s(UiR.string.studio_pausing), RunNotifications.status(context, run, userPaused = true))
        for (r in PauseReason.entries) assertTrue(r.name, RunNotifications.pausedText(context, r).isNotBlank())
    }

    @Test fun aWorkingRunOffersPauseAndCancelWithItsProgress() {
        val n = RunNotifications.build(context, ChapterRun(1, RunPhase.TRANSLATING, 6, 20), "Stardust · Chapter 12")
        assertEquals("Stardust · Chapter 12", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(s(UiR.string.studio_translating_progress, 7, 20), text(n))
        assertEquals(20, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(6, n.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(listOf(s(UiR.string.action_pause), s(UiR.string.action_cancel)), n.actions.map { it.title.toString() })
        assertTrue("it stays while the run works", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test fun aPausedRunOffersResume() {
        val n = RunNotifications.build(context, ChapterRun(1, RunPhase.TRANSLATING, 6, 20), "x", userPaused = true)
        assertEquals(s(UiR.string.action_resume), n.actions.first().title.toString())
        val actions = n.actions.map { shadowOf(it.actionIntent).savedIntent.action }
        assertEquals(listOf(StudioRunService.ACTION_RESUME, StudioRunService.ACTION_CANCEL), actions)
    }

    /** A tap opens the app on the chapter. */
    @Test fun aTapOpensTheChapter() {
        // The app's launcher activity, as the merged manifest declares it in the app.
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        shadowOf(context.packageManager).addResolveInfoForIntent(launcher, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = context.packageName; name = "com.smnexstudio.panelglass.MainActivity" }
        })
        val n = RunNotifications.build(context, ChapterRun(42, RunPhase.READING, 0, 5), "x")
        assertNotNull(n.contentIntent)
        assertEquals(42L, shadowOf(n.contentIntent).savedIntent.getLongExtra(StudioRunService.EXTRA_CHAPTER, -1))
    }

    /** A quiet channel: progress, never a sound. */
    @Test fun theChannelIsQuiet() {
        RunNotifications.createChannel(context)
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(RunNotifications.CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals(s(UiR.string.studio_run_channel), channel.name.toString())
    }
}
