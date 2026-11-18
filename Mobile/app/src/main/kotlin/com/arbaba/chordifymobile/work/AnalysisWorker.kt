package com.arbaba.chordifymobile.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.arbaba.chordifymobile.ChordifyApp
import com.arbaba.chordifymobile.data.JobScheduler
import com.arbaba.chordifymobile.data.Status
import com.arbaba.chordifymobile.engine.CancelledException
import com.arbaba.chordifymobile.engine.ChordifyEngine
import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.analysis.AnalysisOptions
import com.arbaba.chordifymobile.engine.analysis.SourceInfo
import com.arbaba.chordifymobile.engine.analysis.Stage
import com.arbaba.chordifymobile.engine.audio.AudioSource
import com.arbaba.chordifymobile.ui.stageLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The engine, loaded once per process on first use, and the lock that runs one analysis at a time. */
class EngineHolder(private val context: Context) {
    private val lock = Mutex()
    private var engine: ChordifyEngine? = null

    suspend fun <T> withEngine(block: suspend (ChordifyEngine) -> T): T = lock.withLock {
        val loaded = engine ?: withContext(Dispatchers.Default) { ChordifyEngine.fromAssets(context) }.also { engine = it }
        block(loaded)
    }
}

/** One analysis as a WorkManager job (spec §6.1): queued jobs wait for the engine, the running one
 *  is a foreground service with a progress notification, and progress goes to the library's row.
 *  WorkManager runs a job killed with the process again; cancelling it stops the engine. */
class AnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val container = (applicationContext as ChordifyApp).container
        val library = container.library
        return container.engine.withEngine { engine ->
            val row = library.get(id)
            if (row == null || row.status in Status.FINISHED) return@withEngine Result.success()
            val notification = AnalysisNotification(applicationContext, id, row.displayName)
            foreground(notification.info(null, 0.0))
            val progress = MutableStateFlow(Stage.DECODE to 0.0)
            try {
                val result = coroutineScope {
                    val reporter = launch {
                        var shown = -1
                        progress.collect { (stage, fraction) ->
                            library.reportProgress(id, stage.id, fraction)
                            val percent = (fraction * 100).roundToInt()
                            if (percent >= shown + 5 || stage == Stage.DONE) {
                                shown = percent
                                foreground(notification.info(stage, fraction))
                            }
                        }
                    }
                    withContext(Dispatchers.Default) {
                        engine.analyse(
                            AudioSource.FromUri(applicationContext, Uri.parse(row.fileUri)),
                            SourceInfo(id, row.displayName, row.sha256),
                            AnalysisOptions(row.vocabulary, row.predictions, row.minSegmentBeats),
                            onProgress = { stage, fraction -> progress.value = stage to fraction },
                            isCancelled = { isStopped },
                        )
                    }.also { reporter.cancel() }
                }
                library.reportCompleted(id, result)
                Result.success()
            } catch (_: CancelledException) {
                stopped(id)
                Result.failure()
            } catch (error: CancellationException) {
                stopped(id)
                throw error
            } catch (error: EngineException) {
                library.reportFailed(id, error.code.name, error.message ?: error.code.name)
                Result.failure()
            } catch (error: Exception) { // reported as the server reports an unexpected failure
                library.reportFailed(id, "INTERNAL", "${error.javaClass.simpleName}: ${error.message}")
                Result.failure()
            }
        }
    }

    /** Cancelled by the user: the row says so. Stopped by the system: WorkManager runs it again. */
    private suspend fun stopped(id: String) = withContext(NonCancellable) {
        if (stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) {
            (applicationContext as ChordifyApp).container.library.reportCancelled(id)
        }
    }

    /** A job started while the app is in the background may not become a foreground service; it
     *  then runs as an ordinary job. */
    private suspend fun foreground(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (_: IllegalStateException) {
        }
    }

    companion object {
        const val KEY_ID = "analysis_id"
    }
}

/** Starts and cancels AnalysisWorker jobs, one unique job per analysis. */
class WorkJobScheduler(context: Context) : JobScheduler {
    private val work = WorkManager.getInstance(context)

    override fun start(id: String) {
        val request = OneTimeWorkRequestBuilder<AnalysisWorker>().setInputData(workDataOf(AnalysisWorker.KEY_ID to id)).build()
        work.enqueueUniqueWork(name(id), ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel(id: String) {
        work.cancelUniqueWork(name(id))
    }

    private fun name(id: String) = "analysis-$id"
}

/** The progress notification of the running analysis. */
class AnalysisNotification(private val context: Context, id: String, private val title: String) {
    private val notificationId = id.hashCode()

    fun info(stage: Stage?, fraction: Double): ForegroundInfo {
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(stageLabel(stage?.id))
            .setProgress(100, (fraction * 100).roundToInt(), stage == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return ForegroundInfo(notificationId, notification, type)
    }

    companion object {
        const val CHANNEL = "analyses"

        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL, "Analyses", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Progress of the song being analysed"
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
