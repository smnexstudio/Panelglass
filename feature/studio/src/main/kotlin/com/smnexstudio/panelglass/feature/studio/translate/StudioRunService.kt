package com.smnexstudio.panelglass.feature.studio.translate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.smnexstudio.panelglass.core.data.repo.StudioRepository
import com.smnexstudio.panelglass.feature.studio.R
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import com.smnexstudio.panelglass.core.ui.R as UiR

/**
 * Keeps a Studio run alive while the app is in the background: a foreground service for as long as a chapter run is
 * active, with its progress in a notification (Pause or Resume, Cancel; a tap opens the chapter). It holds a partial
 * wake lock while the run works, so it goes on with the screen off, and lets it go while the run waits. It only hosts
 * the run: [StudioTranslator] does the work, and the service stops itself when no run is active.
 */
@AndroidEntryPoint
class StudioRunService : Service() {
    @Inject lateinit var translator: StudioTranslator
    @Inject lateinit var repo: StudioRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var watching = false
    private val titles = HashMap<Long, String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        RunNotifications.createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start must be answered with startForeground, the action ones too (Android stops an app that does not).
        val run = translator.runs.value.values.firstOrNull { it.active }
        ServiceCompat.startForeground(
            this, RunNotifications.ID,
            RunNotifications.build(this, run, titles[run?.chapterId].orEmpty(), translator.userPaused.value), FOREGROUND_TYPE,
        )
        val chapterId = run?.chapterId
        when (intent?.action) {
            ACTION_PAUSE -> translator.pause()
            ACTION_RESUME -> translator.resume()
            ACTION_CANCEL -> chapterId?.let(translator::cancel)
        }
        if (!watching) {
            watching = true
            scope.launch {
                combine(translator.runs, translator.userPaused) { runs, paused -> runs.values.firstOrNull { it.active } to paused }
                    .distinctUntilChanged().collect { (run, paused) -> show(run, paused) }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun show(run: ChapterRun?, userPaused: Boolean) {
        if (run == null) {
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (run.paused == null) holdWakeLock() else releaseWakeLock()
        val title = titles.getOrPut(run.chapterId) { titleOf(run.chapterId) }
        getSystemService(NotificationManager::class.java).notify(RunNotifications.ID, RunNotifications.build(this, run, title, userPaused))
    }

    private suspend fun titleOf(chapterId: Long): String {
        val chapter = repo.getChapter(chapterId) ?: return ""
        val manga = repo.getManga(chapter.mangaId)?.title.orEmpty()
        val number = chapter.numberLabel?.let { getString(UiR.string.studio_chapter_label, it) }
        return listOfNotNull(manga.takeIf { it.isNotBlank() }, number ?: chapter.title.takeIf { it.isNotBlank() }).joinToString(" · ")
    }

    private fun holdWakeLock() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Panelglass:studio-run").also { it.setReferenceCounted(false); wakeLock = it }
        // Renewed at every page; the timeout only matters if this service were stopped without onDestroy.
        lock.acquire(WAKE_LOCK_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PAUSE = "com.smnexstudio.panelglass.studio.PAUSE"
        const val ACTION_RESUME = "com.smnexstudio.panelglass.studio.RESUME"
        const val ACTION_CANCEL = "com.smnexstudio.panelglass.studio.CANCEL"
        /** Extra on the launch intent: the chapter whose run the notification shows. */
        const val EXTRA_CHAPTER = "studio_chapter"
        private const val WAKE_LOCK_MS = 30 * 60 * 1000L
        private val FOREGROUND_TYPE =
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
    }
}

/** Starts [StudioRunService] when a run starts: the user just tapped Translate, so the app is in the foreground. */
@Singleton
class StudioRunStarter @Inject constructor(@ApplicationContext private val context: Context) : RunHost {
    override fun started(chapterId: Long) {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, StudioRunService::class.java)) }
    }
}

/** The run's notification: what it is doing, or why it waits, with its progress and actions. */
internal object RunNotifications {
    const val ID = 4201
    const val CHANNEL = "studio_runs"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, context.getString(UiR.string.studio_run_channel), NotificationManager.IMPORTANCE_LOW)
            .apply { description = context.getString(UiR.string.studio_run_channel_note) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** The line under the title: the page in progress, why the run waits, or that it pauses after this page. */
    fun status(context: Context, run: ChapterRun?, userPaused: Boolean = false): String = when {
        run == null -> context.getString(UiR.string.studio_waiting)
        run.paused != null -> pausedText(context, run.paused)
        userPaused -> context.getString(UiR.string.studio_pausing)
        run.phase == RunPhase.READING -> context.getString(UiR.string.studio_reading_progress, run.done + 1, run.total)
        run.phase == RunPhase.TRANSLATING -> context.getString(UiR.string.studio_translating_progress, run.done + 1, run.total)
        run.phase == RunPhase.CLEANING -> context.getString(UiR.string.studio_cleaning_progress, run.done + 1, run.total)
        else -> context.getString(UiR.string.studio_waiting)
    }

    fun pausedText(context: Context, reason: PauseReason): String = when (reason) {
        PauseReason.USER -> context.getString(UiR.string.studio_paused)
        PauseReason.HOT -> context.getString(UiR.string.studio_paused_hot)
        PauseReason.CHARGER -> context.getString(UiR.string.studio_paused_charger)
        PauseReason.BATTERY_SAVER -> context.getString(UiR.string.studio_paused_saver)
        PauseReason.BATTERY_LOW -> context.getString(UiR.string.studio_paused_battery, RunRules.LOW_BATTERY)
        PauseReason.OFFLINE -> context.getString(UiR.string.studio_paused_offline)
        PauseReason.READER -> context.getString(UiR.string.studio_paused_reader)
    }

    fun build(context: Context, run: ChapterRun?, title: String, userPaused: Boolean = false): Notification {
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { run?.let { putExtra(StudioRunService.EXTRA_CHAPTER, it.chapterId) }; addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_studio_run)
            .setContentTitle(title.ifBlank { context.getString(UiR.string.studio_run_channel) })
            .setContentText(status(context, run, userPaused))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        open?.let { builder.setContentIntent(PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)) }
        if (run != null) {
            if (run.total > 0) builder.setProgress(run.total, run.done, false) else builder.setProgress(0, 0, true)
            val paused = userPaused || run.paused == PauseReason.USER
            builder.addAction(
                0, context.getString(if (paused) UiR.string.action_resume else UiR.string.action_pause),
                action(context, if (paused) StudioRunService.ACTION_RESUME else StudioRunService.ACTION_PAUSE, 1),
            )
            builder.addAction(0, context.getString(UiR.string.action_cancel), action(context, StudioRunService.ACTION_CANCEL, 2))
        }
        return builder.build()
    }

    private fun action(context: Context, name: String, code: Int): PendingIntent = PendingIntent.getService(
        context, code, Intent(context, StudioRunService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
