package ch.sonensei.cockpit.core.events

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.api.EventsController
import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.events.domain.ClientNotification
import ch.sonensei.cockpit.core.events.infra.SseEventHub
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.MediaType
import org.springframework.mock.web.MockAsyncContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration

private data class Greeting(
	val text: String,
	val count: Int,
)

/** [ClientNotification] and [SseEventHub.onNotification] without a Spring context. */
class ClientNotificationHubTests {
	private val hub = SseEventHub(Duration.ofMinutes(5), Duration.ofMinutes(15))
	private val mockMvc = MockMvcBuilders.standaloneSetup(EventsController(hub)).build()

	@AfterEach
	fun stop() = hub.destroy()

	@Test
	fun `a notification reaches every connected client as an SSE event of its name with JSON data`() {
		val first = connect()
		val second = connect()

		hub.onNotification(ClientNotification("reminder", Greeting("Fictitious hello", 2)))

		for (result in listOf(first, second)) {
			// Waits for the whole event: a stream may be read while an event is half written.
			awaitUntil { result.body().contains("event:reminder\ndata:{\"text\":\"Fictitious hello\",\"count\":2}\n\n") }
			result.body() shouldNotContain "event:${SseEventHub.EVENT_NAME}\n"
		}
	}

	@Test
	fun `a notification after the hub is destroyed is dropped without failing the publisher`() {
		hub.destroy()

		hub.onNotification(ClientNotification("reminder", Greeting("Too late", 0)))

		hub.clientCount shouldBe 0
	}

	@Test
	fun `a notification needs a name other than the change event's`() {
		for (name in listOf("", "   ", SseEventHub.EVENT_NAME)) {
			shouldThrow<IllegalArgumentException> { ClientNotification(name, Greeting("x", 0)) }
		}
	}

	private fun MvcResult.body() = response.getContentAsString(Charsets.UTF_8)

	private fun connect(): MvcResult = mockMvc.get(EventsController.PATH).andExpect { request { asyncStarted() } }.andReturn()

	private fun awaitUntil(condition: () -> Boolean) {
		val deadline = System.nanoTime() + 5_000_000_000
		while (!condition()) {
			check(System.nanoTime() < deadline) { "Condition not met in time" }
			Thread.sleep(10)
		}
	}
}

/** A [ClientNotification] published in a real (Postgres) transaction reaches clients only when it commits. */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class ClientNotificationStreamTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val publisher: ApplicationEventPublisher,
	@Autowired private val changes: ChangeEventPublisher,
	@Autowired private val transactions: TransactionTemplate,
) : PostgresTestBase() {
	private val streams = mutableListOf<MvcResult>()

	@AfterEach
	fun closeStreams() {
		streams.forEach { (it.request.asyncContext as MockAsyncContext).complete() }
		streams.clear()
	}

	@Test
	fun `a notification of a rolled-back transaction is dropped, a committed one is sent after the commit`() {
		val stream = connect()
		val rolledBack = UuidV7.generate().toString()
		val committed = UuidV7.generate().toString()

		transactions.executeWithoutResult { status ->
			publisher.publishEvent(ClientNotification("reminder", Greeting(rolledBack, 1)))
			status.setRollbackOnly()
		}
		transactions.executeWithoutResult {
			publisher.publishEvent(ClientNotification("reminder", Greeting(committed, 2)))
			// Sent in order on one thread: once a later non-transactional marker arrived, a premature send would have too.
			sendMarker(stream) shouldNotContain committed
		}

		val body = awaitBody(stream) { it.contains("event:reminder\ndata:{\"text\":\"$committed\",\"count\":2}\n\n") }
		body shouldNotContain rolledBack
	}

	@Test
	fun `a notification published outside a transaction is sent right away`() {
		val stream = connect()
		val text = UuidV7.generate().toString()

		publisher.publishEvent(ClientNotification("reminder", Greeting(text, 3)))

		awaitBody(stream) { it.contains("event:reminder\ndata:{\"text\":\"$text\",\"count\":3}\n\n") }
	}

	private fun sendMarker(stream: MvcResult): String {
		val marker = UuidV7.generate()
		Thread { changes.publish("TASK", marker, ChangeOp.UPDATED) }.apply { start() }.join()
		return awaitBody(stream) { it.contains(marker.toString()) }
	}

	private fun connect(): MvcResult {
		val result =
			mockMvc
				.get(EventsController.PATH) { accept = MediaType.TEXT_EVENT_STREAM }
				.andExpect {
					request { asyncStarted() }
					status { isOk() }
				}.andReturn()
		streams += result
		return result
	}

	private fun awaitBody(
		result: MvcResult,
		condition: (String) -> Boolean,
	): String {
		val deadline = System.nanoTime() + 5_000_000_000
		while (System.nanoTime() < deadline) {
			val body = result.response.getContentAsString(Charsets.UTF_8)
			if (condition(body)) return body
			Thread.sleep(20)
		}
		error("Expected content did not arrive: ${result.response.getContentAsString(Charsets.UTF_8)}")
	}
}
