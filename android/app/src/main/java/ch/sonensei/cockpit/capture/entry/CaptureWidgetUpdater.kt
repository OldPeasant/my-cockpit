package ch.sonensei.cockpit.capture.entry

import android.content.Context
import androidx.glance.appwidget.updateAll
import ch.sonensei.cockpit.capture.queue.CaptureQueue
import ch.sonensei.cockpit.capture.queue.CaptureQueueStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Redraws the placed capture widgets whenever the queue's pending count or failure changes, so the count is right also
 * after the upload job sent the queue while no widget session was running. Captures and uploads only happen in the app
 * process, where the application starts this, so every change is seen.
 */
@Singleton
class CaptureWidgetUpdater internal constructor(
    private val status: Flow<CaptureQueueStatus>,
    private val refresh: suspend () -> Unit,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(
        queue: CaptureQueue,
        @ApplicationContext context: Context,
    ) : this(queue.status, { CaptureWidget().updateAll(context) }, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private var job: Job? = null

    /** Starts following the queue; a second call does nothing. The first status also refreshes (the process restarted). */
    @Synchronized
    fun start() {
        if (job != null) return
        job =
            scope.launch {
                status.distinctUntilChanged().collect {
                    try {
                        refresh()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // A widget host that is gone or busy must not stop the updates: the next change tries again.
                    }
                }
            }
    }
}
