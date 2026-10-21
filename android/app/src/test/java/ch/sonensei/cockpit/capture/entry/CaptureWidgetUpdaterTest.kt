package ch.sonensei.cockpit.capture.entry

import ch.sonensei.cockpit.capture.queue.CaptureQueueStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureWidgetUpdaterTest {
    private val status = MutableStateFlow(CaptureQueueStatus(0, null))

    @Test
    fun `refreshes on start and on every change of the queue status`() =
        runTest(UnconfinedTestDispatcher()) {
            var refreshes = 0
            CaptureWidgetUpdater(status, { refreshes++ }, backgroundScope).start()
            assertEquals(1, refreshes)

            status.value = CaptureQueueStatus(1, null)
            status.value = CaptureQueueStatus(1, "offline")
            status.value = CaptureQueueStatus(0, null)
            assertEquals(4, refreshes)
        }

    @Test
    fun `an unchanged queue status does not redraw the widgets again`() =
        runTest(UnconfinedTestDispatcher()) {
            // Room re-emits on every write to the table, also when count and failure stay the same.
            val emissions = MutableSharedFlow<CaptureQueueStatus>()
            var refreshes = 0
            CaptureWidgetUpdater(emissions, { refreshes++ }, backgroundScope).start()

            emissions.emit(CaptureQueueStatus(1, null))
            emissions.emit(CaptureQueueStatus(1, null))
            assertEquals(1, refreshes)

            emissions.emit(CaptureQueueStatus(1, "offline"))
            emissions.emit(CaptureQueueStatus(1, "offline"))
            assertEquals(2, refreshes)
        }

    @Test
    fun `starting twice follows the queue once`() =
        runTest(UnconfinedTestDispatcher()) {
            var refreshes = 0
            val updater = CaptureWidgetUpdater(status, { refreshes++ }, backgroundScope)
            updater.start()
            updater.start()

            status.value = CaptureQueueStatus(2, null)
            assertEquals(2, refreshes)
        }

    @Test
    fun `a failing refresh does not stop later refreshes`() =
        runTest(UnconfinedTestDispatcher()) {
            var calls = 0
            CaptureWidgetUpdater(status, {
                calls++
                if (calls == 1) error("widget host gone")
            }, backgroundScope).start()

            status.value = CaptureQueueStatus(3, null)
            assertEquals(2, calls)
        }

    @Test
    fun `a failing queue read is retried with backoff and the updates resume`() =
        runTest(UnconfinedTestDispatcher()) {
            var reads = 0
            val failing =
                flow {
                    reads++
                    if (reads <= 2) throw IllegalStateException("database not available")
                    emitAll(status)
                }
            var refreshes = 0
            CaptureWidgetUpdater(failing, { refreshes++ }, backgroundScope).start()
            assertEquals(1, reads)
            assertEquals(0, refreshes)

            advanceTimeBy(WIDGET_RETRY_FIRST_DELAY_MILLIS + 1)
            assertEquals(2, reads)
            assertEquals(0, refreshes)

            advanceTimeBy(2 * WIDGET_RETRY_FIRST_DELAY_MILLIS + 1)
            assertEquals(3, reads)
            assertEquals(1, refreshes)

            status.value = CaptureQueueStatus(5, null)
            assertEquals(2, refreshes)
        }

    @Test
    fun `cancelling the scope stops the updates instead of being swallowed as a failed refresh`() =
        runTest(UnconfinedTestDispatcher()) {
            val scope = CoroutineScope(coroutineContext + Job())
            var refreshes = 0
            CaptureWidgetUpdater(status, { refreshes++ }, scope).start()
            assertEquals(1, refreshes)

            scope.cancel()
            status.value = CaptureQueueStatus(7, null)
            assertEquals(1, refreshes)
        }

    @Test
    fun `the retry delay doubles up to one minute`() {
        assertEquals(1_000L, retryDelayMillis(0))
        assertEquals(2_000L, retryDelayMillis(1))
        assertEquals(32_000L, retryDelayMillis(5))
        assertEquals(WIDGET_RETRY_MAX_DELAY_MILLIS, retryDelayMillis(6))
        assertEquals(WIDGET_RETRY_MAX_DELAY_MILLIS, retryDelayMillis(Long.MAX_VALUE))
    }
}
