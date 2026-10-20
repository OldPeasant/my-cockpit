package ch.sonensei.cockpit.capture.queue

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Failure classification and partial progress of [CaptureUploader] (SPEC.md §9.3, task assumptions #2 and #4). */
@RunWith(AndroidJUnit4::class)
class CaptureUploaderFailureTest {
    private val server = MockWebServer()
    private lateinit var q: QueueHarness

    @Before
    fun setUp() {
        server.start()
        q = QueueHarness(server, clock = TickingClock())
    }

    @After
    fun tearDown() {
        q.close()
        server.close()
    }

    private fun takeAll(): List<RecordedRequest> = generateSequence { server.takeRequest(0, TimeUnit.SECONDS) }.toList()

    private fun fileIdsOf(request: RecordedRequest) =
        Json
            .parseToJsonElement(request.body!!.utf8())
            .jsonObject["fileIds"]!!
            .jsonArray
            .map { it.jsonPrimitive.content }

    @Test
    fun `a failure after the first of two uploads resumes with the second one on retry`() =
        runBlocking {
            val id =
                q.queue.capture(
                    "Two files",
                    null,
                    listOf(CaptureAttachment(q.source("a.txt", "first")), CaptureAttachment(q.source("b.txt", "second"))),
                )
            server.enqueue(answer(201, fileJson(1, "a.txt")))
            server.enqueue(answer(503))

            assertEquals(SendResult.RETRY, q.uploader.sendAll())
            assertEquals(listOf(FILES, FILES), takeAll().map { it.url.encodedPath })
            val stored = q.dao.attachments(id.toString())
            assertEquals(fileId(1), stored[0].remoteFileId)
            assertNull(stored[1].remoteFileId)
            assertEquals(CaptureQueueStatus(1, "Server error (HTTP 503)"), q.queue.status.first())

            server.enqueue(answer(201, fileJson(2, "b.txt")))
            server.enqueue(answer(201, captureTaskJson(id.toString())))
            assertEquals(SendResult.DONE, q.uploader.sendAll())

            val retry = takeAll()
            assertEquals(listOf(FILES, CAPTURE), retry.map { it.url.encodedPath })
            assertTrue("only the second file is uploaded again", retry[0].body!!.utf8().contains("second"))
            assertEquals(listOf(fileId(1), fileId(2)), fileIdsOf(retry[1]))
            assertEquals(CaptureQueueStatus(0, null), q.queue.status.first())
            assertTrue(!File(q.root, id.toString()).exists())
        }

    @Test
    fun `an unreachable server during an upload asks for a retry`() =
        runBlocking {
            q.queue.capture("Offline file", null, listOf(CaptureAttachment(q.source("a.txt", "x"))))
            server.close()

            assertEquals(SendResult.RETRY, q.uploader.sendAll())
            assertEquals(CaptureQueueStatus(1, CaptureUploader.OFFLINE), q.queue.status.first())
            assertNull(
                q.dao
                    .sendable()
                    .single()
                    .attachments
                    .single()
                    .remoteFileId,
            )
        }

    @Test
    fun `a too large attachment rejects the capture without a capture call and the next one is still sent`() =
        runBlocking {
            val big = q.queue.capture("Big", null, listOf(CaptureAttachment(q.source("big.bin", "xxxx"))))
            val small = q.queue.capture("Small")
            server.enqueue(answer(413, """{"title":"Payload too large","status":413}"""))
            server.enqueue(answer(201, captureTaskJson(small.toString())))

            assertEquals(SendResult.DONE, q.uploader.sendAll())

            assertEquals(listOf(FILES, CAPTURE), takeAll().map { it.url.encodedPath })
            assertEquals(CaptureQueueStatus(1, "Rejected by the server (HTTP 413)"), q.queue.status.first())
            assertTrue("a rejected capture is not retried", q.dao.sendable().isEmpty())
            assertTrue("the rejected capture keeps its copy", File(q.root, big.toString()).isDirectory)

            // A later run sends nothing for the rejected capture.
            assertEquals(SendResult.DONE, q.uploader.sendAll())
            assertTrue(takeAll().isEmpty())
        }

    @Test
    fun `401 on the capture call retries as not logged in and stops before later captures`() =
        runBlocking {
            q.queue.capture("First")
            q.queue.capture("Second")
            server.enqueue(answer(401, """{"title":"Unauthorized","status":401}"""))

            assertEquals(SendResult.RETRY, q.uploader.sendAll())

            assertEquals(1, takeAll().size)
            assertEquals(CaptureQueueStatus(2, CaptureUploader.NOT_LOGGED_IN), q.queue.status.first())
            assertEquals("both stay sendable", 2, q.dao.sendable().size)
        }

    @Test
    fun `401 on an upload retries as not logged in`() =
        runBlocking {
            q.queue.capture("File", null, listOf(CaptureAttachment(q.source("a.txt", "x"))))
            server.enqueue(answer(401))

            assertEquals(SendResult.RETRY, q.uploader.sendAll())
            assertEquals(CaptureQueueStatus(1, CaptureUploader.NOT_LOGGED_IN), q.queue.status.first())
        }

    @Test
    fun `408, 429 and 5xx are retried, other 4xx are rejected`() =
        runBlocking {
            val retried = listOf(408, 429, 500, 502)
            for (code in retried) {
                q.queue.capture("Code $code")
                server.enqueue(answer(code))
                // OkHttp itself repeats a request once after a 408; the second 408 reaches the uploader.
                if (code == 408) server.enqueue(answer(code))
                assertEquals("HTTP $code", SendResult.RETRY, q.uploader.sendAll())
                assertEquals(CaptureQueueStatus(1, "Server error (HTTP $code)"), q.queue.status.first())
                q.dao.delete(
                    q.dao
                        .sendable()
                        .single()
                        .capture.id,
                )
            }
            for (code in listOf(400, 403, 404, 422)) {
                q.queue.capture("Code $code")
                server.enqueue(answer(code))
                assertEquals("HTTP $code", SendResult.DONE, q.uploader.sendAll())
                assertTrue("HTTP $code is not retried", q.dao.sendable().isEmpty())
            }
            assertEquals(CaptureQueueStatus(4, "Rejected by the server (HTTP 422)"), q.queue.status.first())
        }

    @Test
    fun `an upload answer without a file id is retried as a server error`() =
        runBlocking {
            q.queue.capture("No body", null, listOf(CaptureAttachment(q.source("a.txt", "x"))))
            server.enqueue(answer(204))

            assertEquals(SendResult.RETRY, q.uploader.sendAll())
            assertEquals(CaptureQueueStatus(1, "Server error (HTTP 204)"), q.queue.status.first())
            assertNull(
                q.dao
                    .sendable()
                    .single()
                    .attachments
                    .single()
                    .remoteFileId,
            )
        }

    @Test
    fun `a capture whose attachment copy vanished is rejected without any request`() =
        runBlocking {
            val id = q.queue.capture("Lost copy", null, listOf(CaptureAttachment(q.source("a.txt", "x"))))
            File(q.root, id.toString()).deleteRecursively()

            assertEquals(SendResult.DONE, q.uploader.sendAll())

            assertTrue(takeAll().isEmpty())
            assertEquals(CaptureQueueStatus(1, CaptureUploader.ATTACHMENT_MISSING), q.queue.status.first())
        }

    @Test
    fun `captures are sent oldest first`() =
        runBlocking {
            val texts = listOf("one", "two", "three", "four")
            texts.forEach { q.queue.capture(it) }
            repeat(texts.size) { server.enqueue(answer(201, captureTaskJson(fileId(it)))) }

            assertEquals(SendResult.DONE, q.uploader.sendAll())

            val sent =
                takeAll().map {
                    Json
                        .parseToJsonElement(it.body!!.utf8())
                        .jsonObject["text"]!!
                        .jsonPrimitive.content
                }
            assertEquals(texts, sent)
        }

    @Test
    fun `the status reports the most recent error and falls back to an older rejection once the newer capture is sent`() =
        runBlocking {
            q.queue.capture("Rejected")
            val later = q.queue.capture("Later")
            server.enqueue(answer(400))
            server.enqueue(answer(503))

            assertEquals(SendResult.RETRY, q.uploader.sendAll())
            assertEquals(CaptureQueueStatus(2, "Server error (HTTP 503)"), q.queue.status.first())

            server.enqueue(answer(201, captureTaskJson(later.toString())))
            assertEquals(SendResult.DONE, q.uploader.sendAll())
            assertEquals(CaptureQueueStatus(1, "Rejected by the server (HTTP 400)"), q.queue.status.first())
        }

    @Test
    fun `an unreadable upload answer marks that capture and the next one is still sent`() =
        runBlocking {
            q.queue.capture("Odd answer", null, listOf(CaptureAttachment(q.source("a.txt", "x"))))
            val next = q.queue.capture("Next")
            server.enqueue(answer(201, "this is not json"))
            server.enqueue(answer(201, captureTaskJson(next.toString())))

            assertEquals(SendResult.RETRY, q.uploader.sendAll())

            assertEquals(listOf(FILES, CAPTURE), takeAll().map { it.url.encodedPath })
            val left = q.dao.sendable().single()
            assertEquals("Odd answer", left.capture.text)
            assertEquals(CaptureQueueStatus(1, CaptureUploader.UNEXPECTED_ERROR), q.queue.status.first())

            // Retried on the next run, not stuck.
            server.enqueue(answer(201, fileJson(1, "a.txt")))
            server.enqueue(answer(201, captureTaskJson(left.capture.id)))
            assertEquals(SendResult.DONE, q.uploader.sendAll())
            assertEquals(CaptureQueueStatus(0, null), q.queue.status.first())
        }

    @Test
    fun `a corrupt stored file id fails only that capture, which is retried`() =
        runBlocking {
            val broken = q.queue.capture("Broken id", null, listOf(CaptureAttachment(q.source("a.txt", "x"))))
            q.dao.markUploaded(broken.toString(), 0, "not-a-uuid")
            val next = q.queue.capture("Next")
            server.enqueue(answer(201, captureTaskJson(next.toString())))

            assertEquals(SendResult.RETRY, q.uploader.sendAll())

            assertEquals(listOf(CAPTURE), takeAll().map { it.url.encodedPath })
            val left = q.dao.sendable().single()
            assertEquals(broken.toString(), left.capture.id)
            assertEquals(CaptureUploader.UNEXPECTED_ERROR, left.capture.lastError)
        }
}
