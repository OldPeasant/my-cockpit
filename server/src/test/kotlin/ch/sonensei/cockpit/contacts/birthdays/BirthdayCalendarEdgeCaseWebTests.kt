package ch.sonensei.cockpit.contacts.birthdays

import ch.sonensei.cockpit.calendar.api.CalendarApi
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.CALENDARS
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.etag
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.id
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.text
import ch.sonensei.cockpit.calendar.api.CalendarService
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Edge cases of the virtual birthdays calendar in `GET /calendar/events` (task contacts-birthdays, SPEC.md §8.3): which
 * calendars count as virtual, ranges that do not start at midnight or span the new year, explicitly requested hidden
 * calendars, and that re-ensuring the calendar keeps the user's changes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class BirthdayCalendarEdgeCaseWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val calendarService: CalendarService,
	@Autowired private val initializer: BirthdayCalendarInitializer,
	@Autowired private val transactions: PlatformTransactionManager,
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
		birthday: String,
	): JsonNode =
		api.read(
			api.postJson(CONTACTS, """{"displayName":"$name","birthday":"$birthday"}""").andExpect { status { isCreated() } },
		)

	private fun calendars(): List<JsonNode> =
		api
			.read(mockMvc.get(CALENDARS).andExpect { status { isOk() } })
			.get("items")
			.values()
			.toList()

	private fun birthdaysCalendar(): JsonNode = calendars().single { it.get("name").asString() == "Birthdays" }

	private fun titles(
		from: String,
		to: String,
		vararg params: Pair<String, String>,
	) = api.occurrences(from, to, *params).map { it.get("title").asString() }

	/** A calendar of a new source of [type] with [externalId], inserted directly (no adapter needed). */
	private fun insertCalendar(
		type: String,
		externalId: String,
	): UUID {
		val source = UUID.randomUUID()
		val calendar = UUID.randomUUID()
		jdbc.update(
			"insert into calendar_source (id, created_at, updated_at, version, type, name) values (?, now(), now(), 0, ?, 'Feed')",
			source,
			type,
		)
		jdbc.update(
			"insert into calendar (id, created_at, updated_at, version, source_id, name, read_only, external_id) " +
				"values (?, now(), now(), 0, ?, 'Imported', true, ?)",
			calendar,
			source,
			externalId,
		)
		return calendar
	}

	@Test
	fun `a calendar of another source whose external id looks virtual has no computed occurrences`() {
		contact("Ada Lovelace", "1990-12-10")
		val impostor = insertCalendar("ICS_FEED", "virtual:birthdays")

		titles("2026-12-10T00:00:00+01:00", "2026-12-11T00:00:00+01:00") shouldContainExactly listOf("🎂 Ada Lovelace (36)")
		titles("2026-12-10T00:00:00+01:00", "2026-12-11T00:00:00+01:00", "calendarIds" to impostor.toString()).shouldBeEmpty()
	}

	@Test
	fun `a local calendar with an unknown virtual key has no occurrences and does not break the others`() {
		contact("Ada Lovelace", "1990-12-10")
		val personal = api.createCalendar()
		api.createEvent("""{"title":"Meeting","calendarId":"${personal.id()}","start":"2026-12-10T09:00:00Z"}""")
		val sourceId = jdbc.queryForObject("select source_id from calendar where id = ?", UUID::class.java, UUID.fromString(personal.id()))
		val unknown = UUID.randomUUID()
		jdbc.update(
			"insert into calendar (id, created_at, updated_at, version, source_id, name, read_only, external_id) " +
				"values (?, now(), now(), 0, ?, 'Removed feature', true, 'virtual:no-such-provider')",
			unknown,
			sourceId,
		)

		titles("2026-12-10T00:00:00+01:00", "2026-12-11T00:00:00+01:00") shouldContainExactly
			listOf("🎂 Ada Lovelace (36)", "Meeting")
		titles("2026-12-10T00:00:00+01:00", "2026-12-11T00:00:00+01:00", "calendarIds" to unknown.toString()).shouldBeEmpty()
	}

	@Test
	fun `a range inside the birthday or ending at its start is judged in the user's zone`() {
		contact("Ada Lovelace", "1990-12-10")

		// One hour around noon of the day (Europe/Zurich): the all-day occurrence overlaps.
		titles("2026-12-10T12:00:00+01:00", "2026-12-10T13:00:00+01:00") shouldContainExactly listOf("🎂 Ada Lovelace (36)")
		// 23:30 UTC on the 9th is already the 10th in Zurich.
		titles("2026-12-09T23:30:00Z", "2026-12-10T00:00:00Z") shouldContainExactly listOf("🎂 Ada Lovelace (36)")
		// Ending exactly at midnight of the 10th (Zurich) excludes it; starting at midnight of the 11th as well.
		titles("2026-12-09T00:00:00+01:00", "2026-12-10T00:00:00+01:00").shouldBeEmpty()
		titles("2026-12-11T00:00:00+01:00", "2026-12-12T00:00:00+01:00").shouldBeEmpty()
	}

	@Test
	fun `a range over the new year lists the birthdays of both years in date order`() {
		contact("New Year", "1980-01-01")
		contact("Old Year", "1985-12-31")

		api.occurrences("2026-12-31T00:00:00+01:00", "2027-01-02T00:00:00+01:00").map { it.text("startDate") } shouldContainExactly
			listOf("2026-12-31", "2027-01-01")
	}

	@Test
	fun `a hidden birthdays calendar still answers when requested explicitly`() {
		contact("Ada Lovelace", "1990-12-10")
		val birthdays = birthdaysCalendar()
		api.patchJson("$CALENDARS/${birthdays.id()}", """{"visible":false}""", etag(birthdays)).andExpect { status { isOk() } }

		titles("2026-12-10T00:00:00+01:00", "2026-12-11T00:00:00+01:00").shouldBeEmpty()
		titles("2026-12-10T00:00:00+01:00", "2026-12-11T00:00:00+01:00", "calendarIds" to birthdays.id()) shouldContainExactly
			listOf("🎂 Ada Lovelace (36)")
	}

	@Test
	fun `ensuring the calendar again keeps the user's name, color and visibility`() {
		contact("Ada Lovelace", "1990-12-10")
		val birthdays = birthdaysCalendar()
		api
			.patchJson("$CALENDARS/${birthdays.id()}", """{"name":"Geburtstage","color":"#00aa00","visible":false}""", etag(birthdays))
			.andExpect { status { isOk() } }

		initializer.onStartup()
		val again = calendarService.ensureVirtualCalendar(BirthdayCalendarProvider.KEY, "Other", "#123456")

		again.id.toString() shouldBe birthdays.id()
		val calendar = calendars().single()
		calendar.get("name").asString() shouldBe "Geburtstage"
		calendar.get("color").asString() shouldBe "#00aa00"
		calendar.get("visible").asBoolean() shouldBe false
		calendar.get("readOnly").asBoolean() shouldBe true
	}

	@Test
	fun `a second first-time creation waits for the first one and returns its calendar`() {
		api.createCalendar() // the LOCAL source exists, as it does once any calendar was created
		val inserted = CountDownLatch(1)
		val commit = CountDownLatch(1)
		val first =
			CompletableFuture.supplyAsync {
				TransactionTemplate(transactions).execute {
					val created = calendarService.ensureVirtualCalendar(BirthdayCalendarProvider.KEY, "Birthdays", null)
					inserted.countDown()
					commit.await(10, TimeUnit.SECONDS)
					created.id
				}
			}
		inserted.await(10, TimeUnit.SECONDS) shouldBe true

		// The first transaction has inserted but not committed: the second waits instead of inserting a duplicate.
		val second = CompletableFuture.supplyAsync { calendarService.ensureVirtualCalendar(BirthdayCalendarProvider.KEY, "Birthdays", null).id }
		shouldThrow<TimeoutException> { second.get(500, TimeUnit.MILLISECONDS) }
		commit.countDown()

		second.get(10, TimeUnit.SECONDS) shouldBe first.get(10, TimeUnit.SECONDS)
		jdbc.queryForObject("select count(*) from calendar where external_id = 'virtual:birthdays'", Int::class.java) shouldBe 1
	}

	@Test
	fun `a range without any birthday keeps the stored events as before`() {
		contact("Ada Lovelace", "1990-12-10")
		api.createCalendar()
		api.createEvent("""{"title":"Second","start":"2026-06-02T09:00:00Z"}""")
		api.createEvent("""{"title":"First","start":"2026-06-01T09:00:00Z"}""")

		titles("2026-06-01T00:00:00+02:00", "2026-06-03T00:00:00+02:00") shouldContainExactly listOf("First", "Second")
	}

	private companion object {
		const val CONTACTS = "/api/v1/contacts"
	}
}
