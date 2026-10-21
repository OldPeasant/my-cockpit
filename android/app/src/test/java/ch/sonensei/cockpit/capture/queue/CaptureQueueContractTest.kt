package ch.sonensei.cockpit.capture.queue

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.time.ZoneOffset

/**
 * The contract of [CaptureQueue.capture] (AC1 "capture always writes locally first"): once a capture is stored the caller
 * gets its id even if the upload job cannot be scheduled, and input the server always rejects (text over
 * [CaptureQueue.MAX_TEXT_LENGTH] after pinning `!today`/`!tomorrow`) is refused before anything is copied or queued.
 */
@RunWith(AndroidJUnit4::class)
class CaptureQueueContractTest {
    private val server = MockWebServer()
    private lateinit var q: QueueHarness

    @Before
    fun setUp() {
        server.start()
        q = QueueHarness(server)
        // Offline: capturing never needs the network.
        server.close()
    }

    @After
    fun tearDown() {
        q.close()
    }

    /** Fails the first [failures] schedule calls with [error], then records successful ones. */
    private class FlakyScheduler(
        private var failures: Int,
        private val error: () -> Throwable,
    ) : CaptureSyncScheduler {
        var attempts = 0
        var scheduled = 0

        override suspend fun schedule() {
            attempts++
            if (failures > 0) {
                failures--
                throw error()
            }
            scheduled++
        }
    }

    /** The harness queue with UTC as the device zone, so pinned dates are 2026-10-20 regardless of the machine. */
    private fun queue(scheduler: CaptureSyncScheduler = q.scheduler) = CaptureQueue(q.dao, q.store, scheduler, q.clock) { ZoneOffset.UTC }

    private fun copiesOf(id: String): List<File> = File(q.root, id).listFiles()?.toList().orEmpty()

    private suspend fun assertNothingQueuedOrCopied() {
        assertTrue("nothing is queued", q.dao.sendable().isEmpty())
        assertFalse("no copies are left", q.root.exists() && q.root.listFiles()!!.any { it.listFiles()!!.isNotEmpty() })
    }

    /** A title line of exactly [length] characters, containing [token] (if any) after a short prefix. */
    private fun textOfLength(
        length: Int,
        token: String = "",
    ): String {
        val head = if (token.isEmpty()) "Plan " else "Plan $token "
        return head + "x".repeat(length - head.length)
    }

    // --- scheduling failures after the commit (CR-1) ---

    @Test
    fun `capture returns the stored id without an exception when scheduling the job fails`() =
        runBlocking {
            listOf<() -> Throwable>(
                { IllegalStateException("WorkManager is not initialized") },
                { RuntimeException("binder died") },
                { IOException("cannot persist work") },
            ).forEachIndexed { n, error ->
                val scheduler = FlakyScheduler(1, error)

                val attachment = CaptureAttachment(q.source("a.txt", "kept $n"))

                val id = queue(scheduler).capture("Fictitious capture $n", null, listOf(attachment))

                assertEquals(1, scheduler.attempts)
                val pending = q.dao.sendable().single { it.capture.id == id.toString() }
                assertEquals("Fictitious capture $n", pending.capture.text)
                assertEquals("kept $n", File(pending.attachments.single().localPath).readText())
            }
            assertEquals(3, q.dao.sendable().size)
        }

    @Test
    fun `after a failed schedule the next capture schedules the job again and both stay queued`() =
        runBlocking {
            val scheduler = FlakyScheduler(1) { IllegalStateException("not yet") }
            val queue = queue(scheduler)

            val first = queue.capture("First, unscheduled")
            val second = queue.capture("Second, schedules")

            assertEquals(2, scheduler.attempts)
            assertEquals(1, scheduler.scheduled)
            assertEquals(listOf(first.toString(), second.toString()), q.dao.sendable().map { it.capture.id })
        }

    @Test
    fun `a cancellation thrown by the scheduler still propagates and the stored capture keeps its copies`() =
        runBlocking {
            val scheduler = FlakyScheduler(1) { CancellationException("worker stopped") }

            try {
                queue(scheduler).capture("Stored before the cancel", null, listOf(CaptureAttachment(q.source("a.txt", "x"))))
                fail("expected the CancellationException to propagate")
            } catch (_: CancellationException) {
            }

            val pending = q.dao.sendable().single()
            assertEquals("Stored before the cancel", pending.capture.text)
            assertEquals("the copies of a stored capture are not deleted", listOf("0"), copiesOf(pending.capture.id).map { it.name })
        }

