package ch.sonensei.cockpit.core.testsupport

import ch.sonensei.cockpit.core.auth.cockpitUser
import ch.sonensei.cockpit.core.auth.domain.LoginThrottle
import ch.sonensei.cockpit.core.auth.domain.SetupService
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeBetween
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** The e2e hooks `POST /api/test/clock` and `POST /api/test/reset`: present only with profile `e2e`. */
class TestSupportWebTests {
	// Closed afterwards (like TestSupportLoginWebTests), so the e2e context does not hold database connections.
	@Nested
	@SpringBootTest
	@DirtiesContext
	@AutoConfigureMockMvc
	@ActiveProfiles("e2e")
	inner class E2eProfile(
		@Autowired private val mockMvc: MockMvc,
		@Autowired private val clock: Clock,
		@Autowired private val jdbc: JdbcTemplate,
		@Autowired private val setup: SetupService,
		@Autowired private val throttle: LoginThrottle,
	) : PostgresTestBase() {
		@AfterEach
		fun realTime() = (clock as ShiftableClock).reset()

		@Test
		fun `the application clock is the shiftable test clock`() {
			clock.shouldBeInstanceOf<ShiftableClock>()
		}

		@Test
		fun `clock sets the server time without login or CSRF token, and the time runs on`() {
			mockMvc
				.post("/api/test/clock") {
					contentType = MediaType.APPLICATION_JSON
					content = """{"now":"2030-05-01T09:30:00Z"}"""
				}.andExpect {
					status { isOk() }
					jsonPath("$.now") { exists() }
				}

			Duration.between(Instant.parse("2030-05-01T09:30:00Z"), clock.instant()).toMillis().shouldBeBetween(0, 60_000)
		}

		@Test
		fun `the test clock drives the application, e g the setup's abandoned-after check`() {
			setup.start("jane.doe", "correct horse battery")
			// A second start with other credentials is refused while the pending setup is fresh ...
			mockMvc
				.post("/api/v1/setup") {
					contentType = MediaType.APPLICATION_JSON
					content = """{"username":"john.doe","password":"another long password"}"""
				}.andExpect { status { isConflict() } }

			// ... and allowed once the clock says it is older than an hour.
			mockMvc
				.post("/api/test/clock") {
					contentType = MediaType.APPLICATION_JSON
					content = """{"now":"${clock.instant().plus(Duration.ofHours(2))}"}"""
				}.andExpect { status { isOk() } }
			mockMvc
				.post("/api/v1/setup") {
					contentType = MediaType.APPLICATION_JSON
					content = """{"username":"john.doe","password":"another long password"}"""
				}.andExpect { status { isCreated() } }
			jdbc.update("delete from app_user")
		}

		@Test
		fun `clock without a time is rejected`() {
			mockMvc
				.post("/api/test/clock") {
					contentType = MediaType.APPLICATION_JSON
					content = "{}"
				}.andExpect { status { isBadRequest() } }
			mockMvc
				.post("/api/test/clock") {
					contentType = MediaType.APPLICATION_JSON
					content = """{"now":"tomorrow"}"""
				}.andExpect { status { isBadRequest() } }
		}

		@Test
		fun `reset empties the database, resets the clock and the login throttle`() {
			val flywayRows = jdbc.queryForObject("select count(*) from flyway_schema_history", Long::class.java)
			setup.start("jane.doe", "correct horse battery")
			jdbc.queryForObject("select count(*) from app_user", Long::class.java) shouldBe 1L
			(clock as ShiftableClock).set(Instant.parse("2035-01-01T00:00:00Z"))
			repeat(LoginThrottle.FREE_ATTEMPTS) { throttle.tryBeginAttempt() }
			throttle.retryAfter().shouldNotBeNull()

			mockMvc.post("/api/test/reset").andExpect { status { isNoContent() } }

			val nonEmpty =
				jdbc
					.queryForList(
						"select tablename from pg_tables where schemaname = current_schema() and tablename <> 'flyway_schema_history'",
						String::class.java,
					).filter { jdbc.queryForObject("select count(*) from $it", Long::class.java) != 0L }
			nonEmpty.shouldBeEmpty()
			jdbc.queryForObject("select count(*) from flyway_schema_history", Long::class.java) shouldBe flywayRows
			Duration.between(Instant.now(), clock.instant()).abs().toMinutes() shouldBe 0L
			throttle.retryAfter().shouldBeNull()
			mockMvc.get("/api/v1/setup/status").andExpect { jsonPath("$.setupRequired") { value(true) } }
		}
	}

	@Nested
	@SpringBootTest
	@AutoConfigureMockMvc
	inner class DefaultProfile(
		@Autowired private val mockMvc: MockMvc,
		@Autowired private val context: ApplicationContext,
		@Autowired private val clock: Clock,
	) : PostgresTestBase() {
		@Test
		fun `no test hooks and no test clock`() {
			context.getBeanNamesForType(TestSupportController::class.java).toList().shouldBeEmpty()
			context.getBeanNamesForType(TestSupportConfiguration::class.java).toList().shouldBeEmpty()
			(clock is ShiftableClock) shouldBe false
		}

		@Test
		fun `the paths need a login and do not exist even with one`() {
			for (path in listOf("/api/test/reset", "/api/test/clock")) {
				// Unlike in the e2e profile, the CSRF token and a login are required.
				mockMvc.post(path).andExpect { status { isForbidden() } }
				mockMvc.post(path) { with(csrf()) }.andExpect { status { isUnauthorized() } }
				mockMvc
					.post(path) {
						with(cockpitUser())
						with(csrf())
						contentType = MediaType.APPLICATION_JSON
						content = """{"now":"2030-05-01T09:30:00Z"}"""
					}.andExpect { status { isNotFound() } }
			}
		}
	}

	@Nested
	@SpringBootTest
	@ActiveProfiles("dev")
	inner class DevProfile(
		@Autowired private val context: ApplicationContext,
	) : PostgresTestBase() {
		@Test
		fun `no test hooks`() {
			context.getBeanNamesForType(TestSupportController::class.java).toList().shouldBeEmpty()
			context.getBeanNamesForType(ShiftableClock::class.java).toList().shouldBeEmpty()
		}
	}
}
