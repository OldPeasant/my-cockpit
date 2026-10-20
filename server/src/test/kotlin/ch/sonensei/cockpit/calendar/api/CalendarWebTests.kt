package ch.sonensei.cockpit.calendar.api

import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.CALENDARS
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.changes
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.etag
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.id
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.isProblem
import ch.sonensei.cockpit.calendar.model.Calendar
import ch.sonensei.cockpit.calendar.model.CalendarRepository
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.json.JsonMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@RecordApplicationEvents
@WithCockpitUser
class CalendarWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val sources: CalendarSourceRepository,
	@Autowired private val calendars: CalendarRepository,
	@Autowired private val clock: MutableClock,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	private val api = CalendarApi(mockMvc, jsonMapper)

	@BeforeEach
	fun reset() {
		CalendarApi.resetCalendarTables(jdbc)
		clock.set(MutableClockTestConfiguration.START)
	}

	@Test
	fun `POST creates a local calendar, the first one is the default, and announces it`(events: ApplicationEvents) {
		val result =
			api
				.postJson(CALENDARS, """{"name":"Personal","color":"#3366ff"}""")
				.andExpect {
					status { isCreated() }
					header { string(HttpHeaders.ETAG, "\"0\"") }
					jsonPath("$.name") { value("Personal") }
					jsonPath("$.color") { value("#3366ff") }
					jsonPath("$.visible") { value(true) }
					jsonPath("$.readOnly") { value(false) }
					jsonPath("$.isDefault") { value(true) }
					jsonPath("$.sourceType") { value("LOCAL") }
				}.andReturn()
		val id = api.read(api.postJson(CALENDARS, """{"name":"Other"}""")).id()

		val firstId = jsonMapper.readTree(result.response.contentAsString).id()
		result.response.getHeader(HttpHeaders.LOCATION) shouldBe "http://localhost$CALENDARS/$firstId"
		sources.findAll().map { it.type } shouldContainExactly listOf(CalendarSourceType.LOCAL)
		calendars.findById(java.util.UUID.fromString(id)).get().isDefault shouldBe false
		events.changes("CALENDAR").map { it.first } shouldContainExactly listOf(ChangeOp.CREATED, ChangeOp.CREATED)
	}

	@Test
	fun `GET lists every calendar by name with its source type`() {
		val google = sources.save(CalendarSource(CalendarSourceType.GOOGLE, "Work account"))
		calendars.save(Calendar(google.id, "Work").apply { readOnly = true })
		api.createCalendar("""{"name":"personal"}""")
		api.createCalendar("""{"name":"Birthdays"}""")

		val items =
			api
				.read(mockMvc.get(CALENDARS).andExpect { status { isOk() } })
				.get("items")
				.values()
				.toList()

		items.map { it.get("name").asString() } shouldContainExactly listOf("Birthdays", "personal", "Work")
		items.map { it.get("sourceType").asString() } shouldContainExactly listOf("LOCAL", "LOCAL", "GOOGLE")
		items.last().get("readOnly").asBoolean() shouldBe true
	}

	@Test
	fun `PATCH changes the given fields and moves the default flag`(events: ApplicationEvents) {
		val first = api.createCalendar()
		val second = api.createCalendar("""{"name":"Family"}""")

		val patched =
			api.read(
				api
					.patchJson("$CALENDARS/${second.id()}", """{"name":"Family & friends","color":null,"visible":false,"isDefault":true}""", etag(second))
					.andExpect {
						status { isOk() }
						header { string(HttpHeaders.ETAG, "\"1\"") }
					},
			)

		patched.get("name").asString() shouldBe "Family & friends"
		patched.get("visible").asBoolean() shouldBe false
		patched.get("isDefault").asBoolean() shouldBe true
		calendars.findByIsDefaultTrue()!!.id.toString() shouldBe second.id()
		events.changes("CALENDAR").filter { it.first == ChangeOp.UPDATED }.map { it.second } shouldContainExactly
			listOf(first.id(), second.id())
	}

	@Test
	fun `PATCH needs a current If-Match and rejects invalid and unknown fields`() {
		val calendar = api.createCalendar()
		val path = "$CALENDARS/${calendar.id()}"

		api.patchJson(path, """{"name":"New"}""", null).isProblem(428)
		api.patchJson(path, """{"name":"New"}""", "\"7\"").isProblem(412)
		api.patchJson(path, """{"color":"blue","name":" "}""", etag(calendar)).isProblem(400, "color", "name")
		api.patchJson(path, """{"readOnly":true}""", etag(calendar)).isProblem(400, "readOnly")
		api.patchJson(path, """{"visible":null}""", etag(calendar)).isProblem(400, "visible")
		api.patchJson("$CALENDARS/${java.util.UUID.randomUUID()}", """{"name":"New"}""", "*").isProblem(404)
	}

	@Test
	fun `POST rejects a missing name and an invalid color`() {
		api.postJson(CALENDARS, """{"color":"#123"}""").isProblem(400)
		api.postJson(CALENDARS, """{"name":"x","color":"#12345g"}""").isProblem(400, "color")
	}

	@Test
	@WithAnonymousUser
	fun `calendars and events need a login`() {
		mockMvc.get(CALENDARS).andExpect { status { isUnauthorized() } }
		mockMvc
			.get(CalendarApi.EVENTS) {
				param("from", "2026-03-01T00:00:00Z")
				param("to", "2026-03-02T00:00:00Z")
			}.andExpect { status { isUnauthorized() } }
		api.postJson(CalendarApi.EVENTS, """{"title":"x","start":"2026-03-02T13:00:00Z"}""").andExpect { status { isUnauthorized() } }
	}
}
