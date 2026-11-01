package ch.sonensei.cockpit.trash

import ch.sonensei.cockpit.core.network.generated.apis.TrashApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import java.util.UUID

/** The Trash endpoints against a fake server: paging, mapping, restore and permanent delete, and failures. */
class TrashRepositoryTest {
    private val server = MockWebServer()
    private val reloaded = mutableListOf<String>()
    private lateinit var trash: TrashRepository

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun json(body: String) =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun status(code: Int) = MockResponse.Builder().code(code).build()

    private fun item(
        n: Int,
        type: String,
        title: String,
        extra: String = "",
    ) = """{"id":"${id(n)}","type":"$type","title":"$title","icon":"task","deletedAt":"2026-10-01T09:00:00Z",""" +
        """"purgeAt":"2026-10-31T09:00:00Z"$extra}"""

    private fun trashItem(
        n: Int,
        type: String,
    ) = TrashItem(id(n), type, "Item $n", null, Instant.parse("2026-10-01T09:00:00Z"), Instant.parse("2026-10-31T09:00:00Z"))

    @Before
    fun setUp() {
        server.start()
        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
        trash = TrashRepository(retrofit.create(TrashApi::class.java)) { reloaded += it }
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `loads every page and maps the items`() =
        runBlocking {
            val subtitle = ""","subtitle":"Home""""
            val blank = ""","subtitle":" """"
            server.enqueue(
                json(
                    """{"items":[${item(
                        1,
                        "TASK",
                        "Call the plumber",
                        subtitle,
                    )},${item(2, "NOTE", "Recipe", blank)}],"nextCursor":"c2"}""",
                ),
            )
            server.enqueue(json("""{"items":[${item(3, "BOOKMARK", "Slow reading")}]}"""))

            val result = trash.list() as TrashResult.Ok

            assertEquals(listOf("TASK", "NOTE", "BOOKMARK"), result.value.map { it.type })
            val first = result.value.first()
            assertEquals(id(1), first.id)
            assertEquals("Call the plumber", first.title)
            assertEquals("Home", first.subtitle)
            assertEquals(Instant.parse("2026-10-01T09:00:00Z"), first.deletedAt)
            assertEquals(Instant.parse("2026-10-31T09:00:00Z"), first.purgeAt)
            assertNull(result.value[1].subtitle)
            assertEquals("/api/v1/trash?limit=200", server.takeRequest().target)
            assertEquals("/api/v1/trash?cursor=c2&limit=200", server.takeRequest().target)
        }

    @Test
    fun `restore posts to the item's restore endpoint and reloads its lists`() =
        runBlocking {
            server.enqueue(status(204))

            assertEquals(TrashResult.Ok(Unit), trash.restore(trashItem(1, "TASK")))

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/trash/TASK/${id(1)}/restore", request.target)
            assertEquals(listOf("TASK"), reloaded)
        }

    @Test
    fun `a refused restore reloads nothing`() =
        runBlocking {
            server.enqueue(status(404))

            assertEquals(TrashResult.Failed(404), trash.restore(trashItem(1, "TASK")))
            assertEquals(emptyList<String>(), reloaded)
        }

    @Test
    fun `purge deletes the item for good`() =
        runBlocking {
            server.enqueue(status(204))

            assertEquals(TrashResult.Ok(Unit), trash.purge(trashItem(2, "JOURNAL_ENTRY")))

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/v1/trash/JOURNAL_ENTRY/${id(2)}", request.target)
        }

    @Test
    fun `server errors count as offline, client errors and unreadable answers as failed`() =
        runBlocking {
            server.enqueue(status(503))
            assertEquals(TrashResult.Offline, trash.list())
            server.enqueue(status(400))
            assertEquals(TrashResult.Failed(400), trash.list())
            server.enqueue(json("""{"items":"nope"}"""))
            assertEquals(TrashResult.Failed(null), trash.list())
        }

    @Test
    fun `no connection is offline`() =
        runBlocking {
            server.close()
            assertEquals(TrashResult.Offline, trash.purge(trashItem(1, "NOTE")))
        }
}
