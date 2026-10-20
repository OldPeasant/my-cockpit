package ch.sonensei.cockpit.core.auth

import ch.sonensei.cockpit.core.auth.domain.AuthSession
import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.LoginThrottle
import ch.sonensei.cockpit.core.auth.domain.SetupService
import ch.sonensei.cockpit.core.auth.domain.Totp
import ch.sonensei.cockpit.core.auth.infra.SessionCookies
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/** Login, sessions, logout, CSRF and lockout against a real account (created through setup) and a test clock. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
class LoginWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val clock: MutableClock,
	@Autowired private val setup: SetupService,
	@Autowired private val throttle: LoginThrottle,
) : PostgresTestBase() {
	private val totp = Totp()
	private lateinit var secret: ByteArray
	private lateinit var recoveryCodes: List<String>

	@BeforeEach
	fun activeAccount() {
		clock.set(MutableClockTestConfiguration.START)
		throttle.recordSuccess()
		jdbc.update("delete from app_user")
		val enrollment = setup.start(USERNAME, PASSWORD)
		secret = Base32.decode(enrollment.totpSecret)
		recoveryCodes = enrollment.recoveryCodes
		setup.confirmTotp(currentCode())
	}

	@Test
	fun `login with password and TOTP sets an HttpOnly Secure Lax browser-session cookie`() {
		val result = login().andExpect { status { isOk() } }.andExpect { jsonPath("$.username") { value(USERNAME) } }.andReturn()

		val setCookie = sessionSetCookie(result)
		setCookie shouldContain "HttpOnly"
		setCookie shouldContain "Secure"
		setCookie shouldContain "SameSite=Lax"
		setCookie shouldContain "Path=/"
		setCookie shouldNotContain "Max-Age"
		me(sessionToken(result)).andExpect {
			status { isOk() }
			jsonPath("$.username") { value(USERNAME) }
		}
		// Only the hash of the token is stored.
		val token = sessionToken(result)
		jdbc.queryForList("select token_hash from auth_session", String::class.java).single() shouldNotBe token
		jdbc.queryForObject("select count(*) from auth_session where token_hash like ?", Int::class.java, "%$token%") shouldBe 0
	}

	@Test
	fun `remember device sets a persistent cookie for 30 days`() {
		val result = login(rememberDevice = true).andExpect { status { isOk() } }.andReturn()

		sessionSetCookie(result) shouldContain "Max-Age=${Duration.ofDays(30).seconds}"
	}

	@Test
	fun `a remembered session slides forward while used and expires 30 days after the last use`() {
		val token = sessionToken(login(rememberDevice = true).andReturn())

		clock.advance(Duration.ofDays(29))
		val renewed = me(token).andExpect { status { isOk() } }.andReturn()
		sessionSetCookie(renewed) shouldContain "Max-Age=${Duration.ofDays(30).seconds}"
		clock.advance(Duration.ofDays(29))
		me(token).andExpect { status { isOk() } }

		clock.advance(Duration.ofDays(30))
		val expired = me(token).andExpect { status { isUnauthorized() } }.andReturn()
		sessionSetCookie(expired) shouldContain "Max-Age=0"
		jdbc.queryForObject("select count(*) from auth_session", Int::class.java) shouldBe 0
	}

	@Test
	fun `a session without remember device ends after the idle timeout`() {
		val token = sessionToken(login().andReturn())

		clock.advance(AuthSession.BROWSER_SESSION_IDLE.minusMinutes(1))
		me(token).andExpect { status { isOk() } }
		clock.advance(AuthSession.BROWSER_SESSION_IDLE.minusMinutes(1))
		me(token).andExpect { status { isOk() } }
		clock.advance(AuthSession.BROWSER_SESSION_IDLE)
		me(token).andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `logout ends the session and clears the cookie`() {
		val token = sessionToken(login().andReturn())

		val result =
			mockMvc
				.post("/api/v1/auth/logout") {
					cookie(Cookie(SessionCookies.NAME, token))
					with(csrf())
				}.andExpect { status { isNoContent() } }
				.andReturn()

		sessionSetCookie(result) shouldContain "Max-Age=0"
		me(token).andExpect { status { isUnauthorized() } }
		jdbc.queryForObject("select count(*) from auth_session", Int::class.java) shouldBe 0
	}

	@Test
	fun `logout without a session still answers 204`() {
		mockMvc.post("/api/v1/auth/logout") { with(csrf()) }.andExpect { status { isNoContent() } }
	}

	@Test
	fun `an unknown session cookie is unauthenticated`() {
		me("not-a-session-token").andExpect { status { isUnauthorized() } }
		mockMvc.get("/api/v1/auth/me").andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `login and logout without the CSRF token are rejected with 403`() {
		mockMvc
			.post("/api/v1/auth/login") {
				contentType = MediaType.APPLICATION_JSON
				content = loginBody()
			}.andExpect { status { isForbidden() } }
		mockMvc.post("/api/v1/auth/logout").andExpect { status { isForbidden() } }

		val token = sessionToken(login().andReturn())
		// A logged-in session does not replace the CSRF token either.
		mockMvc
			.post("/api/v1/auth/logout") { cookie(Cookie(SessionCookies.NAME, token)) }
			.andExpect { status { isForbidden() } }
		mockMvc
			.post("/api/v1/auth/logout") {
				cookie(Cookie(SessionCookies.NAME, token), Cookie(XSRF_COOKIE, "forged"))
				header(XSRF_HEADER, "other")
			}.andExpect { status { isForbidden() } }
		me(token).andExpect { status { isOk() } }
	}

	@Test
	fun `a recovery code logs in only once`() {
		login(totp = null, recoveryCode = recoveryCodes[0]).andExpect { status { isOk() } }
		login(totp = null, recoveryCode = recoveryCodes[0]).isProblem(401)
		// Case, dashes and spaces do not matter; the other codes stay valid.
		login(totp = null, recoveryCode = " " + recoveryCodes[1].uppercase().replace("-", " ") + " ").andExpect { status { isOk() } }

		jdbc.queryForObject("select count(*) from recovery_code where used_at is not null", Int::class.java) shouldBe 2
	}

	@Test
	fun `wrong username, password, TOTP or recovery code all answer the same 401`() {
		val wrongCode = ((currentCode().toInt() + 1) % 1_000_000).toString().padStart(6, '0')
		val details =
			listOf(
				login(username = "mallory.example"),
				login(password = "wrong-fictitious-password"),
				login(totp = wrongCode),
				login(totp = null, recoveryCode = "aaaaa-bbbbb"),
				login(totp = totp.generate(secret, clock.instant().minus(Duration.ofMinutes(5)))),
			).map {
				it
					.isProblem(401)
					.andReturn()
					.response.contentAsString
					.let(::detail)
			}

		details.toSet() shouldHaveSize 1
		details.first() shouldBe "Invalid credentials"
		jdbc.queryForList("select id from auth_session", String::class.java).shouldBeEmpty()
	}

	@Test
	fun `login needs exactly one of TOTP and recovery code`() {
		login(totp = null, recoveryCode = null).isProblem(400)
		login(totp = currentCode(), recoveryCode = recoveryCodes[0]).isProblem(400)
		login(totp = "", recoveryCode = null).isProblem(400)
		login(password = "").isProblem(400)
	}

	@Test
	fun `an account whose setup is not confirmed cannot log in`() {
		jdbc.update("update app_user set totp_confirmed_at = null")

		login().isProblem(401)
	}

	@Test
	fun `5 consecutive failures block logins with exponential backoff until a success resets it`() {
		// Failures 1 to 4 do not block: each next attempt is still checked.
		repeat(5) { login(password = "wrong-fictitious-password").isProblem(401) }

		// After the 5th failure even correct credentials are rejected, with the wait time.
		login().isProblem(429).andExpect { header { string(HttpHeaders.RETRY_AFTER, "30") } }
		clock.advance(Duration.ofSeconds(10))
		login().isProblem(429).andExpect { header { string(HttpHeaders.RETRY_AFTER, "20") } }

		clock.advance(Duration.ofSeconds(20))
		login(password = "wrong-fictitious-password").isProblem(401)
		login().isProblem(429).andExpect { header { string(HttpHeaders.RETRY_AFTER, "60") } }
		clock.advance(Duration.ofSeconds(60))
		login(password = "wrong-fictitious-password").isProblem(401)
		login().isProblem(429).andExpect { header { string(HttpHeaders.RETRY_AFTER, "120") } }

		clock.advance(Duration.ofSeconds(120))
		login().andExpect { status { isOk() } }
		// The success reset the counter: the next failure is a plain 401 and does not block.
		login(password = "wrong-fictitious-password").isProblem(401)
		login().andExpect { status { isOk() } }
	}

	@Test
	fun `the backoff grows up to one hour`() {
		LoginThrottle.delayAfter(4) shouldBe Duration.ZERO
		LoginThrottle.delayAfter(5) shouldBe Duration.ofSeconds(30)
		LoginThrottle.delayAfter(6) shouldBe Duration.ofSeconds(60)
		LoginThrottle.delayAfter(11) shouldBe Duration.ofSeconds(30 * 64)
		LoginThrottle.delayAfter(12) shouldBe Duration.ofHours(1)
		LoginThrottle.delayAfter(Int.MAX_VALUE) shouldBe Duration.ofHours(1)
	}

	private fun currentCode() = totp.generate(secret, clock.instant())

	private fun login(
		username: String = USERNAME,
		password: String = PASSWORD,
		totp: String? = currentCode(),
		recoveryCode: String? = null,
		rememberDevice: Boolean = false,
	) = mockMvc.post("/api/v1/auth/login") {
		contentType = MediaType.APPLICATION_JSON
		content = loginBody(username, password, totp, recoveryCode, rememberDevice)
		with(csrf())
	}

	private fun loginBody(
		username: String = USERNAME,
		password: String = PASSWORD,
		totp: String? = currentCode(),
		recoveryCode: String? = null,
		rememberDevice: Boolean = false,
	): String =
		jsonMapper.writeValueAsString(
			mapOf(
				"username" to username,
				"password" to password,
				"totp" to totp,
				"recoveryCode" to recoveryCode,
				"rememberDevice" to rememberDevice,
			),
		)

	private fun me(token: String) = mockMvc.get("/api/v1/auth/me") { cookie(Cookie(SessionCookies.NAME, token)) }

	private fun sessionToken(result: MvcResult): String =
		result.response
			.getCookie(SessionCookies.NAME)
			.shouldNotBeNull()
			.value

	private fun sessionSetCookie(result: MvcResult): String =
		result.response
			.getHeaders(HttpHeaders.SET_COOKIE)
			.single { it.startsWith("${SessionCookies.NAME}=") }

	private fun detail(body: String) = jsonMapper.readValue(body, Map::class.java)["detail"]

	private fun ResultActionsDsl.isProblem(status: Int) =
		andExpect {
			status { isEqualTo(status) }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		}

	private companion object {
		const val USERNAME = "jane.doe"
		const val PASSWORD = "correct-horse-battery-staple"
		const val XSRF_COOKIE = "XSRF-TOKEN"
		const val XSRF_HEADER = "X-XSRF-TOKEN"
	}
}
