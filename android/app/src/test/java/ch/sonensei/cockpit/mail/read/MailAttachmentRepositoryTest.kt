package ch.sonensei.cockpit.mail.read

import ch.sonensei.cockpit.core.network.generated.apis.FilesApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import ch.sonensei.cockpit.core.network.generated.models.FileResponse
import ch.sonensei.cockpit.mail.list.MailResult
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/** Attachment download and "Save to Files" (with the move into a picked folder) against a fake server. */
class MailAttachmentRepositoryTest {
    private val server = MockWebServer()
    private lateinit var repository: MailAttachmentRepository

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun fileJson(
        n: Int,
        name: String,
        folder: UUID?,
        version: Long = 3,
    ) = """{"id":"${id(n)}","name":"$name","mime":"application/pdf","size":2048,"sha256":"ab",
        |"createdAt":"2026-10-01T09:00:00Z","updatedAt":"2026-10-02T09:00:00Z","version":$version
        |${folder?.let { ",\"folderId\":\"$it\"" } ?: ""}}
        """.trimMargin().replace("\n", "")

    private fun json(
        body: String,
        code: Int = 200,
    ) = MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun status(code: Int) = MockResponse.Builder().code(code).build()

    private fun bytes(content: ByteArray) =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/pdf")
            .body(okio.Buffer().write(content))
            .build()

