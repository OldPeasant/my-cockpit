package ch.sonensei.cockpit.gtd.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GtdChangeStreamTest {
    private val dispatcher = RoutingDispatcher()
    private val server = MockWebServer().apply { this.dispatcher = this@GtdChangeStreamTest.dispatcher }
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

    private fun sse(body: String) =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "text/event-stream")
            .body(body)
            .build()

    private fun change(type: String) =
        "event: change\ndata: {\"type\":\"$type\",\"id\":\"${uuid(1)}\",\"op\":\"UPDATED\",\"at\":\"2026-10-20T10:00:00Z\"}\n\n"

    @Test
    fun `stream signals the connection, then each change, skipping comments and other events`() =
        runBlocking {
            dispatcher.events = {
                sse(":heartbeat\n\nevent: other\ndata: x\n\nevent: change\ndata: not json\n\n" + change("TASK") + change("CONTEXT"))
            }

            val signals =
                withTimeout(5_000) {
                    gtd.changes.signals
                        .take(3)
                        .toList()
                }

            assertEquals(
                listOf(ChangeSignal.Connected, ChangeSignal.Changed("TASK"), ChangeSignal.Changed("CONTEXT")),
                signals,
            )
            assertEquals("text/event-stream", dispatcher.requests.first().headers["Accept"])
        }

    @Test
    fun `stream reconnects after the server ends it or refuses it`() =
        runBlocking {
            var calls = 0
            dispatcher.events = {
                calls++
                if (calls == 2) status(503) else sse(change("TASK"))
            }

            val signals =
                withTimeout(5_000) {
                    gtd.changes.signals
                        .take(4)
                        .toList()
                }

            assertEquals(
                listOf(ChangeSignal.Connected, ChangeSignal.Changed("TASK"), ChangeSignal.Connected, ChangeSignal.Changed("TASK")),
                signals,
            )
        }

    @Test
    fun `a task change while the inbox is observed reloads it`() =
        runBlocking {
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(1, "Before"))))
            repeat(20) { dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(1, "After")))) }
            dispatcher.events = { sse(change("TASK")) }

            val cached = withTimeout(5_000) { gtd.tasks.observe(TaskView.INBOX).first { it.data.firstOrNull()?.title == "After" } }

            assertEquals(1, cached.data.size)
        }

    @Test
    fun `contexts and projects reload on their own change types`() =
        runBlocking {
            dispatcher.enqueue(CONTEXTS, json("""{"items":[${contextJson(1, "@home")}]}"""))
            repeat(20) { dispatcher.enqueue(CONTEXTS, json("""{"items":[${contextJson(1, "@office")}]}""")) }
            dispatcher.enqueue(PROJECTS, json(taskPage(projectJson(1, "Move house"))))
            repeat(20) { dispatcher.enqueue(PROJECTS, json(taskPage(projectJson(1, "Move flat")))) }
            dispatcher.events = { sse(change("CONTEXT") + change("PROJECT")) }

            withTimeout(5_000) { gtd.contexts.observe().first { it.data.firstOrNull()?.name == "@office" } }
            withTimeout(5_000) { gtd.projects.observe().first { it.data.firstOrNull()?.title == "Move flat" } }
            Unit
        }

    @Test
    fun `parser joins data lines and ignores events without data`() {
        val parser = SseParser()
        assertNull(parser.feed("event: change"))
        assertNull(parser.feed("data: a"))
        assertNull(parser.feed("data:b"))
        assertNull(parser.feed("id: 7"))
        assertEquals(SseEvent("change", "a\nb"), parser.feed(""))
        assertNull(parser.feed("event: empty"))
        assertNull(parser.feed(""))
        assertNull(parser.feed("data"))
        assertEquals(SseEvent("message", ""), parser.feed(""))
    }
}
