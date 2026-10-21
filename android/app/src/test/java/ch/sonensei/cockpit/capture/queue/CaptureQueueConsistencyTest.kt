package ch.sonensei.cockpit.capture.queue

import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.core.network.generated.apis.CaptureApi
import ch.sonensei.cockpit.core.network.generated.apis.FilesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The real queue DAO, except that the caller's coroutine is cancelled right after a capture row is deleted. */
private class StopAfterDeleteDao(
    private val real: CaptureQueueDao,
) : CaptureQueueDao() {
    val deleted = mutableListOf<String>()

    override suspend fun insert(
        capture: PendingCaptureEntity,
        attachments: List<PendingAttachmentEntity>,
    ) = real.insert(capture, attachments)

    override suspend fun sendable() = real.sendable()

    override fun observeStatus() = real.observeStatus()

    override suspend fun attachments(captureId: String) = real.attachments(captureId)

    override suspend fun markUploaded(
        captureId: String,
        position: Int,
        remoteFileId: String,
    ) = real.markUploaded(captureId, position, remoteFileId)

    override suspend fun markFailed(
        id: String,
        error: String,
        atEpochMs: Long,
        rejected: Boolean,
    ) = real.markFailed(id, error, atEpochMs, rejected)

    override suspend fun delete(id: String) {
        real.delete(id)
        deleted += id
        currentCoroutineContext().job.cancel()
    }

    override suspend fun sendableCaptures(): List<PendingCaptureEntity> = error("not used: sendable() delegates")

    override suspend fun insertCapture(capture: PendingCaptureEntity): Unit = error("not used: insert() delegates")

    override suspend fun insertAttachments(attachments: List<PendingAttachmentEntity>): Unit = error("not used: insert() delegates")
}

/**
 * The queue's invariant behind "attachment cleanup" (AC1, AC4): a capture's attachment copies exist exactly as long as
 * the capture is in the queue, also when a run is interrupted between the two steps.
 */
@RunWith(AndroidJUnit4::class)
class CaptureQueueConsistencyTest {
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

    @Test
    fun `a send run cancelled right after the capture row is removed leaves no orphaned attachment copies`() =
        runBlocking {
            val id = q.queue.capture("Sent, then stopped", null, listOf(CaptureAttachment(q.source("a.txt", "x"))))
            server.enqueue(answer(201, fileJson(1, "a.txt")))
            server.enqueue(answer(201, captureTaskJson(id.toString())))
            // WorkManager stops the worker (e.g. the network constraint is lost) just as the sent capture is removed.
            val dao = StopAfterDeleteDao(q.dao)
            val uploader = CaptureUploader(q.session, dao, q.store, captureApi(), filesApi(), q.clock)

            val outcome = runCatching { async { uploader.sendAll() }.await() }

            assertTrue("the run was stopped", outcome.exceptionOrNull() is CancellationException)
            assertEquals(listOf(id.toString()), dao.deleted)

            assertTrue("the capture was sent and removed from the queue", q.dao.sendable().isEmpty())
            assertFalse(
                "nothing references the copies any more, so they must be gone",
                File(q.root, id.toString()).exists(),
            )
        }

    @Test
    fun `a capture stays queued with its copies when scheduling the job fails`() =
        runBlocking {
            val failingScheduler =
                object : CaptureSyncScheduler {
                    override suspend fun schedule(): Unit = throw IllegalStateException("WorkManager is not initialized")
                }
            val queue = CaptureQueue(q.dao, q.store, failingScheduler, q.clock)

            runCatching { queue.capture("Written first", null, listOf(CaptureAttachment(q.source("a.txt", "kept")))) }

            val pending = q.dao.sendable().single()
            assertEquals("Written first", pending.capture.text)
            assertEquals("kept", File(pending.attachments.single().localPath).readText())
        }

    @Test
    fun `a capture made while a send run is in progress is not lost and is sent by the next run`() =
        runBlocking {
            val first = q.queue.capture("First")
            val firstArrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            val sentTexts = CopyOnWriteArrayList<String>()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val text = textOf(request)
                        sentTexts += text
                        if (text == "First") {
                            // The run has read the queue and is sending: hold the answer while the user captures again.
                            firstArrived.countDown()
                            release.await(10, TimeUnit.SECONDS)
                        }
                        return answer(201, captureTaskJson(first.toString()))
                    }
                }

            val run = async(Dispatchers.IO) { q.uploader.sendAll() }
            assertTrue(firstArrived.await(10, TimeUnit.SECONDS))
            val second = q.queue.capture("Second, during the run")
            release.countDown()

            assertEquals(SendResult.DONE, run.await())
            assertEquals("the new capture waits for the next run", listOf(second.toString()), q.dao.sendable().map { it.capture.id })

            assertEquals(SendResult.DONE, q.uploader.sendAll())
            assertTrue(q.dao.sendable().isEmpty())
            assertEquals(listOf("First", "Second, during the run"), sentTexts)
        }

    @Test
    fun `a run stopped during the capture call keeps the capture queued without marking an error`() =
        runBlocking {
            q.queue.capture("Stopped mid-send")
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        arrived.countDown()
                        release.await(10, TimeUnit.SECONDS)
                        return answer(503)
                    }
                }

            val run = async(Dispatchers.IO) { q.uploader.sendAll() }
            assertTrue(arrived.await(10, TimeUnit.SECONDS))
            run.cancel()
            val outcome = runCatching { run.await() }
            release.countDown()

            assertTrue("the cancellation propagates", outcome.exceptionOrNull() is CancellationException)
            val pending = q.dao.sendable().single()
            assertEquals("Stopped mid-send", pending.capture.text)
            assertNull("a stopped run is not an unexpected error", pending.capture.lastError)
        }

    private fun textOf(request: RecordedRequest): String {
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        return body["text"]!!.jsonPrimitive.content
    }

    private fun captureApi() = q.retrofitApi(CaptureApi::class.java)

    private fun filesApi() = q.retrofitApi(FilesApi::class.java)
}
