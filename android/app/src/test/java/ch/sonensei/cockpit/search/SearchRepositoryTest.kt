package ch.sonensei.cockpit.search

import ch.sonensei.cockpit.core.network.generated.apis.SearchApi
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
import java.util.UUID

/** `GET /api/v1/search` against a fake server: what is asked, how groups are mapped, and how failures are reported. */
class SearchRepositoryTest {
    private val server = MockWebServer()
    private lateinit var search: SearchRepository

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
    ) = """{"id":"${id(n)}","type":"$type","title":"$title","updatedAt":"2026-10-01T09:00:00Z"$extra}"""

    private val empty = """{"groups":[]}"""

    @Before
    fun setUp() {
        server.start()
        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
        search = SearchRepository(retrofit.create(SearchApi::class.java))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `asks the search endpoint with the trimmed text, all types and the largest page`() =
        runBlocking {
            server.enqueue(json(empty))

            search.search("  trip plan ", emptySet())

            val url = server.takeRequest().url
            assertEquals("/api/v1/search", url.encodedPath)
            assertEquals("trip plan", url.queryParameter("q"))
            assertEquals(emptyList<String>(), url.queryParameterValues("types"))
            assertEquals("50", url.queryParameter("limit"))
        }

    @Test
    fun `a filter asks for its item types, files for files and folders`() =
        runBlocking {
            server.enqueue(json(empty))

            search.search("plan", setOf(SearchFilter.Files))

            assertEquals(listOf("FILE", "FOLDER"), server.takeRequest().url.queryParameterValues("types"))
        }

    @Test
    fun `several filters ask for all their item types`() =
        runBlocking {
            server.enqueue(json(empty))

            search.search("plan", setOf(SearchFilter.Journal, SearchFilter.Tasks, SearchFilter.Emails))

            assertEquals(
                setOf("JOURNAL_ENTRY", "TASK", "EMAIL"),
                server
                    .takeRequest()
                    .url
                    .queryParameterValues("types")
                    .toSet(),
            )
        }

    @Test
    fun `every filter asks for the server's item type names`() =
        runBlocking {
            val expected =
                mapOf(
                    SearchFilter.Tasks to listOf("TASK"),
                    SearchFilter.Projects to listOf("PROJECT"),
                    SearchFilter.Notes to listOf("NOTE"),
                    SearchFilter.Events to listOf("EVENT"),
                    SearchFilter.Emails to listOf("EMAIL"),
                    SearchFilter.Contacts to listOf("CONTACT"),
                    SearchFilter.Files to listOf("FILE", "FOLDER"),
                    SearchFilter.Bookmarks to listOf("BOOKMARK"),
                    SearchFilter.Journal to listOf("JOURNAL_ENTRY"),
                )
            assertEquals(SearchFilter.entries.toSet(), expected.keys)
            for ((filter, types) in expected) {
                server.enqueue(json(empty))
                search.search("plan", setOf(filter))
                assertEquals(filter.name, types, server.takeRequest().url.queryParameterValues("types"))
            }
        }

    @Test
    fun `a long text is cut to 200 characters after trimming`() =
        runBlocking {
            server.enqueue(json(empty))

            search.search("   " + "a".repeat(250), emptySet())

            assertEquals("a".repeat(200), server.takeRequest().url.queryParameter("q"))
        }

    @Test
    fun `a text of exactly 200 characters is sent whole`() =
        runBlocking {
            server.enqueue(json(empty))

            search.search("b".repeat(200), emptySet())

            assertEquals("b".repeat(200), server.takeRequest().url.queryParameter("q"))
        }

    @Test
    fun `a blank text finds nothing without asking the server`() =
        runBlocking {
            assertEquals(SearchResult.Ok(emptyList()), search.search(" \t ", setOf(SearchFilter.Tasks)))
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `groups are mapped in the server's order, blank subtitles and links dropped, empty groups left out`() =
        runBlocking {
            server.enqueue(
                json(
                    """{"groups":[
                    |{"type":"TASK","items":[${item(1, "TASK", "Plan trip", ""","subtitle":"Next","deepLink":"/tasks/${id(1)}"""")},
                    |${item(2, "TASK", "Plan budget", ""","subtitle":" ","deepLink":"","icon":"task_alt"""")}]},
                    |{"type":"NOTE","items":[]},
                    |{"type":"FOLDER","items":[${item(3, "FOLDER", "Plans")}]}]}
                    """.trimMargin().replace("\n", ""),
                ),
            )

            val result = search.search("plan", emptySet()) as SearchResult.Ok

            assertEquals(
                listOf(
                    SearchGroup(
                        "TASK",
                        listOf(
                            SearchHit(id(1), "TASK", "Plan trip", "Next", "/tasks/${id(1)}"),
                            SearchHit(id(2), "TASK", "Plan budget", null, null),
                        ),
                    ),
                    SearchGroup("FOLDER", listOf(SearchHit(id(3), "FOLDER", "Plans", null, null))),
                ),
                result.groups,
            )
        }

    @Test
    fun `server errors count as offline`() =
        runBlocking {
            for (code in listOf(500, 502, 503)) {
                server.enqueue(status(code))
                assertEquals("status $code", SearchResult.Offline, search.search("plan", emptySet()))
            }
        }

    @Test
    fun `no connection counts as offline`() =
        runBlocking {
            server.close()

            assertEquals(SearchResult.Offline, search.search("plan", emptySet()))
        }

    @Test
    fun `a refusal reports its status`() =
        runBlocking {
            for (code in listOf(400, 401, 403, 404, 429)) {
                server.enqueue(status(code))
                assertEquals("status $code", SearchResult.Failed(code), search.search("plan", emptySet()))
            }
        }

    @Test
    fun `a success without a body fails`() =
        runBlocking {
            server.enqueue(status(204))

            assertEquals(SearchResult.Failed(204), search.search("plan", emptySet()))
        }

    @Test
    fun `an unreadable answer fails without a status`() =
        runBlocking {
            server.enqueue(json("""{"groups":[{"type":"UNKNOWN_KIND","items":[]}]}"""))
            assertEquals(SearchResult.Failed(null), search.search("plan", emptySet()))

            server.enqueue(json("not json"))
            val result = search.search("plan", emptySet())
            assertNull((result as SearchResult.Failed).status)
        }
}
