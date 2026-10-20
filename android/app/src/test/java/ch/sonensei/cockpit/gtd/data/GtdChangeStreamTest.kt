package ch.sonensei.cockpit.gtd.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
                listOf(ChangeSignal.Connected, ChangeSignal.Changed("TASK"), ChangeSignal.Reconnected, ChangeSignal.Changed("TASK")),
                signals,
            )
        }

    /**
     * Observes [views] while the stream's one connection delivers [events] only after every view finished its first
     * load (later connections are refused, so no reconnect reloads anything); returns the API calls per path.
     */
    private suspend fun loadsWith(
        events: String,
        vararg views: Flow<Cached<*>>,
    ): Map<String, Int> =
        coroutineScope {
            val loaded = CountDownLatch(views.size)
            val calls = AtomicInteger()
            dispatcher.events = {
                if (calls.incrementAndGet() == 1) {
                    loaded.await(5, TimeUnit.SECONDS)
                    sse(events)
                } else {
                    status(503)
                }
            }
            val jobs =
                views.map { view ->
                    launch(Dispatchers.Default) {
                        var first = true
                        view.collect {
                            if (first && it.refreshedAt != null) {
                                first = false
                                loaded.countDown()
                            }
                        }
                    }
                }
            // Two refused reconnects after the one connection: its changes were all delivered by then.
            withTimeout(5_000) { while (calls.get() < 3) delay(10) }
            delay(300)
            jobs.forEach { it.cancel() }
            dispatcher.requests
                .map { it.url.encodedPath }
                .filter { it != "/api/v1/events" }
                .groupingBy { it }
                .eachCount()
        }

    private fun enqueueViews() {
        repeat(5) {
            dispatcher.enqueue("$TASKS|INBOX", json(taskPage(taskJson(1, "Call the plumber"))))
            dispatcher.enqueue(CONTEXTS, json("""{"items":[${contextJson(1, "@home")}]}"""))
            dispatcher.enqueue(PROJECTS, json(taskPage(projectJson(1, "Move house"))))
        }
    }

    @Test
    fun `a task change on an open stream reloads the inbox`() =
        runBlocking {
            enqueueViews()

            val loads = loadsWith(change("TASK"), gtd.tasks.observe(TaskView.INBOX))

            assertEquals(mapOf(TASKS to 2), loads)
        }

    @Test
    fun `other change types and the first connection do not reload the inbox`() =
        runBlocking {
            enqueueViews()

            val loads = loadsWith(change("CONTEXT") + change("PROJECT") + change("AREA"), gtd.tasks.observe(TaskView.INBOX))

            assertEquals(mapOf(TASKS to 1), loads)
        }

    @Test
    fun `a context change reloads contexts but not projects`() =
        runBlocking {
            enqueueViews()

            val loads = loadsWith(change("CONTEXT"), gtd.contexts.observe(), gtd.projects.observe())

            assertEquals(mapOf(CONTEXTS to 2, PROJECTS to 1), loads)
        }

    @Test
    fun `a project change reloads projects but not contexts`() =
        runBlocking {
            enqueueViews()

            val loads = loadsWith(change("PROJECT"), gtd.contexts.observe(), gtd.projects.observe())

            assertEquals(mapOf(CONTEXTS to 1, PROJECTS to 2), loads)
        }

    @Test
    fun `a task change reloads projects, whose stalled flag depends on tasks`() =
        runBlocking {
            enqueueViews()

            val loads = loadsWith(change("TASK"), gtd.contexts.observe(), gtd.projects.observe())

            assertEquals(mapOf(CONTEXTS to 1, PROJECTS to 2), loads)
        }

    @Test
    fun `the connection is closed once the last collector left for the stop timeout`() =
        runBlocking {
            val connections = AtomicInteger()
            dispatcher.events = {
                connections.incrementAndGet()
                // Ends at once, so the stream reconnects every 50 ms while it is shared.
                sse("")
            }
            GtdHarness(server, dispatcher, stopTimeoutMs = 500).use { short ->
                val job = launch(Dispatchers.Default) { short.changes.signals.collect { } }
                withTimeout(5_000) { while (connections.get() < 2) delay(10) }
                job.cancel()
                val atLeave = connections.get()

                delay(1_500)
                val afterTimeout = connections.get()
                delay(500)

                assertTrue("kept open during the timeout: $atLeave -> $afterTimeout", afterTimeout > atLeave)
                assertEquals("closed after the timeout", afterTimeout, connections.get())
            }
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
