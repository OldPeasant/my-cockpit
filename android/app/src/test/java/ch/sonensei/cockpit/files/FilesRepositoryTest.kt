package ch.sonensei.cockpit.files

import ch.sonensei.cockpit.core.network.generated.apis.FilesApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** The drive calls against a fake server: what is asked, and how answers and refusals are reported. */
class FilesRepositoryTest {
    private val server = MockWebServer()
    private lateinit var files: FilesRepository

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun fileJson(
        n: Int,
        name: String,
        mime: String = "application/pdf",
    ) = """{"id":"${id(n)}","name":"$name","mime":"$mime","size":2048,"sha256":"ab","createdAt":"2026-10-01T09:00:00Z",
        |"updatedAt":"2026-10-02T09:00:00Z","version":1}
        """.trimMargin().replace("\n", "")

    private fun folderJson(
        n: Int,
        name: String,
        special: String? = null,
    ) = """{"id":"${id(n)}","name":"$name",${special?.let { "\"special\":\"$it\"," } ?: ""}"createdAt":"2026-10-01T09:00:00Z",
        |"updatedAt":"2026-10-01T09:00:00Z","version":1}
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

    private class Source(
        override val name: String,
        private val bytes: ByteArray?,
    ) : UploadSource {
        override val mime = "text/plain"
        override val sizeBytes = bytes?.size?.toLong()

        override fun open(): InputStream? = bytes?.let(::ByteArrayInputStream)
    }

    @Before
    fun setUp() {
        server.start()
        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
        files = FilesRepository(retrofit.create(FilesApi::class.java), retrofit.create(FileContentApi::class.java))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `a folder lists folders and files across pages`() =
        runBlocking {
            server.enqueue(
                json(
                    """{"items":[{"type":"FOLDER","folder":${folderJson(1, "Attachments", "ATTACHMENTS")}},
                    |{"type":"FILE","file":${fileJson(2, "tax.pdf")}}],"nextCursor":"c2"}
                    """.trimMargin(),
                ),
            )
            server.enqueue(json("""{"items":[{"type":"FILE","file":${fileJson(3, "notes.txt", "text/plain")}},{"type":"FILE"}]}"""))

            val result = files.list(id(9)) as FilesResult.Ok
            assertEquals(
                listOf(DriveEntry.Folder(id(1), "Attachments", "ATTACHMENTS")),
                result.value.filterIsInstance<DriveEntry.Folder>(),
            )
            assertEquals(listOf("tax.pdf", "notes.txt"), result.value.filterIsInstance<DriveEntry.File>().map { it.name })
            assertEquals(2048L, (result.value[1] as DriveEntry.File).size)
            val first = server.takeRequest().url
            assertEquals("/api/v1/drive", first.encodedPath)
            assertEquals(id(9).toString(), first.queryParameter("folderId"))
            assertEquals("c2", server.takeRequest().url.queryParameter("cursor"))
        }

    @Test
    fun `the drive root is listed without a folder`() =
        runBlocking {
            server.enqueue(json("""{"items":[]}"""))
            assertEquals(FilesResult.Ok(emptyList<DriveEntry>()), files.list(null))
            assertEquals(null, server.takeRequest().url.queryParameter("folderId"))
        }

    @Test
    fun `refusals, server errors and garbage are told apart`() =
        runBlocking {
            server.enqueue(status(404))
            assertEquals(FilesResult.Failed(404), files.list(id(1)))
            server.enqueue(status(503))
            assertEquals(FilesResult.Offline, files.list(null))
            server.enqueue(json("{nope"))
            assertEquals(FilesResult.Failed(null), files.list(null))
            server.close()
            assertEquals(FilesResult.Offline, files.list(null))
        }

    @Test
    fun `a download streams the contents into the target`() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("hello drive")
                    .build(),
            )
            val out = ByteArrayOutputStream()
            assertEquals(FilesResult.Ok(11L), files.download(id(4)) { out })
            assertEquals("hello drive", out.toString(Charsets.UTF_8))
            assertEquals("/api/v1/files/${id(4)}/content", server.takeRequest().url.encodedPath)
        }

    @Test
    fun `a download that cannot be written or is refused fails`() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("x")
                    .build(),
            )
            assertEquals(FilesResult.Failed(null), files.download(id(4)) { null })

            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("x")
                    .build(),
            )
            val broken =
                object : OutputStream() {
                    override fun write(b: Int) = throw IOException("disk full")

                    override fun write(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ) = throw IOException("disk full")
                }
            assertEquals(FilesResult.Failed(null), files.download(id(4)) { broken })

            server.enqueue(status(404))
            assertEquals(FilesResult.Failed(404), files.download(id(4)) { ByteArrayOutputStream() })
        }

    @Test
    fun `an upload sends the file into the folder`() =
        runBlocking {
            server.enqueue(json(fileJson(5, "todo.txt", "text/plain"), code = 201))
            val result = files.upload(id(9), Source("todo.txt", "buy milk".toByteArray())) as FilesResult.Ok
            assertEquals("todo.txt", result.value.name)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/files", request.url.encodedPath)
            assertEquals(id(9).toString(), request.url.queryParameter("folderId"))
            assertEquals("todo.txt", request.url.queryParameter("name"))
            val body = request.body!!.utf8()
            assertTrue(body.contains("filename=\"todo.txt\""))
            assertTrue(body.contains("buy milk"))
        }

    @Test
    fun `an upload that is too large or cannot be read fails`() =
        runBlocking {
            server.enqueue(status(413))
            assertEquals(FilesResult.Failed(413), files.upload(null, Source("big.bin", ByteArray(10))))
            server.enqueue(status(201))
            assertEquals(FilesResult.Failed(null), files.upload(null, Source("gone.txt", null)))
        }
}
