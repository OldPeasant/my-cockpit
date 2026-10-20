package ch.sonensei.cockpit.gtd.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ProjectContextRepositoryTest {
    private val dispatcher = RoutingDispatcher()
    private val server = MockWebServer().apply { this.dispatcher = this@ProjectContextRepositoryTest.dispatcher }
    private lateinit var gtd: GtdHarness

    @Before
    fun setUp() {
        server.start()
        gtd = GtdHarness(server, dispatcher)
    }

    @After
    fun tearDown() {
        gtd.close()
        server.close()
    }

    private fun contexts(vararg contexts: String) = json("""{"items":[${contexts.joinToString(",")}]}""")

    private suspend fun projectTitles() =
        gtd.dao
            .observeProjects()
            .first()
            .map { it.title }

    private suspend fun contextNames() =
        gtd.dao
            .observeContexts()
            .first()
            .map { it.name }

    @Test
    fun `projects of every status are cached in server order across pages`() =
        runBlocking {
            dispatcher.enqueue(PROJECTS, json(taskPage(projectJson(5, "Write a novel", "SOMEDAY"), nextCursor = "p2")))
            dispatcher.enqueue(PROJECTS, json(taskPage(projectJson(1, "Paint the fence", "COMPLETED"), projectJson(3, "Move house"))))

            assertEquals(RefreshResult.Refreshed, gtd.projects.refresh())

            assertEquals(listOf("Write a novel", "Paint the fence", "Move house"), projectTitles())
            assertEquals(listOf(null, "p2"), dispatcher.requests.map { it.url.queryParameter("cursor") })
            assertEquals(listOf(null, null), dispatcher.requests.map { it.url.queryParameter("status") })
            assertEquals(
                "200",
                dispatcher.requests
                    .first()
                    .url
                    .queryParameter("limit"),
            )
        }

    @Test
    fun `a project refresh replaces the cached projects`() =
        runBlocking {
            dispatcher.enqueue(PROJECTS, json(taskPage(projectJson(1, "Old"), projectJson(2, "Gone"))))
            dispatcher.enqueue(PROJECTS, json(taskPage(projectJson(1, "Renamed"))))
            gtd.projects.refresh()

            gtd.projects.refresh()

            assertEquals(listOf("Renamed"), projectTitles())
        }

    @Test
    fun `an unreachable project server keeps the cache and marks every view offline`() =
        runBlocking {
            dispatcher.enqueue(PROJECTS, json(taskPage(projectJson(1, "Kept"))))
            dispatcher.enqueue(PROJECTS, status(503))
            gtd.projects.refresh()

            assertEquals(RefreshResult.Offline, gtd.projects.refresh())

            assertEquals(listOf("Kept"), projectTitles())
            val cached = withTimeout(5_000) { gtd.projects.observe().first { it.offline } }
            assertEquals(listOf("Kept"), cached.data.map { it.title })
            assertEquals(Instant.parse("2026-10-20T10:00:00Z"), cached.refreshedAt)
            // One shared flag: the task views are offline too.
            assertTrue(withTimeout(5_000) { gtd.tasks.observe(TaskView.INBOX).first() }.offline)
        }

    @Test
    fun `a rejected or unreadable project answer keeps the cache without going offline`() =
        runBlocking {
            dispatcher.enqueue(PROJECTS, json(taskPage(projectJson(1, "Kept"))))
            dispatcher.enqueue(PROJECTS, status(403))
            dispatcher.enqueue(PROJECTS, json("""{"items":[{"id":"${uuid(2)}","title":"No status"}]}"""))
            gtd.projects.refresh()

            assertEquals(RefreshResult.Failed(403), gtd.projects.refresh())
            assertEquals(RefreshResult.Failed(null), gtd.projects.refresh())

            assertFalse(gtd.connectivity.offline.value)
            assertEquals(listOf("Kept"), projectTitles())
        }

    @Test
    fun `contexts are cached in server order and replaced on refresh`() =
        runBlocking {
            dispatcher.enqueue(CONTEXTS, contexts(contextJson(9, "@phone"), contextJson(2, "@home")))
            dispatcher.enqueue(CONTEXTS, contexts(contextJson(2, "@home")))

            assertEquals(RefreshResult.Refreshed, gtd.contexts.refresh())
            assertEquals(listOf("@phone", "@home"), contextNames())
            assertEquals(
                "#3366ff",
                gtd.dao
                    .observeContexts()
                    .first()
                    .first()
                    .color,
            )

            assertEquals(RefreshResult.Refreshed, gtd.contexts.refresh())
            assertEquals(listOf("@home"), contextNames())
        }

    @Test
    fun `context refresh failures keep the cache`() =
        runBlocking {
            dispatcher.enqueue(CONTEXTS, contexts(contextJson(1, "@home")))
            dispatcher.enqueue(CONTEXTS, status(401))
            dispatcher.enqueue(CONTEXTS, json("not json"))
            dispatcher.enqueue(CONTEXTS, status(500))
            gtd.contexts.refresh()

            assertEquals(RefreshResult.Failed(401), gtd.contexts.refresh())
            assertEquals(RefreshResult.Failed(null), gtd.contexts.refresh())
            assertFalse(gtd.connectivity.offline.value)
            assertEquals(RefreshResult.Offline, gtd.contexts.refresh())
            assertTrue(gtd.connectivity.offline.value)

            assertEquals(listOf("@home"), contextNames())
        }

    @Test
    fun `contexts served offline before anything was loaded are empty and never refreshed`() =
        runBlocking {
            server.close()

            val cached = withTimeout(5_000) { gtd.contexts.observe().first { it.offline } }

            assertEquals(emptyList<GtdContext>(), cached.data)
            assertNull(cached.refreshedAt)
            assertTrue(cached.readOnly)
        }

    @Test
    fun `observing projects loads them once on start`() =
        runBlocking {
            dispatcher.enqueue(PROJECTS, json(taskPage(projectJson(1, "Fresh"))))

            // Rows and their refresh time come from two Room queries, so wait for both (see TW-1 in the test report).
            val cached = withTimeout(5_000) { gtd.projects.observe().first { it.data.isNotEmpty() && it.refreshedAt != null } }

            assertEquals(listOf("Fresh"), cached.data.map { it.title })
            assertFalse(cached.offline)
            assertEquals(Instant.parse("2026-10-20T10:00:00Z"), cached.refreshedAt)
        }
}