    @Test
    fun `a caller cancelled while the job is being scheduled keeps the capture queued and the schedule finishes`() =
        runBlocking {
            val scheduling = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var scheduled = 0
            val scheduler =
                object : CaptureSyncScheduler {
                    override suspend fun schedule() {
                        scheduling.complete(Unit)
                        release.await()
                        scheduled++
                    }
                }

            val attachments = listOf(CaptureAttachment(q.source("a.txt", "y")))
            val job = launch(Dispatchers.IO) { queue(scheduler).capture("Cancelled while scheduling", null, attachments) }
            scheduling.await()
            job.cancel()
            release.complete(Unit)
            job.join()

            assertEquals("the schedule ran to the end despite the cancellation", 1, scheduled)
            val pending = q.dao.sendable().single()
            assertEquals("Cancelled while scheduling", pending.capture.text)
            assertEquals("y", File(pending.attachments.single().localPath).readText())
        }

    // --- text length limit after pinning (CR-3) ---

    @Test
    fun `text of exactly the maximum length is queued, one character more is refused`() =
        runBlocking {
            val atLimit = textOfLength(CaptureQueue.MAX_TEXT_LENGTH)
            assertEquals(CaptureQueue.MAX_TEXT_LENGTH, atLimit.length)

            try {
                queue().capture(atLimit + "x", null, listOf(CaptureAttachment(q.source("a.txt", "a"))))
                fail("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
            }
            assertNothingQueuedOrCopied()
            assertEquals(0, q.scheduler.scheduled)

            queue().capture(atLimit)
            assertEquals(
                atLimit,
                q.dao
                    .sendable()
                    .single()
                    .capture.text,
            )
            assertEquals(1, q.scheduler.scheduled)
        }

    @Test
    fun `pinning today that pushes the text over the limit refuses it before anything is copied or queued`() =
        runBlocking {
            // "!today" (6) becomes "!2026-10-20" (11): 5 characters longer.
            val text = textOfLength(CaptureQueue.MAX_TEXT_LENGTH - 4, "!today")
            assertEquals(CaptureQueue.MAX_TEXT_LENGTH - 4, text.length)

            try {
                val attachments = listOf(CaptureAttachment(q.source("a.txt", "a")), CaptureAttachment(q.source("b.txt", "b")))
                queue().capture(text, null, attachments)
                fail("expected IllegalArgumentException: the pinned text is one character too long")
            } catch (_: IllegalArgumentException) {
            }

            assertNothingQueuedOrCopied()
            assertEquals(0, q.scheduler.scheduled)
        }

    @Test
    fun `pinning that lands exactly on the limit is queued with the pinned date`() =
        runBlocking {
            val today = textOfLength(CaptureQueue.MAX_TEXT_LENGTH - 5, "!today")
            // "!tomorrow" (9) becomes "!2026-10-21" (11): 2 characters longer.
            val tomorrow = textOfLength(CaptureQueue.MAX_TEXT_LENGTH - 2, "!tomorrow")

            queue().capture(today)
            queue().capture(tomorrow)

            val texts = q.dao.sendable().map { it.capture.text }
            assertEquals(listOf(CaptureQueue.MAX_TEXT_LENGTH, CaptureQueue.MAX_TEXT_LENGTH), texts.map { it.length })
            assertTrue(texts[0].startsWith("Plan !2026-10-20 "))
            assertTrue(texts[1].startsWith("Plan !2026-10-21 "))
        }

    @Test
    fun `pinning tomorrow one character over the limit is refused`() =
        runBlocking {
            try {
                queue().capture(textOfLength(CaptureQueue.MAX_TEXT_LENGTH - 1, "!tomorrow"))
                fail("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
            }
            assertNothingQueuedOrCopied()
            assertEquals(0, q.scheduler.scheduled)
        }

    @Test
    fun `a today token outside the title line is not pinned, so a text at the limit stays accepted`() =
        runBlocking {
            val title = "Notes for the trip\n"
            val text = title + "!today " + "x".repeat(CaptureQueue.MAX_TEXT_LENGTH - title.length - "!today ".length)
            assertEquals(CaptureQueue.MAX_TEXT_LENGTH, text.length)

            queue().capture(text)

            assertEquals(
                text,
                q.dao
                    .sendable()
                    .single()
                    .capture.text,
            )
        }
}
