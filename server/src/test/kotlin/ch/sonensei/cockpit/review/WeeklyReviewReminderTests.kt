package ch.sonensei.cockpit.review

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.reminders.domain.ReminderService
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.review.domain.WeeklyReviewReminders
import ch.sonensei.cockpit.review.infra.ReviewSettings
import ch.sonensei.cockpit.review.infra.WeeklyReviewItemResolver
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The one pending weekly review reminder: created, moved and removed when the setting or the time zone changes, and
 * re-armed after it fired.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@WithCockpitUser
class WeeklyReviewReminderTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val clock: MutableClock,
	@Autowired private val settings: SettingsService,
	@Autowired private val reminders: ReminderService,
	@Autowired private val service: WeeklyReviewReminders,
) : PostgresTestBase() {
	@BeforeEach
	fun setUp() {
		reset()
		settings.update(mapOf("timeZone" to "Europe/Zurich"))
		// Wednesday 2026-03-04, 09:00 in Zurich (UTC+1).
		clock.set(NOW)
	}

	@AfterEach
	fun reset() {
		settings.update(mapOf("timeZone" to null, "weeklyReviewReminder" to null))
		// All reminders: due ones left by other test classes in the shared database would fire with ours.
		jdbc.execute("delete from reminder")
	}

	@Test
	fun `off by default, without a reminder`() {
		settings.get(SETTING) shouldBe "off"
		pending().shouldBeEmpty()
	}

	@Test
	fun `invalid values are rejected with 400`() {
		shouldThrow<InvalidRequestException> { settings.update(mapOf("weeklyReviewReminder" to "FUNDAY@18:00")) }
		pending().shouldBeEmpty()
	}

	@Test
	fun `turning it on creates the reminder, changing it replaces it, off removes it`() {
		settings.update(mapOf("weeklyReviewReminder" to "SUNDAY@18:00"))
		val first = pending()
		first.map { it.second } shouldContainExactly listOf(Instant.parse("2026-03-08T17:00:00Z"))

		settings.update(mapOf("weeklyReviewReminder" to "FRIDAY@07:30"))
		val second = pending()
		second.map { it.second } shouldContainExactly listOf(Instant.parse("2026-03-06T06:30:00Z"))
		second.single().first shouldNotBe first.single().first

		settings.update(mapOf("weeklyReviewReminder" to "off"))
		pending().shouldBeEmpty()
	}

	@Test
	fun `a time zone change moves the reminder`() {
		settings.update(mapOf("weeklyReviewReminder" to "SUNDAY@18:00"))
		settings.update(mapOf("timeZone" to "America/New_York"))
		// New York is UTC-4 from 2026-03-08 02:00.
		pending().map { it.second } shouldContainExactly listOf(Instant.parse("2026-03-08T22:00:00Z"))
	}

	@Test
	fun `re-armed for the next week after it fired, and delivered with its item`() {
		settings.update(mapOf("weeklyReviewReminder" to "SUNDAY@18:00"))
		val body =
			mockMvc
				.get("/api/v1/reminders/upcoming") { param("until", "2026-03-09T00:00:00Z") }
				.andExpect { status { isOk() } }
				.andReturn()
				.response.contentAsString
		val weekly = JsonMapper().readTree(body)["items"].filter { it["item"]["type"].asString() == "WEEKLY_REVIEW" }
		weekly.map { it["item"]["title"].asString() } shouldContainExactly listOf("Weekly review")
		weekly.map { it["item"]["deepLink"].asString() } shouldContainExactly listOf("/review")
		weekly.map { Instant.parse(it["remindAt"].asString()) } shouldContainExactly listOf(Instant.parse("2026-03-08T17:00:00Z"))

		clock.set(Instant.parse("2026-03-08T17:00:01Z"))
		reminders.fireDue() shouldBe 1
		pending().map { it.second } shouldContainExactly listOf(Instant.parse("2026-03-15T17:00:00Z"))
	}

	@Test
	fun `setting it through the settings API arms the reminder, an invalid value is a 400 and changes nothing`() {
		val etag =
			mockMvc
				.get("/api/v1/settings")
				.andExpect { status { isOk() } }
				.andReturn()
				.response
				.getHeader("ETag")
		mockMvc
			.patch("/api/v1/settings") {
				with(csrf())
				header("If-Match", etag!!)
				contentType = MediaType.APPLICATION_JSON
				content = """{"weeklyReviewReminder":"THURSDAY@20:15"}"""
			}.andExpect { status { isOk() } }
		// Thursday 2026-03-05 20:15 Zurich (UTC+1).
		pending().map { it.second } shouldContainExactly listOf(Instant.parse("2026-03-05T19:15:00Z"))

		val etag2 =
			mockMvc
				.get("/api/v1/settings")
				.andReturn()
				.response
				.getHeader("ETag")
		mockMvc
			.patch("/api/v1/settings") {
				with(csrf())
				header("If-Match", etag2!!)
				contentType = MediaType.APPLICATION_JSON
				content = """{"weeklyReviewReminder":"THURSDAY@25:00"}"""
			}.andExpect { status { isBadRequest() } }
		settings.get(SETTING) shouldBe "THURSDAY@20:15"
		pending().map { it.second } shouldContainExactly listOf(Instant.parse("2026-03-05T19:15:00Z"))
	}

	@Test
	fun `an unrelated settings change keeps the pending reminder`() {
		settings.update(mapOf("weeklyReviewReminder" to "SUNDAY@18:00"))
		val id = pending().single().first
		// Tamper with the row: a sync would move it back, so an unchanged row proves the listener ignored the key.
		jdbc.execute("update reminder set remind_at = '2026-03-10T10:00:00Z' where id = '$id'")
		try {
			settings.update(mapOf("theme" to "dark"))
			pending() shouldContainExactly listOf(id to Instant.parse("2026-03-10T10:00:00Z"))
		} finally {
			settings.update(mapOf("theme" to null))
		}
	}

	@Test
	fun `startup catches up a reminder lost while the server was down`() {
		settings.update(mapOf("weeklyReviewReminder" to "SUNDAY@18:00"))
		jdbc.execute("delete from reminder where item_type = 'WEEKLY_REVIEW'")
		// The server was down past Sunday's reminder: the next one is the following Sunday.
		clock.set(Instant.parse("2026-03-09T08:00:00Z"))
		service.onStartup()
		pending().map { it.second } shouldContainExactly listOf(Instant.parse("2026-03-15T17:00:00Z"))
	}

	@Test
	fun `startup keeps a reminder that became due while the server was down, so it still fires`() {
		settings.update(mapOf("weeklyReviewReminder" to "SUNDAY@18:00"))
		val missed = pending().single()
		missed.second shouldBe Instant.parse("2026-03-08T17:00:00Z")
		// The server was down at Sunday 18:00 and restarts at 18:10, before the scheduler's first run.
		clock.set(Instant.parse("2026-03-08T17:10:00Z"))
		service.onStartup()
		pending().first() shouldBe missed
		pending().map { it.second } shouldContainExactly
			listOf(Instant.parse("2026-03-08T17:00:00Z"), Instant.parse("2026-03-15T17:00:00Z"))
		service.sync()
		pending().first() shouldBe missed

		reminders.fireDue() shouldBe 1
		jdbc.queryForObject("select fired_at is not null from reminder where id = '${missed.first}'", Boolean::class.java) shouldBe true
		pending().map { it.second } shouldContainExactly listOf(Instant.parse("2026-03-15T17:00:00Z"))
	}

	@Test
	fun `turning it off also removes a pending reminder that is already due`() {
		settings.update(mapOf("weeklyReviewReminder" to "SUNDAY@18:00"))
		clock.set(Instant.parse("2026-03-08T17:10:00Z"))
		settings.update(mapOf("weeklyReviewReminder" to "off"))
		pending().shouldBeEmpty()
	}

	@Test
	fun `the resolver knows only the one weekly review item`() {
		val resolver = WeeklyReviewItemResolver()
		resolver.type shouldBe ItemType.WEEKLY_REVIEW
		resolver.resolve(WeeklyReviewReminders.ITEM.id)?.deepLink shouldBe "/review"
		resolver.resolve(UUID.fromString("00000000-0000-7000-8000-00000000beef")).shouldBeNull()
	}

	@Test
	fun `sync is idempotent and repairs extra or missing pending reminders`() {
		settings.update(mapOf("weeklyReviewReminder" to "SUNDAY@18:00"))
		val id = pending().single().first
		service.sync()
		pending().single().first shouldBe id

		reminders.create(WeeklyReviewReminders.ITEM, Instant.parse("2026-03-05T10:00:00Z"))
		service.sync()
		pending().map { it.second } shouldContainExactly listOf(Instant.parse("2026-03-08T17:00:00Z"))

		jdbc.execute("delete from reminder where item_type = 'WEEKLY_REVIEW'")
		service.sync()
		pending().map { it.second } shouldContainExactly listOf(Instant.parse("2026-03-08T17:00:00Z"))
	}

	@Test
	fun `concurrent syncs leave exactly one pending reminder`() {
		settings.update(mapOf("weeklyReviewReminder" to "SUNDAY@18:00"))
		jdbc.execute("delete from reminder where item_type = 'WEEKLY_REVIEW'")
		val pool = Executors.newFixedThreadPool(4)
		try {
			(1..8).map { pool.submit { service.sync() } }.forEach { it.get(30, TimeUnit.SECONDS) }
		} finally {
			pool.shutdown()
		}
		pending().size shouldBe 1
	}

	private fun pending(): List<Pair<UUID, Instant>> =
		jdbc.query(
			"select id, remind_at from reminder where item_type = 'WEEKLY_REVIEW' and fired_at is null order by remind_at, id",
		) { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getTimestamp(2).toInstant() }

	private companion object {
		val NOW: Instant = Instant.parse("2026-03-04T08:00:00Z")
		val SETTING = ReviewSettings.WEEKLY_REVIEW_REMINDER
	}
}
