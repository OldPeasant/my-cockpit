package ch.sonensei.cockpit.core.testsupport

import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.LoginThrottle
import ch.sonensei.cockpit.core.auth.domain.SetupService
import ch.sonensei.cockpit.core.auth.domain.Totp
import ch.sonensei.cockpit.core.auth.infra.SessionCookies
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.comparables.shouldBeBetween
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

/**
 * The e2e hooks against an active account (profile `e2e`): reset with sessions and recovery codes present, the shifted
 * clock driving TOTP, session expiry and the login lockout, and the hooks not opening the rest of the API.
 * The e2e context is closed afterwards, so it does not hold database connections for the rest of the run.
 */
@SpringBootTest
@DirtiesContext
@AutoConfigureMockMvc
@ActiveProfiles("e2e")
class TestSupportLoginWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val clock: ShiftableClock,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val setup: SetupService,
	@Autowired private val throttle: LoginThrottle,
) : PostgresTestBase() {
	private val totp = Totp()
	private lateinit var secret: ByteArray

	@BeforeEach
	fun activeAccount() {
		clock.reset()
		throttle.recordSuccess()
		jdbc.update("delete from app_user")
		val enrollment = setup.start(USERNAME, PASSWORD)
		secret = Base32.decode(enrollment.totpSecret)
		setup.confirmTotp(serverCode())
	}

	@AfterEach
	fun realTime() {
		clock.reset()
		throttle.recordSuccess()
		jdbc.update("delete from app_user")
	}

	@Test
	fun `reset ends existing sessions and removes recovery codes, so setup is required again`() {
		val token = sessionToken(login().andExpect { status { isOk() } }.andReturn())
		me(token).andExpect { status { isOk() } }
		count("recovery_code") shouldBeGreaterThan 0L
		count("auth_session") shouldBe 1L

		mockMvc.post("/api/test/reset").andExpect { status { isNoContent() } }

		count("app_user") shouldBe 0L
		count("recovery_code") shouldBe 0L
		count("auth_session") shouldBe 0L
		val stale = me(token).andExpect { status { isUnauthorized() } }.andReturn()
		sessionSetCookie(stale) shouldContain "Max-Age=0"
		mockMvc.get("/api/v1/setup/status").andExpect { jsonPath("$.setupRequired") { value(true) } }
		// As fresh as after the first start: a new setup with other credentials goes through right away.
		mockMvc
			.post("/api/v1/setup") {
				contentType = MediaType.APPLICATION_JSON
				content = """{"username":"john.doe","password":"another long fictitious password"}"""
			}.andExpect { status { isCreated() } }
	}

	@Test
	fun `reset is repeatable on an already empty database`() {
		mockMvc.post("/api/test/reset").andExpect { status { isNoContent() } }
		mockMvc.post("/api/test/reset").andExpect { status { isNoContent() } }
		count("app_user") shouldBe 0L
	}

	@Test
	fun `only POST resets, a GET leaves the database alone`() {
		mockMvc.get("/api/test/reset").andExpect { status { isMethodNotAllowed() } }
		mockMvc.get("/api/test/clock").andExpect { status { isMethodNotAllowed() } }
		count("app_user") shouldBe 1L
	}

	@Test
	fun `the public test chain does not open the rest of the API`() {
		// No login: the application's own chain still answers 401 for the API.
		mockMvc.get("/api/v1/auth/me").andExpect { status { isUnauthorized() } }
		mockMvc.get("/api/v1/settings").andExpect { status { isUnauthorized() } }
		// CSRF is still required outside /api/test/.
		mockMvc
			.post("/api/v1/auth/login") {
				contentType = MediaType.APPLICATION_JSON
				content = loginBody(serverCode())
			}.andExpect { status { isForbidden() } }
		// Paths that only start like the test path are not covered by it.
		mockMvc.post("/api/testing/reset").andExpect { status { isForbidden() } }
		mockMvc.post("/api/test-reset") { with(csrf()) }.andExpect { status { isUnauthorized() } }
		count("app_user") shouldBe 1L
	}

	@Test
	fun `clock answers the time it was set to, also for an input with a zone offset`() {
		val result =
			mockMvc
				.post("/api/test/clock") {
					contentType = MediaType.APPLICATION_JSON
					content = """{"now":"2030-05-01T11:30:00+02:00"}"""
				}.andExpect { status { isOk() } }
				.andReturn()

		val answered = Instant.parse(jsonMapper.readTree(result.response.contentAsString)["now"].asString())
		Duration.between(Instant.parse("2030-05-01T09:30:00Z"), answered).shouldBeBetween(Duration.ZERO, Duration.ofMinutes(1))
		Duration.between(Instant.parse("2030-05-01T09:30:00Z"), clock.instant()).shouldBeBetween(Duration.ZERO, Duration.ofMinutes(1))
	}

	@Test
	fun `after the clock is set, TOTP codes must be computed for the server time`() {
		setClock(Instant.now().plus(Duration.ofDays(3)))

		login(totp = totp.generate(secret, Instant.now())).andExpect { status { isUnauthorized() } }
		login(totp = serverCode()).andExpect { status { isOk() } }
	}

	@Test
	fun `moving the clock past the idle timeout ends a session`() {
		val token = sessionToken(login().andExpect { status { isOk() } }.andReturn())
		me(token).andExpect { status { isOk() } }

		setClock(clock.instant().plus(Duration.ofHours(13)))

		me(token).andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `the login lockout runs out on the shifted clock`() {
		repeat(LoginThrottle.FREE_ATTEMPTS) { login(password = "wrong fictitious password").andExpect { status { isUnauthorized() } } }
		login().andExpect {
			status { isTooManyRequests() }
			header { exists(HttpHeaders.RETRY_AFTER) }
		}

		setClock(clock.instant().plus(Duration.ofMinutes(1)))

		login().andExpect { status { isOk() } }
	}

	@Test
	fun `reset lifts an active login lockout`() {
		repeat(LoginThrottle.FREE_ATTEMPTS) { login(password = "wrong fictitious password").andExpect { status { isUnauthorized() } } }
		login().andExpect { status { isTooManyRequests() } }

		mockMvc.post("/api/test/reset").andExpect { status { isNoContent() } }
		val enrollment = setup.start(USERNAME, PASSWORD)
		secret = Base32.decode(enrollment.totpSecret)
		setup.confirmTotp(serverCode())

		login().andExpect { status { isOk() } }
	}

	private fun serverCode() = totp.generate(secret, clock.instant())

	private fun setClock(now: Instant) =
		mockMvc
			.post("/api/test/clock") {
				contentType = MediaType.APPLICATION_JSON
				content = """{"now":"$now"}"""
			}.andExpect { status { isOk() } }

	private fun login(
		password: String = PASSWORD,
		totp: String = serverCode(),
	) = mockMvc.post("/api/v1/auth/login") {
		contentType = MediaType.APPLICATION_JSON
		content = loginBody(totp, password)
		with(csrf())
	}

	private fun loginBody(
		totp: String,
		password: String = PASSWORD,
	): String =
		jsonMapper.writeValueAsString(
			mapOf("username" to USERNAME, "password" to password, "totp" to totp, "rememberDevice" to false),
		)

	private fun me(token: String) = mockMvc.get("/api/v1/auth/me") { cookie(Cookie(SessionCookies.NAME, token)) }

	private fun count(table: String) = jdbc.queryForObject("select count(*) from $table", Long::class.java)!!

	private fun sessionToken(result: MvcResult): String =
		result.response
			.getCookie(SessionCookies.NAME)
			.shouldNotBeNull()
			.value

	private fun sessionSetCookie(result: MvcResult): String =
		result.response
			.getHeaders(HttpHeaders.SET_COOKIE)
			.single { it.startsWith("${SessionCookies.NAME}=") }

	private companion object {
		const val USERNAME = "jane.doe"
		const val PASSWORD = "correct-horse-battery-staple"
	}
}
