package ch.sonensei.cockpit.trash

import ch.sonensei.cockpit.core.network.generated.apis.TrashApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import ch.sonensei.cockpit.gtd.data.GtdSync
import ch.sonensei.cockpit.gtd.data.RefreshResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.io.IOException
import java.time.Instant
import java.util.UUID

/** The Trash endpoints against a fake server: paging, mapping, restore and permanent delete, and failures. */
class TrashRepositoryTest {
    private val server = MockWebServer()
    private val reloaded = mutableListOf<String>()

    /** Runs the background reload at once, up to its first suspension, so the tests see it without waiting. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
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
        trash = TrashRepository(retrofit.create(TrashApi::class.java), { reloaded += it }, appScope)
    }

    @After
    fun tearDown() {
        appScope.cancel()
        server.close()
    }

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

    @Test
    fun `an empty Trash is an empty list`() =
        runBlocking {
            server.enqueue(json("""{"items":[]}"""))
            assertEquals(TrashResult.Ok(emptyList<TrashItem>()), trash.list())
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `an item repeated on the next page is listed once, the same id of another type is kept`() =
        runBlocking {
            server.enqueue(json("""{"items":[${item(1, "TASK", "First")},${item(1, "NOTE", "Same id")}],"nextCursor":"c2"}"""))
            server.enqueue(json("""{"items":[${item(1, "TASK", "First again")}]}"""))

            val result = trash.list() as TrashResult.Ok

            assertEquals(listOf("TASK:${id(1)}", "NOTE:${id(1)}"), result.value.map { it.key })
            assertEquals("First", result.value.first().title)
        }

    @Test
    fun `paging stops after the page limit even if the server keeps sending cursors`() =
        runBlocking {
            repeat(TrashRepository.MAX_PAGES + 1) { page ->
                server.enqueue(json("""{"items":[${item(page + 1, "NOTE", "Note $page")}],"nextCursor":"c${page + 1}"}"""))
            }

            val result = trash.list() as TrashResult.Ok

            assertEquals(TrashRepository.MAX_PAGES, result.value.size)
            assertEquals(TrashRepository.MAX_PAGES, server.requestCount)
        }

    @Test
    fun `a failure on a later page fails the whole load`() =
        runBlocking {
            server.enqueue(json("""{"items":[${item(1, "TASK", "First")}],"nextCursor":"c2"}"""))
            server.enqueue(status(502))
            assertEquals(TrashResult.Offline, trash.list())
        }

    @Test
    fun `an unreadable date fails the load`() =
        runBlocking {
            val broken = item(1, "TASK", "Broken").replace("2026-10-01T09:00:00Z", "yesterday")
            server.enqueue(json("""{"items":[$broken]}"""))
            assertEquals(TrashResult.Failed(null), trash.list())
        }

    @Test
    fun `a restore that is offline or hits a server error reloads nothing`() =
        runBlocking {
            server.enqueue(status(500))
            assertEquals(TrashResult.Offline, trash.restore(trashItem(1, "PROJECT")))
            server.close()
            assertEquals(TrashResult.Offline, trash.restore(trashItem(1, "PROJECT")))
            assertEquals(emptyList<String>(), reloaded)
        }

    @Test
    fun `purge failures are reported by status`() =
        runBlocking {
            server.enqueue(status(404))
            assertEquals(TrashResult.Failed(TrashRepository.NOT_FOUND), trash.purge(trashItem(1, "NOTE")))
            server.enqueue(status(409))
            assertEquals(TrashResult.Failed(409), trash.purge(trashItem(1, "NOTE")))
            server.enqueue(status(503))
            assertEquals(TrashResult.Offline, trash.purge(trashItem(1, "NOTE")))
        }

    @Test
    fun `a reload that fails after a restore does not fail the restore`() =
        runBlocking {
            val failing = TrashRepository(trashApi(), { throw IOException("fictitious outage") }, appScope)
            server.enqueue(status(204))
            assertEquals(TrashResult.Ok(Unit), failing.restore(trashItem(1, "TASK")))
        }

    @Test
    fun `a restore returns without waiting for the reload of its lists`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val slow =
                TrashRepository(trashApi(), { type ->
                    gate.await()
                    reloaded += type
                }, appScope)
            server.enqueue(status(204))

            assertEquals(TrashResult.Ok(Unit), slow.restore(trashItem(1, "TASK")))
            assertEquals(emptyList<String>(), reloaded)

            gate.complete(Unit)
            assertEquals(listOf("TASK"), reloaded)
        }

    @Test
    fun `the reload after a restore goes on when the screen that restored is gone`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<String>()
            val slow =
                TrashRepository(trashApi(), { type ->
                    started.complete(Unit)
                    gate.await()
                    finished.complete(type)
                }, appScope)
            server.enqueue(status(204))
            // Stands in for the view model scope, which is cancelled when the user leaves the Trash.
            val screen = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            val restored = screen.async { slow.restore(trashItem(1, "PROJECT")) }
            assertEquals(TrashResult.Ok(Unit), restored.await())
            withTimeout(5_000) { started.await() }
            screen.cancel()

            gate.complete(Unit)
            assertEquals("PROJECT", withTimeout(5_000) { finished.await() })
        }

    @Test
    fun `restoring a task or project reloads the GTD cache, other types do not`() =
        runBlocking {
            val gtd = mockk<GtdSync> { coEvery { refreshAll() } returns RefreshResult.Refreshed }
            val wired = TrashRepository(trashApi(), gtd, appScope)
            repeat(11) { server.enqueue(status(204)) }

            wired.restore(trashItem(1, "NOTE"))
            coVerify(exactly = 0) { gtd.refreshAll() }
            wired.restore(trashItem(2, "TASK"))
            coVerify(exactly = 1) { gtd.refreshAll() }
            wired.restore(trashItem(3, "PROJECT"))
            coVerify(exactly = 2) { gtd.refreshAll() }
            listOf("EVENT", "EMAIL", "FILE", "FOLDER", "CONTACT", "BOOKMARK", "JOURNAL_ENTRY", "HABIT")
                .forEachIndexed { i, type -> wired.restore(trashItem(10 + i, type)) }
            coVerify(exactly = 2) { gtd.refreshAll() }
        }

    @Test
    fun `the injected repository reloads the GTD cache in the background after a task restore`() =
        runBlocking {
            val gtd = mockk<GtdSync> { coEvery { refreshAll() } returns RefreshResult.Refreshed }
            server.enqueue(status(204))

            assertEquals(TrashResult.Ok(Unit), TrashRepository(trashApi(), gtd).restore(trashItem(1, "TASK")))

            coVerify(exactly = 1, timeout = 5_000) { gtd.refreshAll() }
        }

    @Test
    fun `purging a task does not reload the GTD cache`() =
        runBlocking {
            val gtd = mockk<GtdSync> { coEvery { refreshAll() } returns RefreshResult.Refreshed }
            server.enqueue(status(204))
            assertEquals(TrashResult.Ok(Unit), TrashRepository(trashApi(), gtd).purge(trashItem(1, "TASK")))
            coVerify(exactly = 0) { gtd.refreshAll() }
        }

    private fun trashApi(): TrashApi =
        Retrofit
            .Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TrashApi::class.java)
}
