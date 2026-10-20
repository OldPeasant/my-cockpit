package ch.sonensei.cockpit.core.reminders

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.FakeItemResolvers
import ch.sonensei.cockpit.core.items.FakeItemResolversConfiguration
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.reminders.domain.ReminderService
import ch.sonensei.cockpit.core.reminders.infra.ReminderRepository
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockAsyncContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * A fired reminder reaches a client connected to `GET /api/v1/events` as SSE event `reminder` with the item title,
 * only after the firing transaction commits (SPEC.md §4.6).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeItemResolversConfiguration::class, MutableClockTestConfiguration::class)
@WithCockpitUser
class ReminderEventsStreamTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val service: ReminderService,
	@Autowired private val repository: ReminderRepository,
	@Autowired private val fakes: FakeItemResolvers,
	@Autowired private val clock: MutableClock,
	@Autowired private val transactions: TransactionTemplate,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val changes: ChangeEventPublisher,
) : PostgresTestBase() {
	private val start = MutableClockTestConfiguration.START
	private val streams = mutableListOf<MvcResult>()

	@BeforeEach
	fun setUp() {
		repository.deleteAll()
		fakes.reset()
		clock.set(start)
	}

	@AfterEach
	fun closeStreams() {
		streams.forEach { (it.request.asyncContext as MockAsyncContext).complete() }
		streams.clear()
	}

	@Test
	fun `a fired reminder reaches a connected client after the commit as a reminder event with the item title`() {
		val task = fakes.tasks.add("Water the fictitious ficus")
		val reminder = service.create(task, start.plus(Duration.ofMinutes(30))).reminder
		val stream = connect()
		clock.advance(Duration.ofMinutes(30))

		transactions.executeWithoutResult {
			service.fireDue() shouldBe 1
			// The hub sends in order on one thread: once a later, non-transactional marker arrived, an event sent
			// before the commit would have arrived too.
			sendMarker(stream) shouldNotContain reminder.id.toString()
		}

		val data = awaitReminderData(stream)
		val json = jsonMapper.readTree(data.removePrefix("data:"))
		json.get("id").asString() shouldBe reminder.id.toString()
		json.get("item").get("title").asString() shouldBe "Water the fictitious ficus"
		json.get("item").get("available").asBoolean() shouldBe true
		json.get("item").get("deepLink").asString() shouldBe "/task/${task.id}"
		json.get("firedAt").asString() shouldBe "2026-03-01T08:30:00Z"
		json.get("channel").asString() shouldBe "NOTIFICATION"
		// The change event of the firing is a separate, content-free `change` event.
		stream.body() shouldContain "event:change\n"
		stream.body() shouldContain "\"type\":\"REMINDER\""
		stream.body() shouldContain "\"op\":\"UPDATED\""
	}

	@Test
	fun `a rolled-back firing sends no reminder event and the reminder fires once later`() {
		val task = fakes.tasks.add("Pay fictitious invoice")
		val reminder = service.create(task, start).reminder
		val stream = connect()

		transactions.executeWithoutResult { status ->
			service.fireDue() shouldBe 1
			status.setRollbackOnly()
		}

		repository.findById(reminder.id).get().firedAt shouldBe null
		sendMarker(stream) shouldNotContain "event:reminder\n"
		clock.advance(Duration.ofMinutes(1))
		service.fireDue() shouldBe 1
		service.fireDue() shouldBe 0

		val events = sendMarker(stream).split("\n\n").filter { it.contains("event:reminder\n") }
		events.size shouldBe 1
		events.single() shouldContain reminder.id.toString()
		events.single() shouldContain "\"firedAt\":\"2026-03-01T08:01:00Z\""
	}

	@Test
	fun `a reminder of a deleted item sends no reminder event`() {
		val gone = fakes.tasks.add("Deleted errand")
		val goneReminder = service.create(gone, start).reminder
		fakes.tasks.remove(gone.id)
		val kept = fakes.notes.add("Kept note")
		val keptReminder = service.create(kept, start).reminder
		val stream = connect()

		service.fireDue() shouldBe 2

		awaitReminderData(stream) shouldContain keptReminder.id.toString()
		sendMarker(stream)
			.split("\n\n")
			.filter { it.contains("event:reminder\n") }
			.none { it.contains(goneReminder.id.toString()) } shouldBe true
	}

	private fun connect(): MvcResult {
		val result =
			mockMvc
				.get("/api/v1/events") { accept = MediaType.TEXT_EVENT_STREAM }
				.andExpect {
					request { asyncStarted() }
					status { isOk() }
				}.andReturn()
		streams += result
		return result
	}

	/**
	 * Publishes a change event outside any transaction (from another thread, so it is not bound to a running test
	 * transaction) and waits until it arrived: everything sent before it has arrived too. Returns the stream body.
	 */
	private fun sendMarker(stream: MvcResult): String {
		val marker = UuidV7.generate()
		Thread { changes.publish("TASK", marker, ChangeOp.UPDATED) }.apply { start() }.join()
		return awaitBody(stream) { it.contains(marker.toString()) }
	}

	private fun MvcResult.body() = response.getContentAsString(Charsets.UTF_8)

	/** The `data:` line of the first complete `reminder` event (a stream may be read while an event is half written). */
	private fun awaitReminderData(result: MvcResult): String =
		COMPLETE_REMINDER_EVENT
			.find(awaitBody(result) { COMPLETE_REMINDER_EVENT.containsMatchIn(it) })!!
			.groupValues[1]

	private fun awaitBody(
		result: MvcResult,
		condition: (String) -> Boolean,
	): String {
		val deadline = System.nanoTime() + 5_000_000_000
		while (System.nanoTime() < deadline) {
			val body = result.body()
			if (condition(body)) return body
			Thread.sleep(20)
		}
		error("Expected content did not arrive: ${result.body()}")
	}

	private companion object {
		val COMPLETE_REMINDER_EVENT = Regex("event:reminder\n(data:[^\n]*)\n\n")
	}
}
