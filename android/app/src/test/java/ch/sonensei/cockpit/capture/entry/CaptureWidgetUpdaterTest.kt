package ch.sonensei.cockpit.capture.entry

import ch.sonensei.cockpit.capture.queue.CaptureQueueStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
}
