package ch.sonensei.cockpit.core.events

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * `GET /api/v1/events` end to end: events published in real (Postgres) transactions reach a connected client only
 * when the transaction commits.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EventsStreamTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val publisher: ChangeEventPublisher,
	@Autowired private val transactions: TransactionTemplate,
) : PostgresTestBase() {
	private enum class Kind { TASK }

	@Test
	fun `the stream needs a login`() {
		mockMvc.get(PATH) { accept = MediaType.TEXT_EVENT_STREAM }.andExpect { status { isUnauthorized() } }
	}

	@Test
	@WithCockpitUser
	fun `an event of a committed transaction reaches a connected client, a rolled-back one does not`() {
		val result = connect()
		val committed = UuidV7.generate()
		val rolledBack = UuidV7.generate()

		transactions.executeWithoutResult { status ->
			publisher.publish(Kind.TASK, rolledBack, ChangeOp.UPDATED)
			status.setRollbackOnly()
		}
		transactions.executeWithoutResult { publisher.publish(Kind.TASK, committed, ChangeOp.CREATED) }

		val body = awaitBody(result) { it.contains(committed.toString()) }
		body shouldContain "event:change\n"
		body shouldContain "\"type\":\"TASK\""
		body shouldContain "\"op\":\"CREATED\""
		// Events are delivered in order on one thread, so the rolled-back one would have arrived first.
		body shouldNotContain rolledBack.toString()
	}

	@Test
	@WithCockpitUser
	fun `an event published inside a transaction is not sent before the commit`() {
		val result = connect()
		val id = UuidV7.generate()
		val marker = UuidV7.generate()

		transactions.executeWithoutResult {
			publisher.publish(Kind.TASK, id, ChangeOp.DELETED)
			// A non-transactional event sent meanwhile overtakes the pending one.
			Thread { publisher.publish("REMINDER", marker, ChangeOp.CREATED) }.apply { start() }.join()
			awaitBody(result) { it.contains(marker.toString()) } shouldNotContain id.toString()
		}

		awaitBody(result) { it.contains(id.toString()) } shouldContain "\"op\":\"DELETED\""
	}

	@Test
	@WithCockpitUser
	fun `the event data is the ChangeEvent as JSON with an ISO-8601 timestamp`() {
		val result = connect()
		val id = UuidV7.generate()

		publisher.publish(ChangeEvent("NOTE", id, ChangeOp.UPDATED, Instant.parse("2026-03-14T09:26:53.589Z")))

		val data =
			awaitBody(result) { it.contains(id.toString()) }
				.lines()
				.single { it.startsWith("data:") && it.contains(id.toString()) }
		data shouldBe "data:{\"type\":\"NOTE\",\"id\":\"$id\",\"op\":\"UPDATED\",\"at\":\"2026-03-14T09:26:53.589Z\"}"
	}

	private fun connect(): MvcResult {
		val result =
			mockMvc
				.get(PATH) { accept = MediaType.TEXT_EVENT_STREAM }
				.andExpect {
					request { asyncStarted() }
					status { isOk() }
				}.andReturn()
		result.response.contentType!! shouldStartWith MediaType.TEXT_EVENT_STREAM_VALUE
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

	private companion object {
		const val PATH = "/api/v1/events"
	}
}
