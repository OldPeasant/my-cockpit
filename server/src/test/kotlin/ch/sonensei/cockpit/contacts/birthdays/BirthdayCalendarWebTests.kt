package ch.sonensei.cockpit.contacts.birthdays

import ch.sonensei.cockpit.calendar.api.CalendarApi
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.CALENDARS
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.etag
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.id
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.isProblem
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.text
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class BirthdayCalendarWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val initializer: BirthdayCalendarInitializer,
) : PostgresTestBase() {
	private val api = CalendarApi(mockMvc, jsonMapper)

	@BeforeEach
	@AfterEach
	fun reset() {
		jdbc.execute("delete from contact")
		jdbc.execute("delete from search_document where item_type = 'CONTACT'")
		CalendarApi.resetCalendarTables(jdbc)
	}

	private fun contact(
		name: String,
		birthday: String?,
	): JsonNode {
		val body = if (birthday == null) """{"displayName":"$name"}""" else """{"displayName":"$name","birthday":"$birthday"}"""
		return api.read(api.postJson(CONTACTS, body).andExpect { status { isCreated() } })
	}

	private fun calendars(): List<JsonNode> =
		api
			.read(mockMvc.get(CALENDARS).andExpect { status { isOk() } })
			.get("items")
			.values()
			.toList()

	private fun birthdaysCalendar(): JsonNode = calendars().single { it.get("name").asString() == "Birthdays" }

	/** The occurrences of the days [from, to) in the user's zone (Europe/Zurich, winter time). */
	private fun days(
		from: String,
		to: String,
		vararg params: Pair<String, String>,
	) = api.occurrences("${from}T00:00:00+01:00", "${to}T00:00:00+01:00", *params)

	@Test
	fun `birthdays are yearly all-day occurrences of a read-only virtual calendar`() {
		val ada = contact("Ada Lovelace", "1990-12-10")
		contact("No Birthday", null)

		val calendar = birthdaysCalendar()
		calendar.get("readOnly").asBoolean() shouldBe true
		calendar.get("visible").asBoolean() shouldBe true
		calendar.get("isDefault").asBoolean() shouldBe false
		calendar.get("sourceType").asString() shouldBe "LOCAL"

		val occurrences = days("2026-12-01", "2027-12-31")
		occurrences.map { it.text("startDate") } shouldContainExactly listOf("2026-12-10", "2027-12-10")
		occurrences.forEach {
			it.get("allDay").asBoolean() shouldBe true
			it.get("calendarId").asString() shouldBe calendar.id()
			it.get("eventId").asString() shouldBe ada.id()
			it.get("seriesId").asString() shouldBe ada.id()
			it.text("occurrence") shouldBe it.text("startDate")
			it.get("transparency").asString() shouldBe "TRANSPARENT"
		}
		occurrences.map { it.text("endDate") } shouldContainExactly listOf("2026-12-11", "2027-12-11")
		occurrences.map { it.get("title").asString() } shouldContainExactly listOf("🎂 Ada Lovelace (36)", "🎂 Ada Lovelace (37)")
	}

	@Test
	fun `a February 29 birthday falls on February 28 in other years`() {
		contact("Leap Person", "2000-02-29")

		days("2027-02-01", "2027-03-01").map { it.text("startDate") } shouldContainExactly listOf("2027-02-28")
		days("2028-02-01", "2028-03-01").map { it.text("startDate") } shouldContainExactly listOf("2028-02-29")
		// The range ending before the 28th has none; one starting on March 1st neither.
		days("2027-02-01", "2027-02-28").shouldBeEmpty()
		days("2027-03-01", "2027-04-01").shouldBeEmpty()
	}

	@Test
	fun `occurrences merge with stored events in start order and respect the calendar filter and visibility`() {
		contact("Grace Hopper", "1906-12-09")
		val personal = api.createCalendar()
		api.createEvent("""{"title":"Meeting","start":"2026-12-08T09:00:00Z"}""")
		api.createEvent("""{"title":"Later","start":"2026-12-10T09:00:00Z"}""")

		days("2026-12-01", "2026-12-31").map { it.get("title").asString() } shouldContainExactly
			listOf("Meeting", "🎂 Grace Hopper (120)", "Later")
		days("2026-12-01", "2026-12-31", "calendarIds" to personal.id()).map { it.get("title").asString() } shouldContainExactly
			listOf("Meeting", "Later")
		val birthdays = birthdaysCalendar()
		days("2026-12-01", "2026-12-31", "calendarIds" to birthdays.id()).map { it.get("title").asString() } shouldContainExactly
			listOf("🎂 Grace Hopper (120)")

		api.patchJson("$CALENDARS/${birthdays.id()}", """{"visible":false,"color":"#00aa00"}""", etag(birthdays)).andExpect {
			status { isOk() }
		}
		days("2026-12-01", "2026-12-31").map { it.get("title").asString() } shouldContainExactly listOf("Meeting", "Later")
	}

	@Test
	fun `no occurrence before the first birthday, no age for placeholder years`() {
		contact("Baby", "2027-05-01")
		contact("Unknown Year", "1604-05-02")

		days("2027-01-01", "2028-01-01").map { it.get("title").asString() } shouldContainExactly listOf("🎂 Unknown Year")
		days("2028-01-01", "2029-01-01").map { it.get("title").asString() } shouldContainExactly
			listOf("🎂 Baby (1)", "🎂 Unknown Year")
	}

	@Test
	fun `contacts in the Trash and changed birthdays are reflected`() {
		val ada = contact("Ada Lovelace", "1990-12-10")
		val bob = contact("Bob Example", "1980-06-01")
		days("2026-06-01", "2026-12-31").size shouldBe 2

		api.patchJson("$CONTACTS/${bob.id()}", """{"birthday":"1980-07-01"}""", "\"${bob.get("version").asLong()}\"").andExpect {
			status { isOk() }
		}
		mockMvc
			.delete("$CONTACTS/${ada.id()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, "\"${ada.get("version").asLong()}\"")
			}.andExpect { status { isNoContent() } }

		days("2026-06-01", "2026-12-31").map { it.text("startDate") } shouldContainExactly listOf("2026-07-01")
	}

	@Test
	fun `the calendar is created only once there is a birthday, and only once`() {
		contact("No Birthday", null)
		calendars().shouldBeEmpty()

		val later = contact("Later Birthday", null)
		api.patchJson("$CONTACTS/${later.id()}", """{"birthday":"1970-01-01"}""", "\"${later.get("version").asLong()}\"").andExpect {
			status { isOk() }
		}
		contact("Second", "1971-01-01")
		initializer.onStartup()
		calendars().map { it.get("name").asString() } shouldContainExactly listOf("Birthdays")
	}

	@Test
	fun `startup creates the calendar for birthdays saved before`() {
		contact("Ada Lovelace", "1990-12-10")
		CalendarApi.resetCalendarTables(jdbc)
		calendars().shouldBeEmpty()

		initializer.onStartup()

		birthdaysCalendar().get("readOnly").asBoolean() shouldBe true
	}

	@Test
	fun `birthday occurrences cannot be created in, read or changed`() {
		val ada = contact("Ada Lovelace", "1990-12-10")
		val calendar = birthdaysCalendar()

		api.postJson(EVENTS, """{"title":"x","calendarId":"${calendar.id()}","allDay":true,"startDate":"2026-12-10"}""").isProblem(409)
		mockMvc.get("$EVENTS/${ada.id()}").andExpect { status { isNotFound() } }
	}

	private companion object {
		const val CONTACTS = "/api/v1/contacts"
		const val EVENTS = CalendarApi.EVENTS
	}
}
