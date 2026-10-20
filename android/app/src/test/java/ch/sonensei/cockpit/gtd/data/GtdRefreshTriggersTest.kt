package ch.sonensei.cockpit.gtd.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Which stream signals reload a view, and how the stream backs off when the server refuses it. */
@RunWith(AndroidJUnit4::class)
class GtdRefreshTriggersTest {
    private val dispatcher = RoutingDispatcher()
    private val server = MockWebServer().apply { this.dispatcher = this@GtdRefreshTriggersTest.dispatcher }
    private var gtd: GtdHarness? = null

    private fun harness(retryDelaysMs: List<Long> = listOf(50L)): GtdHarness {
        server.start()
        return GtdHarness(server, dispatcher, retryDelaysMs).also { gtd = it }
    }

    @After
    fun tearDown() {
        gtd?.close()
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
        "event: change\ndata: {\"type\":\"$type\",\"id\":\"${uuid(1)}\",\"op\":\"CREATED\",\"at\":\"2026-10-20T10:00:00Z\"}\n\n"

    /** The first connection carries [body]; every later one is refused, so no further Connected signal arrives. */
    private fun oneConnection(body: String): AtomicInteger {
        val calls = AtomicInteger()
        dispatcher.events = { if (calls.incrementAndGet() == 1) sse(body) else status(503) }
        return calls
    }

    /** How often `triggers.on(types)` fires until the stream has been called [until] times (refused at the end). */
    private suspend fun CoroutineScope.countTriggers(
        vararg types: String,
        calls: AtomicInteger,
        until: Int = 3,
    ): Int {
        val count = AtomicInteger()
        val job = launch(Dispatchers.Default) { gtd!!.triggers.on(*types).collect { count.incrementAndGet() } }
        // Two refused reconnects after the last connection: its signals were all delivered by then.
        withTimeout(5_000) { while (calls.get() < until) delay(10) }
        job.cancel()
        return count.get()
    }

    @Test
    fun `a view reloads at start and on its own change types only`() =
        runBlocking {
            harness()
            val calls = oneConnection(change("CONTEXT") + change("PROJECT") + change("TASK") + change("AREA"))

            // Start + TASK; the first connection is covered by the load at start.
            assertEquals(2, countTriggers(ChangeTypes.TASK, calls = calls))
        }

    @Test
    fun `a view reloads after a reconnect, which may have missed changes`() =
        runBlocking {
            harness()
            val calls = AtomicInteger()
            // Connected (ends), refused, connected again (ends), refused...
            dispatcher.events = { if (calls.incrementAndGet() in setOf(1, 3)) sse("") else status(503) }

            // Start + Reconnected.
            assertEquals(2, countTriggers(ChangeTypes.TASK, calls = calls, until = 5))
        }

    @Test
    fun `a view still loads at start when the stream cannot connect`() =
        runBlocking {
            harness()
            val calls = AtomicInteger()
            dispatcher.events = {
                calls.incrementAndGet()
                status(503)
            }

            assertEquals(1, countTriggers(ChangeTypes.TASK, calls = calls))
        }

    @Test
    fun `a view listening to several types reloads on each of them`() =
        runBlocking {
            harness()
            val calls = oneConnection(change("CONTEXT") + change("PROJECT") + change("TASK"))

            // Start + PROJECT + TASK, as the Projects view (stalled depends on tasks).
            assertEquals(3, countTriggers(ChangeTypes.PROJECT, ChangeTypes.TASK, calls = calls))
            assertTrue(gtd!!.scheduler.scheduled > 0)
        }

    @Test
    fun `a refused stream backs off further with each failure and resets after a connection`() =
        runBlocking {
            harness(retryDelaysMs = listOf(50L, 1_000L))
            val times = CopyOnWriteArrayList<Long>()
            dispatcher.events = {
                times += System.nanoTime()
                // Refused, refused, connected (ends at once), refused...
                if (times.size == 3) sse(change("TASK")) else status(503)
            }
            val job = launch(Dispatchers.Default) { gtd!!.changes.signals.collect { } }

            withTimeout(10_000) { while (times.size < 5) delay(10) }
            job.cancel()

            val gaps = times.zipWithNext { a, b -> TimeUnit.NANOSECONDS.toMillis(b - a) }
            // 1st failure -> first delay, 2nd failure -> second delay.
            assertTrue("gaps $gaps", gaps[0] in 50L until 1_000L)
            assertTrue("gaps $gaps", gaps[1] >= 1_000L)
            // A stream the server ended reconnects after the first delay, and the failure count starts over.
            assertTrue("gaps $gaps", gaps[2] in 50L until 1_000L)
            assertTrue("gaps $gaps", gaps[3] in 50L until 1_000L)
        }

    @Test
    fun `failures beyond the last delay keep using the last delay`() =
        runBlocking {
            harness(retryDelaysMs = listOf(20L, 60L))
            val times = CopyOnWriteArrayList<Long>()
            dispatcher.events = {
                times += System.nanoTime()
                status(503)
            }
            val job = launch(Dispatchers.Default) { gtd!!.changes.signals.collect { } }

            withTimeout(10_000) { while (times.size < 5) delay(10) }
            job.cancel()

            val gaps = times.zipWithNext { a, b -> TimeUnit.NANOSECONDS.toMillis(b - a) }
            assertTrue("gaps $gaps", gaps.drop(1).all { it >= 60L })
        }
}
