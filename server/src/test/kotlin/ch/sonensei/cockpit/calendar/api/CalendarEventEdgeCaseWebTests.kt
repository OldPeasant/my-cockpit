package ch.sonensei.cockpit.calendar.api

import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.CALENDARS
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.EVENTS
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.etag
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.id
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.isProblem
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.text
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.TestSecurityContextHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier

/** Validation limits, concurrent idempotent creates, range edges and calendar reads. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@WithCockpitUser
class CalendarEventEdgeCaseWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val clock: MutableClock,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	private val api = CalendarApi(mockMvc, jsonMapper)
	private lateinit var calendarId: String

	@BeforeEach
	fun reset() {
		CalendarApi.resetCalendarTables(jdbc)
		clock.set(MutableClockTestConfiguration.START)
		calendarId = api.createCalendar().id()
	}

	private fun json(value: String) = jsonMapper.writeValueAsString(value)

	/** A POST of a timed event with [extra] JSON fields. */
	private fun post(extra: String) = api.postJson(EVENTS, """{"title":"Dentist","start":"2026-03-02T13:00:00Z",$extra}""")

	private fun attendees(
		count: Int,
		email: (Int) -> String = { "guest$it@example.com" },
		name: String = "Guest",
	) = (0 until count).joinToString(",", "[", "]") { """{"email":${json(email(it))},"name":${json(name)}}""" }

	private fun emailOfLength(length: Int) = "a".repeat(length - "@example.com".length) + "@example.com"

	// --- validation limits

	@Test
	fun `text fields are accepted up to their limit and rejected one above`() {
		post(""""title":${json("t".repeat(500))},"description":${json("d".repeat(100_000))},"location":${json("l".repeat(2000))}""")
			.andExpect { status { isCreated() } }

		post(""""title":${json("t".repeat(501))},"description":${json("d".repeat(100_001))},"location":${json("l".repeat(2001))}""")
			.isProblem(400, "title", "description", "location")
	}

	@Test
	fun `attendees are accepted up to their limits and rejected above`() {
		post(""""attendees":${attendees(200, name = "n".repeat(200))}""").andExpect { status { isCreated() } }
		post(""""attendees":${attendees(1, email = { emailOfLength(320) })}""").andExpect { status { isCreated() } }

		post(""""attendees":${attendees(201)}""").isProblem(400, "attendees")
		post(""""attendees":${attendees(1, email = { emailOfLength(321) })}""").isProblem(400, "attendees[0].email")
		post(""""attendees":${attendees(2, name = "n".repeat(201))}""").isProblem(400, "attendees[0].name", "attendees[1].name")
		post(""""attendees":[{"email":"   "}]""").isProblem(400, "attendees[0].email")
	}

	@Test
	fun `reminders are accepted up to their limits and rejected above`() {
		post(""""reminderMinutes":${(0 until 20).map { it * 10 }}""").andExpect { status { isCreated() } }
		post(""""reminderMinutes":[0,40320]""").andExpect { status { isCreated() } }

		post(""""reminderMinutes":${(0 until 21).toList()}""").isProblem(400, "reminderMinutes")
		post(""""reminderMinutes":[40321]""").isProblem(400, "reminderMinutes")
	}

	@Test
	fun `conference URLs must be http(s) with a host and not too long`() {
		post(""""conferenceUrl":"http://meet.example.com/a"""").andExpect { status { isCreated() } }

		post(""""conferenceUrl":"https://"""").isProblem(400, "conferenceUrl")
		post(""""conferenceUrl":"ftp://files.example.com/x"""").isProblem(400, "conferenceUrl")
		post(""""conferenceUrl":"https://exa mple.com"""").isProblem(400, "conferenceUrl")
		post(""""conferenceUrl":${json("https://meet.example.com/" + "x".repeat(2000))}""").isProblem(400, "conferenceUrl")
	}

	@Test
	fun `rules need FREQ and a sane length, a blank rule is none`() {
		post(""""rrule":"COUNT=3"""").isProblem(400, "rrule")
		post(""""rrule":${json("FREQ=DAILY;" + "BYHOUR=1;".repeat(250))}""").isProblem(400, "rrule")

		api.createEvent("""{"title":"x","start":"2026-03-02T13:00:00Z","rrule":"  "}""").text("rrule") shouldBe null
		api.createEvent("""{"title":"x","start":"2026-03-02T13:00:00Z","rrule":"rrule:FREQ=DAILY"}""").text("rrule") shouldBe "FREQ=DAILY"
	}

	@Test
	fun `PATCH validates the changed event and reports every violation`() {
		val event = api.createEvent("""{"title":"Dentist","start":"2026-03-02T13:00:00Z"}""")

		api
			.patchEvent(event.id(), """{"title":"","reminderMinutes":[-1],"conferenceUrl":"mailto:x@example.com","rrule":"FREQ=NEVER"}""")
			.isProblem(400, "title", "reminderMinutes", "conferenceUrl", "rrule")
		api.patchEvent(event.id(), """{"attendees":[{"name":"No mail"}]}""").isProblem(400, "attendees")
		api.patchEvent(event.id(), """{"status":null,"attendees":null}""").isProblem(400, "status", "attendees")

		api.event(event.id()).get("version").asLong() shouldBe 0
	}

	@Test
	fun `POST without a title is rejected`() {
		api.postJson(EVENTS, """{"start":"2026-03-02T13:00:00Z"}""").isProblem(400, "title")
		api.postJson(EVENTS, """{"title":null,"start":"2026-03-02T13:00:00Z"}""").isProblem(400, "title")
	}

	@Test
	fun `PATCH changes attendees, reminders, status and transparency`() {
		val event = api.createEvent("""{"title":"Dentist","start":"2026-03-02T13:00:00Z"}""")
		event.text("status") shouldBe "CONFIRMED"
		event.text("transparency") shouldBe "OPAQUE"

		val changed =
			api.patchEventOk(
				event.id(),
				"""{"attendees":[{"email":" ada@example.com ","name":"Ada"}],"reminderMinutes":[10,60],""" +
					""""status":"TENTATIVE","transparency":"TRANSPARENT"}""",
			)

		changed.text("status") shouldBe "TENTATIVE"
		changed.text("transparency") shouldBe "TRANSPARENT"
		changed.get("reminderMinutes").values().map { it.asInt() } shouldContainExactly listOf(10, 60)
		changed.get("attendees").values().map { it.text("email") to it.text("status") } shouldContainExactly
			listOf("ada@example.com" to "NEEDS_ACTION")
		api.event(event.id()).text("transparency") shouldBe "TRANSPARENT"
		api.patchEvent(event.id(), """{"status":"MAYBE"}""").isProblem(400, "status")
	}

	@Test
	fun `a single event cannot move to a read-only or unknown calendar`() {
		val event = api.createEvent("""{"title":"Dentist","start":"2026-03-02T13:00:00Z"}""")
		val holidays = api.createCalendar("""{"name":"Holidays"}""").id()
		jdbc.update("update calendar set read_only = true where id = ?::uuid", holidays)

		api.patchEvent(event.id(), """{"calendarId":"$holidays"}""").isProblem(409)
		api.patchEvent(event.id(), """{"calendarId":"${UUID.randomUUID()}"}""").isProblem(400, "calendarId")
	}

	// --- idempotent create

	@Test
	fun `concurrent creates with the same client id create one event and all succeed`() {
		val id = UuidV7.generate().toString()
		val body = """{"id":"$id","title":"Dentist","start":"2026-03-02T13:00:00Z"}"""
		val parallel = 6
		val barrier = CyclicBarrier(parallel)
		val done = CountDownLatch(parallel)
		val statuses = java.util.Collections.synchronizedList(mutableListOf<Int>())
		val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
		// The test's login lives in a thread-local: hand it to the request threads explicitly.
		val login = TestSecurityContextHolder.getContext()

		repeat(parallel) {
			Thread {
				try {
					barrier.await()
					statuses +=
						mockMvc
							.post(EVENTS) {
								with(securityContext(login))
								with(csrf())
								contentType = MediaType.APPLICATION_JSON
								content = body
							}.andReturn()
							.response.status
				} catch (e: Throwable) {
					failures += e
				} finally {
					done.countDown()
				}
			}.start()
		}
		done.await()

		failures.shouldBeEmpty()
		statuses.sorted() shouldContainExactly listOf(200, 200, 200, 200, 200, 201)
		jdbc.queryForObject("select count(*) from calendar_event", Int::class.java) shouldBe 1
		api.event(id).text("title") shouldBe "Dentist"
	}

	// --- range query

	@Test
	fun `the range may be exactly 400 days, not one second more`() {
		api.createEvent("""{"title":"Dentist","start":"2026-03-02T13:00:00Z"}""")

		api.occurrences("2026-01-01T00:00:00Z", "2027-02-05T00:00:00Z").map { it.text("title") } shouldContainExactly listOf("Dentist")
		mockMvc
			.get(EVENTS) {
				param("from", "2026-01-01T00:00:00Z")
				param("to", "2027-02-05T00:00:01Z")
			}.isProblem(400, "to")
		mockMvc
			.get(EVENTS) {
				param("from", "2026-01-01T00:00:00Z")
				param("to", "2026-01-01T00:00:00Z")
			}.isProblem(400, "to")
	}

	@Test
	fun `without visible calendars the range is empty, an explicit hidden calendar is still listed`() {
		api.createEvent("""{"title":"Dentist","start":"2026-03-02T13:00:00Z"}""")
		jdbc.update("update calendar set visible = false")

		api.occurrences("2026-03-01T00:00:00Z", "2026-03-08T00:00:00Z").shouldBeEmpty()
		api.occurrences("2026-03-01T00:00:00Z", "2026-03-08T00:00:00Z", "calendarIds" to calendarId).map { it.text("title") } shouldContainExactly
			listOf("Dentist")
	}

	@Test
	fun `zero-length and boundary events are in the range they touch`() {
		api.createEvent("""{"title":"Reminder","start":"2026-03-02T13:00:00Z","end":"2026-03-02T13:00:00Z"}""")
		api.createEvent("""{"title":"Ends at from","start":"2026-03-02T11:00:00Z","end":"2026-03-02T12:00:00Z"}""")
		api.createEvent("""{"title":"Starts at to","start":"2026-03-02T14:00:00Z"}""")

		api.occurrences("2026-03-02T12:00:00Z", "2026-03-02T14:00:00Z").map { it.text("title") } shouldContainExactly listOf("Reminder")
	}

	// --- calendars

	@Test
	fun `GET of one calendar returns it with its ETag, an unknown one is 404`() {
		mockMvc.get("$CALENDARS/$calendarId").andExpect {
			status { isOk() }
			header { string("ETag", "\"0\"") }
			jsonPath("$.name") { value("Personal") }
			jsonPath("$.sourceType") { value("LOCAL") }
			jsonPath("$.isDefault") { value(true) }
		}
		mockMvc.get("$CALENDARS/${UUID.randomUUID()}").andExpect { status { isNotFound() } }
	}

	@Test
	fun `calendar PATCH rejects values of the wrong type and names that are too long`() {
		val calendar = api.read(mockMvc.get("$CALENDARS/$calendarId"))
		val path = "$CALENDARS/$calendarId"

		api.patchJson(path, """{"visible":"maybe"}""", etag(calendar)).isProblem(400, "visible")
		api.patchJson(path, """{"name":${json("n".repeat(201))}}""", etag(calendar)).isProblem(400, "name")
		api.postJson(CALENDARS, """{"name":${json("n".repeat(201))}}""").isProblem(400, "name")
		api.patchJson(path, """{"name":"  Personal 2  "}""", etag(calendar)).andExpect {
			status { isOk() }
			jsonPath("$.name") { value("Personal 2") }
		}
	}

	@Test
	fun `clearing the default flag leaves no default calendar`() {
		val calendar = api.read(mockMvc.get("$CALENDARS/$calendarId"))

		api.patchJson("$CALENDARS/$calendarId", """{"isDefault":false}""", etag(calendar)).andExpect {
			status { isOk() }
			jsonPath("$.isDefault") { value(false) }
		}

		api.postJson(EVENTS, """{"title":"x","start":"2026-03-02T13:00:00Z"}""").isProblem(400, "calendarId")
		val second = api.createCalendar("""{"name":"Second"}""")
		second.get("isDefault").asBoolean() shouldBe true
		api
			.read(mockMvc.get(CALENDARS))
			.get("items")
			.values()
			.filter { it.get("isDefault").asBoolean() }
			.map { it.id() } shouldContainExactlyInAnyOrder listOf(second.id())
	}

	@Test
	fun `POST of a default calendar takes the flag from the previous default`() {
		val work = api.createCalendar("""{"name":"Work","isDefault":true}""")

		work.get("isDefault").asBoolean() shouldBe true
		api
			.read(mockMvc.get(CALENDARS))
			.get("items")
			.values()
			.filter { it.get("isDefault").asBoolean() }
			.map { it.id() } shouldContainExactly listOf(work.id())
		api.createEvent("""{"title":"x","start":"2026-03-02T13:00:00Z"}""").text("calendarId") shouldBe work.id()
	}
}
