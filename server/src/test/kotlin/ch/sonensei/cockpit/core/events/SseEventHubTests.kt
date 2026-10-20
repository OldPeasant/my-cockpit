package ch.sonensei.cockpit.core.events

import ch.sonensei.cockpit.core.events.api.EventsController
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.events.infra.SseEventHub
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import jakarta.servlet.AsyncEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockAsyncContext
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Heartbeats and cleanup of the hub, without a Spring context (short heartbeat). */
class SseEventHubTests {
	private val hub = SseEventHub(Duration.ofMillis(50), STREAM_TIMEOUT)
	private val mockMvc = MockMvcBuilders.standaloneSetup(EventsController(hub)).build()

	@AfterEach
	fun stop() = hub.destroy()

	@Test
	fun `connected clients get heartbeat comments`() {
		val result = connect()
		awaitUntil {
			result.response
				.getContentAsString(Charsets.UTF_8)
				.split(":heartbeat\n\n")
				.size > 2
		}
	}

	@Test
	fun `a client whose stream completes is removed`() {
		val result = connect()
		hub.clientCount shouldBe 1

		result.asyncContext().complete()

		hub.clientCount shouldBe 0
	}

	@Test
	fun `a client whose stream fails is removed`() {
		val result = connect()
		val context = result.asyncContext()

		context.listeners.forEach { it.onError(AsyncEvent(context, IOException("Broken pipe"))) }

		hub.clientCount shouldBe 0
	}

	@Test
	fun `a new stream starts with a connected comment and heartbeats are comment lines without data`() {
		val result = connect()

		result.body() shouldStartWith ":connected\n\n"
		awaitUntil { result.body().contains(":heartbeat\n\n") }
		result.body() shouldNotContain "data:"
		result.body() shouldNotContain "event:"
	}

	@Test
	fun `a client whose stream times out is removed`() {
		val result = connect()
		val context = result.asyncContext()

		context.listeners.forEach { it.onTimeout(AsyncEvent(context)) }

		hub.clientCount shouldBe 0
	}

	@Test
	fun `an event outside a transaction reaches every connected client as a named JSON event`() {
		val first = connect()
		val second = connect()
		val id = UUID.fromString("0192f0c4-7a1e-7c3d-9b2a-1f2e3d4c5b6a")

		hub.onChange(ChangeEvent("TASK", id, ChangeOp.UPDATED, Instant.parse("2026-03-14T09:26:53Z")))

		for (result in listOf(first, second)) {
			awaitUntil { result.body().contains(id.toString()) }
			result.body() shouldContain "event:${SseEventHub.EVENT_NAME}\ndata:{"
			result.body() shouldContain "\"id\":\"$id\""
			result.body() shouldContain "\"op\":\"UPDATED\""
		}
	}

	@Test
	fun `a client that cannot be written to is dropped and the others still get the event`() {
		val healthy = connect()
		val broken = hub.subscribe()
		hub.clientCount shouldBe 2
		// Completed but never attached to a response: the next send throws, as for a client that went away.
		broken.complete()
		val id = UUID.fromString("0192f0c4-7a1e-7c3d-9b2a-1f2e3d4c5b6b")

		hub.onChange(ChangeEvent("TASK", id, ChangeOp.CREATED, Instant.parse("2026-03-14T09:26:53Z")))

		awaitUntil { healthy.body().contains(id.toString()) }
		awaitUntil { hub.clientCount == 1 }
	}

	@Test
	fun `a heartbeat alone drops a client that cannot be written to`() {
		val healthy = connect()
		val broken = hub.subscribe()
		broken.complete()
		hub.clientCount shouldBe 2

		// No event is published: only the next heartbeat write can reveal the dead client.
		awaitUntil { hub.clientCount == 1 }
		awaitUntil { healthy.body().contains(":heartbeat\n\n") }
	}

	@Test
	fun `destroy completes every stream and forgets the clients`() {
		val results = listOf(connect(), connect())
		hub.clientCount shouldBe 2

		hub.destroy()

		hub.clientCount shouldBe 0
		// Completing an emitter ends its async request; a still-open stream would time out here.
		results.forEach { it.getAsyncResult(5_000) }
	}

	@Test
	fun `the heartbeat interval must be positive`() {
		for (heartbeat in listOf(Duration.ZERO, Duration.ofSeconds(-25))) {
			shouldThrow<IllegalArgumentException> { SseEventHub(heartbeat, STREAM_TIMEOUT) }
		}
	}

	@Test
	fun `the stream timeout must be positive`() {
		for (timeout in listOf(Duration.ZERO, Duration.ofMinutes(-15))) {
			shouldThrow<IllegalArgumentException> { SseEventHub(Duration.ofMillis(50), timeout) }
		}
	}

	@Test
	fun `a stream uses the configured timeout`() {
		connect().asyncContext().timeout shouldBe STREAM_TIMEOUT.toMillis()
	}

	@Test
	fun `a stream that times out ends normally instead of with an error`() {
		val result = connect()
		val context = result.asyncContext()

		context.listeners.forEach { it.onTimeout(AsyncEvent(context)) }

		hub.clientCount shouldBe 0
		// Without completing on timeout Spring resolves the request with an AsyncRequestTimeoutException (503).
		result.getAsyncResult(5_000) shouldBe null
		mockMvc.perform(asyncDispatch(result)).andReturn().resolvedException shouldBe null
	}

	@Test
	fun `an event after the hub is destroyed is dropped without failing the publisher`() {
		hub.destroy()

		hub.onChange(ChangeEvent("TASK", UUID.fromString("0192f0c4-7a1e-7c3d-9b2a-1f2e3d4c5b6c"), ChangeOp.DELETED, Instant.EPOCH))

		hub.clientCount shouldBe 0
	}

	private fun MvcResult.body() = response.getContentAsString(Charsets.UTF_8)

	private fun connect(): MvcResult = mockMvc.get(EventsController.PATH).andExpect { request { asyncStarted() } }.andReturn()

	private fun MvcResult.asyncContext() = request.asyncContext as MockAsyncContext

	private fun awaitUntil(condition: () -> Boolean) {
		val deadline = System.nanoTime() + 5_000_000_000
		while (!condition()) {
			check(System.nanoTime() < deadline) { "Condition not met in time" }
			Thread.sleep(10)
		}
	}

	private companion object {
		/** Not the default (15 min), to show the configured value is used. MockMvc never fires it on its own. */
		val STREAM_TIMEOUT: Duration = Duration.ofSeconds(90)
	}
}