    @Before
    fun setUp() {
        server.start()
        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
        repository = MailAttachmentRepository(retrofit.create(MailAttachmentApi::class.java), retrofit.create(FilesApi::class.java))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `a download streams the attachment into the target and counts the bytes`() =
        runBlocking {
            val content = ByteArray(200_000) { (it % 251).toByte() }
            server.enqueue(bytes(content))
            val out = ByteArrayOutputStream()

            val result = repository.download(id(1), "2", { out })

            assertEquals(MailResult.Ok(content.size.toLong()), result)
            assertArrayEquals(content, out.toByteArray())
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/v1/mail/messages/${id(1)}/attachments/2", request.url.encodedPath)
        }

    @Test
    fun `an empty attachment is downloaded as zero bytes`() =
        runBlocking {
            server.enqueue(bytes(ByteArray(0)))
            val out = ByteArrayOutputStream()

            assertEquals(MailResult.Ok(0L), repository.download(id(1), "2", { out }))
            assertEquals(0, out.size())
        }

    @Test
    fun `a target that opens nothing fails without being called offline`() =
        runBlocking {
            server.enqueue(bytes("%PDF-1.4".toByteArray()))

            assertEquals(MailResult.Failed(null), repository.download(id(1), "2", { null }))
        }

    @Test
    fun `a failed write (a full disk) is a failure, not a lost connection, and the target is closed`() =
        runBlocking {
            server.enqueue(bytes("%PDF-1.4".toByteArray()))
            var closed = false
            val full =
                object : OutputStream() {
                    override fun write(b: Int) = throw IOException("No space left on device")

                    override fun write(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ) = throw IOException("No space left on device")

                    override fun close() {
                        closed = true
                    }
                }

            assertEquals(MailResult.Failed(null), repository.download(id(1), "2", { full }))
            assertTrue(closed)
        }

    @Test
    fun `a refused download keeps the status and never opens the target`() =
        runBlocking {
            server.enqueue(status(404))
            var opened = false

            val result =
                repository.download(id(1), "2", {
                    opened = true
                    ByteArrayOutputStream()
                })

            assertEquals(MailResult.Failed(404), result)
            assertEquals(false, opened)
        }

    @Test
    fun `a download without a server is offline`() =
        runBlocking {
            server.close()

            assertEquals(MailResult.Offline, repository.download(id(1), "2", { ByteArrayOutputStream() }))
        }

    @Test
    fun `a server error on download is reported as offline`() =
        runBlocking {
            server.enqueue(status(502))

            assertEquals(MailResult.Offline, repository.download(id(1), "2", { ByteArrayOutputStream() }))
        }

    @Test
    fun `a connection lost in the middle of a download is offline, not a full disk, and closes target and body`() =
        runBlocking {
            var bodyClosed = false
            val breaking =
                object : Source {
                    private var sent = false

                    override fun read(
                        sink: Buffer,
                        byteCount: Long,
                    ): Long {
                        if (sent) throw IOException("Connection reset")
                        sent = true
                        sink.writeUtf8("%PDF-1.4 first part")
                        return 19
                    }

                    override fun timeout() = Timeout.NONE

                    override fun close() {
                        bodyClosed = true
                    }
                }
            val api =
                object : MailAttachmentApi {
                    override suspend fun download(
                        id: String,
                        partId: String,
                    ): Response<ResponseBody> = Response.success(breaking.buffer().asResponseBody("application/pdf".toMediaType()))

                    override suspend fun saveToFiles(
                        id: String,
                        partId: String,
                    ): Response<FileResponse> = error("not called")
                }
            var targetClosed = false
            val out =
                object : ByteArrayOutputStream() {
                    override fun close() {
                        targetClosed = true
                    }
                }

            val result = MailAttachmentRepository(api, mockk()).download(id(1), "2", { out })

            assertEquals(MailResult.Offline, result)
            assertEquals("%PDF-1.4 first part", out.toString())
            assertTrue("target not closed", targetClosed)
            assertTrue("response body not closed", bodyClosed)
        }

    @Test
    fun `a cancelled download stops streaming partway and closes target and body`() =
        runBlocking {
            val chunk = 1024
            val maxReads = 10_000
            var reads = 0
            var bodyClosed = false
            val streaming = CompletableDeferred<Unit>()
            val endless =
                object : Source {
                    override fun read(
                        sink: Buffer,
                        byteCount: Long,
                    ): Long {
                        if (reads >= maxReads) return -1
                        reads++
                        if (reads == 5) streaming.complete(Unit)
                        sink.write(ByteArray(chunk))
                        return chunk.toLong()
                    }

                    override fun timeout() = Timeout.NONE

                    override fun close() {
                        bodyClosed = true
                    }
                }
            val api =
                object : MailAttachmentApi {
                    override suspend fun download(
                        id: String,
                        partId: String,
                    ): Response<ResponseBody> = Response.success(endless.buffer().asResponseBody("application/pdf".toMediaType()))

                    override suspend fun saveToFiles(
                        id: String,
                        partId: String,
                    ): Response<FileResponse> = error("not called")
                }
            var targetClosed = false
            val cancelled = CompletableDeferred<Unit>()
            val out =
                object : OutputStream() {
                    override fun write(b: Int) = Unit

                    override fun write(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ) {
                        // Each chunk waits until the test has cancelled, once the transfer is under way.
                        if (streaming.isCompleted) runBlocking { cancelled.await() }
                    }

                    override fun close() {
                        targetClosed = true
                    }
                }

            val download =
                async(Dispatchers.Default) { MailAttachmentRepository(api, mockk()).download(id(1), "2", { out }) }
            streaming.await()
            download.cancel()
            cancelled.complete(Unit)
            runCatching { download.await() }

            assertTrue("download not cancelled", download.isCancelled)
            assertTrue("kept streaming after cancel: $reads reads", reads < maxReads)
            assertTrue("target not closed", targetClosed)
            assertTrue("response body not closed", bodyClosed)
        }

    @Test
    fun `save to files without a folder stores the attachment where the server puts it`() =
        runBlocking {
            server.enqueue(json(fileJson(10, "report.pdf", id(50))))

            val result = repository.saveToFiles(id(1), "2")

            assertEquals(MailResult.Ok(SavedAttachment(id(10), "report.pdf", id(50))), result)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/mail/messages/${id(1)}/attachments/2/save-to-files", request.url.encodedPath)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `save to files into a picked folder moves the stored file there with its version`() =
        runBlocking {
            server.enqueue(json(fileJson(10, "report.pdf", id(50), version = 7)))
            server.enqueue(json(fileJson(10, "report.pdf", id(60), version = 8)))

            val result = repository.saveToFiles(id(1), "2", id(60))

            assertEquals(MailResult.Ok(SavedAttachment(id(10), "report.pdf", id(60))), result)
            server.takeRequest()
            val move = server.takeRequest()
            assertEquals("PATCH", move.method)
            assertEquals("/api/v1/files/${id(10)}", move.url.encodedPath)
            assertEquals("\"7\"", move.headers["If-Match"])
            val body = Json.parseToJsonElement(move.body!!.utf8()).jsonObject
            assertEquals("${id(60)}", body.getValue("folderId").jsonPrimitive.content)
        }

    @Test
    fun `save to files into the folder the server already used does not move it`() =
        runBlocking {
            server.enqueue(json(fileJson(10, "report.pdf", id(50))))

            val result = repository.saveToFiles(id(1), "2", id(50))

            assertEquals(MailResult.Ok(SavedAttachment(id(10), "report.pdf", id(50))), result)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `a refused move keeps the stored file and names where it is`() =
        runBlocking {
            server.enqueue(json(fileJson(10, "report.pdf", id(50))))
            server.enqueue(status(409))

            val result = repository.saveToFiles(id(1), "2", id(60))

            assertEquals(MailResult.Ok(SavedAttachment(id(10), "report.pdf", id(50))), result)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `a move that loses the connection keeps the stored file`() =
        runBlocking {
            server.enqueue(json(fileJson(10, "report.pdf", id(50))))
            server.enqueue(status(503))

            assertEquals(MailResult.Ok(SavedAttachment(id(10), "report.pdf", id(50))), repository.saveToFiles(id(1), "2", id(60)))
        }

    @Test
    fun `an attachment larger than the drive allows is refused with 413 and nothing is moved`() =
        runBlocking {
            server.enqueue(json("""{"title":"Payload Too Large","status":413}""", 413))

            assertEquals(MailResult.Failed(413), repository.saveToFiles(id(1), "2", id(60)))
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `save to files without a server is offline`() =
        runBlocking {
            server.close()

            assertEquals(MailResult.Offline, repository.saveToFiles(id(1), "2"))
        }

    @Test
    fun `an unreadable save answer is a failure without a status`() =
        runBlocking {
            server.enqueue(json("""{"unexpected":true}"""))

            assertEquals(MailResult.Failed(null), repository.saveToFiles(id(1), "2"))
        }
}
