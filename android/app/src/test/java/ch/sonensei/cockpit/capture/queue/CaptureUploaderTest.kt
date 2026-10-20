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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CaptureUploaderTest {
    private val server = MockWebServer()
    private lateinit var q: QueueHarness

    @Before
    fun setUp() {
        server.start()
        q = QueueHarness(server)
    }

    @After
    fun tearDown() {
        q.close()
        server.close()
    }

    private fun takeAll(): List<RecordedRequest> =
        generateSequence { server.takeRequest(0, java.util.concurrent.TimeUnit.SECONDS) }.toList()

    private fun body(request: RecordedRequest) = Json.parseToJsonElement(request.body!!.utf8()).jsonObject

    @Test
    fun `attachments are uploaded in order before the capture, which carries the client id and file ids`() =
        runBlocking {
            val id =
                q.queue.capture(
                    "Taxes",
                    "see files",
                    listOf(CaptureAttachment(q.source("a.txt", "first")), CaptureAttachment(q.source("b.txt", "second"))),
                )
            server.enqueue(answer(201, fileJson(1, "a.txt")))
            server.enqueue(answer(201, fileJson(2, "b.txt")))
            server.enqueue(answer(201, captureTaskJson(id.toString())))

            assertEquals(SendResult.DONE, q.uploader.sendAll())

            val requests = takeAll()
            assertEquals(listOf(FILES, FILES, CAPTURE), requests.map { it.url.encodedPath })
            assertTrue(requests[0].body!!.utf8().contains("first"))
            assertTrue(requests[1].body!!.utf8().contains("second"))
            val capture = body(requests[2])
            assertEquals(id.toString(), capture["id"]!!.jsonPrimitive.content)
            assertEquals("Taxes", capture["text"]!!.jsonPrimitive.content)
            assertEquals("see files", capture["notes"]!!.jsonPrimitive.content)
            assertEquals(listOf(fileId(1), fileId(2)), capture["fileIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        }

    @Test
    fun `a sent capture is removed with its attachment copies`() =
        runBlocking {
            val id = q.queue.capture("With file", null, listOf(CaptureAttachment(q.source("a.txt", "x"))))
            val copy =
                File(
                    q.dao
                        .sendable()
                        .single()
                        .attachments
                        .single()
                        .localPath,
                )
            server.enqueue(answer(201, fileJson(1, "a.txt")))
            server.enqueue(answer(201, captureTaskJson(id.toString())))

            assertEquals(SendResult.DONE, q.uploader.sendAll())

            assertTrue(q.dao.sendable().isEmpty())
            assertTrue(q.dao.attachments(id.toString()).isEmpty())
            assertFalse(copy.exists())
            assertFalse(File(q.root, id.toString()).exists())
            assertEquals(CaptureQueueStatus(0, null), q.queue.status.first())
        }

    @Test
    fun `a retry after a failed capture call does not upload the attachments again`() =
        runBlocking {
            val id = q.queue.capture("Retry me", null, listOf(CaptureAttachment(q.source("a.txt", "x"))))
            server.enqueue(answer(201, fileJson(1, "a.txt")))
            server.enqueue(answer(503))

            assertEquals(SendResult.RETRY, q.uploader.sendAll())
            assertEquals(CaptureQueueStatus(1, "Server error (HTTP 503)"), q.queue.status.first())
            assertEquals(listOf(FILES, CAPTURE), takeAll().map { it.url.encodedPath })

            // The first answer got lost: the server already has the task and answers the repeat with 200.
            server.enqueue(answer(200, captureTaskJson(id.toString())))
            assertEquals(SendResult.DONE, q.uploader.sendAll())

            val retry = takeAll()
            assertEquals(listOf(CAPTURE), retry.map { it.url.encodedPath })
            assertEquals(id.toString(), body(retry[0])["id"]!!.jsonPrimitive.content)
            assertEquals(listOf(fileId(1)), body(retry[0])["fileIds"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(CaptureQueueStatus(0, null), q.queue.status.first())
        }

    @Test
    fun `a duplicate id the server already trashed counts as sent`() =
        runBlocking {
            q.queue.capture("Old")
            server.enqueue(answer(409, """{"title":"In trash","status":409}"""))

            assertEquals(SendResult.DONE, q.uploader.sendAll())
            assertTrue(q.dao.sendable().isEmpty())
        }

    @Test
    fun `an unreachable server keeps the queue and asks for a retry`() =
        runBlocking {
            q.queue.capture("Offline")
            server.close()

            assertEquals(SendResult.RETRY, q.uploader.sendAll())
            assertEquals(CaptureQueueStatus(1, CaptureUploader.OFFLINE), q.queue.status.first())
        }

    @Test
    fun `a rejected capture is kept with its error and the next one is still sent`() =
        runBlocking {
            q.queue.capture("Bad")
            val good = q.queue.capture("Good")
            server.enqueue(answer(400, """{"title":"Bad request","status":400}"""))
            server.enqueue(answer(201, captureTaskJson(good.toString())))

            assertEquals(SendResult.DONE, q.uploader.sendAll())

            assertEquals(CaptureQueueStatus(1, "Rejected by the server (HTTP 400)"), q.queue.status.first())
            assertTrue("rejected captures are not retried", q.dao.sendable().isEmpty())
            assertEquals(2, takeAll().size)
        }

    @Test
    fun `nothing is sent while logged out`() =
        runBlocking {
            val loggedOut = QueueHarness(server, loggedIn = false)
            try {
                loggedOut.queue.capture("Later")
                assertEquals(SendResult.RETRY, loggedOut.uploader.sendAll())
                assertEquals(CaptureQueueStatus(1, CaptureUploader.NOT_LOGGED_IN), loggedOut.queue.status.first())
                assertTrue(takeAll().isEmpty())
            } finally {
                loggedOut.close()
            }
        }
}
