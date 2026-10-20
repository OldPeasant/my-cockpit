package ch.sonensei.cockpit.capture.queue

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class CaptureQueueTest {
    private val server = MockWebServer()
    private lateinit var q: QueueHarness

    @Before
    fun setUp() {
        server.start()
        q = QueueHarness(server)
        // Offline: nothing may reach the network while capturing.
        server.close()
    }

    @After
    fun tearDown() {
        q.close()
    }

    @Test
    fun `capture is written locally with attachment copies before any network call`() =
        runBlocking {
            val source = q.source("receipt.txt", "hello")

            val id = q.queue.capture("Buy milk", "2 liters", listOf(CaptureAttachment(source)))

            val pending = q.dao.sendable().single()
            assertEquals(id.toString(), pending.capture.id)
            assertEquals(7, id.version())
            assertEquals("Buy milk", pending.capture.text)
            assertEquals("2 liters", pending.capture.notes)
            assertEquals(q.clock.millis(), pending.capture.createdAtEpochMs)
            val attachment = pending.attachments.single()
            assertEquals("receipt.txt", attachment.name)
            val copy = File(attachment.localPath)
            assertTrue("copy lives in the queue's storage", copy.startsWith(q.root))
            assertEquals("hello", copy.readText())
            File(source.path!!).delete()
            assertEquals("copy survives the source", "hello", copy.readText())
            assertEquals(1, q.scheduler.scheduled)
        }

    @Test
    fun `an explicit name overrides the provider name and blank notes are dropped`() =
        runBlocking {
            q.queue.capture("Scan", " ", listOf(CaptureAttachment(q.source("x.bin", "1"), name = "Scan.pdf")))

            val pending = q.dao.sendable().single()
            assertNull(pending.capture.notes)
            assertEquals("Scan.pdf", pending.attachments.single().name)
        }

    @Test
    fun `an unreadable attachment queues nothing and leaves no copies`() =
        runBlocking {
            val readable = q.source("a.txt", "a")
            val missing = Uri.fromFile(File(q.root.parentFile, "does-not-exist-${System.nanoTime()}.txt"))
            try {
                q.queue.capture("Broken", null, listOf(CaptureAttachment(readable), CaptureAttachment(missing)))
                fail("expected IOException")
            } catch (_: IOException) {
            }

            assertTrue(q.dao.sendable().isEmpty())
            assertFalse(q.root.exists() && q.root.listFiles()!!.any { it.listFiles()!!.isNotEmpty() })
            assertEquals(0, q.scheduler.scheduled)
        }

    @Test
    fun `status flow counts pending captures and reports the latest error`() =
        runTest {
            q.queue.status.test {
                assertEquals(CaptureQueueStatus(0, null), awaitItem())
                q.queue.capture("One")
                assertEquals(CaptureQueueStatus(1, null), awaitItem())

                assertEquals(SendResult.RETRY, q.uploader.sendAll())
                assertEquals(CaptureQueueStatus(1, CaptureUploader.OFFLINE), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `blank text or too many attachments are refused before anything is copied`() =
        runBlocking {
            listOf("", "  \n\t ").forEach { text ->
                try {
                    q.queue.capture(text, null, listOf(CaptureAttachment(q.source("a.txt", "a"))))
                    fail("expected IllegalArgumentException for '$text'")
                } catch (_: IllegalArgumentException) {
                }
            }
            val source = q.source("a.txt", "a")
            try {
                q.queue.capture("Too many", null, List(CaptureQueue.MAX_ATTACHMENTS + 1) { CaptureAttachment(source) })
                fail("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
            }

            assertTrue(q.dao.sendable().isEmpty())
            assertFalse(q.root.exists() && q.root.listFiles()!!.isNotEmpty())
            assertEquals(0, q.scheduler.scheduled)

            q.queue.capture("Just enough", null, List(CaptureQueue.MAX_ATTACHMENTS) { CaptureAttachment(source) })
            assertEquals(
                CaptureQueue.MAX_ATTACHMENTS,
                q.dao
                    .sendable()
                    .single()
                    .attachments.size,
            )
        }

    @Test
    fun `relative due dates are pinned to the capture day in the device time zone`() =
        runBlocking {
            // 22:30 UTC is already the next day in Zurich.
            val clock = Clock.fixed(Instant.parse("2026-10-20T22:30:00Z"), ZoneOffset.UTC)
            val queue = CaptureQueue(q.dao, q.store, q.scheduler, clock) { ZoneId.of("Europe/Zurich") }

            queue.capture("Pay rent !today", "check !today in the notes")
            queue.capture("Call bank !Tomorrow.\nsecond line !today")

            val texts = q.dao.sendable().map { it.capture.text to it.capture.notes }
            assertEquals(
                listOf(
                    "Pay rent !2026-10-21" to "check !today in the notes",
                    "Call bank !2026-10-22.\nsecond line !today" to null,
                ),
                texts,
            )
        }
}
