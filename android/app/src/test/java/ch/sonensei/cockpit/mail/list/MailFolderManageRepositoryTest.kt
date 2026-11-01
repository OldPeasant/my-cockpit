package ch.sonensei.cockpit.mail.list

import ch.sonensei.cockpit.core.network.generated.apis.MailAccountsApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.UUID

/** Creating, renaming and deleting folders against a fake server: requests, ETags, answers and failures. */
class MailFolderManageRepositoryTest {
    private val server = MockWebServer()
    private lateinit var repository: MailRepository

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val account = id(80)
    private val projects = MailFolder(id(92), account, "Projects", "Projects", null, 0, version = 3, delimiter = "/")

    private fun folderJson(
        n: Int,
        path: String,
        name: String,
        version: Int,
        role: String? = null,
    ) = """{"id":"${id(n)}","accountId":"$account","name":"$name","path":"$path","delimiter":"/",""" +
        """"role":${role?.let { "\"$it\"" } ?: "null"},"roleOverridden":false,"selectable":true,"version":$version}"""

    private fun json(
        body: String,
        code: Int = 200,
    ) = MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun problem(code: Int) =
        MockResponse
            .Builder()
            .code(code)
            .addHeader("Content-Type", "application/problem+json")
            .body("""{"status":$code,"title":"No"}""")
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
        repository = MailRepository(retrofit.create(MailApi::class.java), retrofit.create(MailAccountsApi::class.java))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `a new subfolder is created under its parent and read back with its depth and version`() =
        runBlocking {
            server.enqueue(json(folderJson(93, "Projects/Alpha", "Alpha", 1), 201))

            val result = repository.createFolder(account, projects, "Alpha")

            val folder = (result as MailResult.Ok).value
            assertEquals(MailFolder(id(93), account, "Alpha", "Projects/Alpha", null, 1, version = 1, delimiter = "/"), folder)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/mail/accounts/$account/folders", request.url.encodedPath)
            val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
            assertEquals("Alpha", body["name"]!!.jsonPrimitive.content)
            assertEquals(projects.id.toString(), body["parentId"]!!.jsonPrimitive.content)
        }

    @Test
    fun `a top-level folder is created without a parent`() =
        runBlocking {
            server.enqueue(json(folderJson(94, "Receipts", "Receipts", 1), 201))

            repository.createFolder(account, null, "Receipts")

            val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            val parent = body["parentId"]
            assert(parent == null || parent == JsonNull) { "parentId must be absent or null, was $parent" }
        }

    @Test
    fun `renaming sends only the name, with the folder's version as If-Match`() =
        runBlocking {
            server.enqueue(json(folderJson(92, "Clients", "Clients", 4)))

            val folder = (repository.renameFolder(projects, "Clients") as MailResult.Ok).value

            assertEquals("Clients", folder.name)
            assertEquals(4L, folder.version)
            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/api/v1/mail/accounts/$account/folders/${projects.id}", request.url.encodedPath)
            assertEquals("\"3\"", request.headers["If-Match"])
            val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
            // A "role" key, even null, would take the folder's role away on the server.
            assertEquals(setOf("name"), body.keys)
        }

    @Test
    fun `deleting sends the folder's version as If-Match and accepts 204`() =
        runBlocking {
            server.enqueue(MockResponse.Builder().code(204).build())

            assertEquals(MailResult.Ok(Unit), repository.deleteFolder(projects))

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/v1/mail/accounts/$account/folders/${projects.id}", request.url.encodedPath)
            assertEquals("\"3\"", request.headers["If-Match"])
        }

    @Test
    fun `refusals keep their status, a mail server that cannot be reached is offline`() =
        runBlocking {
            server.enqueue(problem(409))
            server.enqueue(problem(400))
            server.enqueue(problem(412))
            server.enqueue(problem(412))
            server.enqueue(problem(404))
            server.enqueue(problem(404))
            server.enqueue(problem(502))

            assertEquals(MailResult.Failed(409), repository.createFolder(account, null, "Projects"))
            assertEquals(MailResult.Failed(400), repository.renameFolder(projects, "a/b"))
            assertEquals(MailResult.Failed(412), repository.renameFolder(projects, "Clients"))
            assertEquals(MailResult.Failed(412), repository.deleteFolder(projects))
            assertEquals(MailResult.Failed(404), repository.renameFolder(projects, "Clients"))
            assertEquals(MailResult.Failed(404), repository.deleteFolder(projects))
            assertEquals(MailResult.Offline, repository.deleteFolder(projects))
        }

    @Test
    fun `an unreadable answer to create or rename is a failure without status`() =
        runBlocking {
            server.enqueue(json("""{"id":"not-a-folder"}""", 201))
            server.enqueue(json("not json"))

            assertEquals(MailResult.Failed(null), repository.createFolder(account, null, "Receipts"))
            assertEquals(MailResult.Failed(null), repository.renameFolder(projects, "Clients"))
        }

    @Test
    fun `a folder listed without a version still sends a quoted If-Match, so the server answers 412 instead of 428`() =
        runBlocking {
            server.enqueue(MockResponse.Builder().code(204).build())

            repository.deleteFolder(projects.copy(version = 0))

            assertEquals("\"0\"", server.takeRequest().headers["If-Match"])
        }

    @Test
    fun `no connection at all is offline`() =
        runBlocking {
            server.close()

            assertEquals(MailResult.Offline, repository.createFolder(account, null, "Receipts"))
        }

    @Test
    fun `the folder list carries each folder's version and delimiter`() =
        runBlocking {
            server.enqueue(json("""{"items":[{"id":"$account","displayName":"Work","emailAddress":"me@example.org"}]}"""))
            server.enqueue(json("""{"items":[${folderJson(92, "Projects", "Projects", 7)}]}"""))

            val folder =
                (repository.accounts() as MailResult.Ok)
                    .value
                    .single()
                    .folders
                    .single()

            assertEquals(7L, folder.version)
            assertEquals("/", folder.delimiter)
            assertFalse(folder.contains(folder))
        }
}
