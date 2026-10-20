package ch.sonensei.cockpit.core.events

import ch.sonensei.cockpit.core.events.api.EventsController
import ch.sonensei.cockpit.core.events.infra.SseEventHub
import io.kotest.matchers.shouldBe
import jakarta.servlet.AsyncEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockAsyncContext
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.io.IOException
import java.time.Duration

/** Heartbeats and cleanup of the hub, without a Spring context (short heartbeat). */
class SseEventHubTests {
	private val hub = SseEventHub(Duration.ofMillis(50))
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

	private fun connect(): MvcResult = mockMvc.get(EventsController.PATH).andExpect { request { asyncStarted() } }.andReturn()

	private fun MvcResult.asyncContext() = request.asyncContext as MockAsyncContext

	private fun awaitUntil(condition: () -> Boolean) {
		val deadline = System.nanoTime() + 5_000_000_000
		while (!condition()) {
			check(System.nanoTime() < deadline) { "Condition not met in time" }
			Thread.sleep(10)
		}
	}
}
