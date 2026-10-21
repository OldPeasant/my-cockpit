package ch.sonensei.cockpit.core.auth

import ch.sonensei.cockpit.core.auth.domain.AuthSession
import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.LoginCredentials
import ch.sonensei.cockpit.core.auth.domain.LoginResult
import ch.sonensei.cockpit.core.auth.domain.LoginService
import ch.sonensei.cockpit.core.auth.domain.LoginThrottle
import ch.sonensei.cockpit.core.auth.domain.SetupService
import ch.sonensei.cockpit.core.auth.domain.Totp
import ch.sonensei.cockpit.core.auth.infra.SessionCookies
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Boundaries of the login flow beyond [LoginWebTests]: exact session expiry, the once-per-minute expiry write,
 * recovery codes that must not be consumed by a failed or blocked attempt, Retry-After rounding, malformed requests
 * and log hygiene. Same context as [LoginWebTests] (test clock), so no extra database pool.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class LoginEdgeCaseTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val clock: MutableClock,
	@Autowired private val setup: SetupService,
	@Autowired private val throttle: LoginThrottle,
	@Autowired private val loginService: LoginService,
) : PostgresTestBase() {
	private val totp = Totp()
	private lateinit var secret: ByteArray
	private lateinit var recoveryCodes: List<String>

	@BeforeEach
	fun activeAccount() {
		clock.set(START)
		throttle.recordSuccess()
		jdbc.update("delete from app_user")
		val enrollment = setup.start(USERNAME, PASSWORD)
		secret = Base32.decode(enrollment.totpSecret)
		recoveryCodes = enrollment.recoveryCodes
		setup.confirmTotp(currentCode())
	}

	@Test
	fun `a session without remember device is valid until just before 12 hours idle and ends exactly at 12 hours`() {
		val first = sessionToken(login().andReturn())
		val second = sessionToken(login().andReturn())

		clock.advance(AuthSession.BROWSER_SESSION_IDLE.minusNanos(1_000))
		me(first).andExpect { status { isOk() } }
		clock.advance(Duration.ofNanos(1_000))
		me(second).andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `a remembered session ends exactly 30 days after its last use`() {
		val token = sessionToken(login(rememberDevice = true).andReturn())

		clock.advance(AuthSession.REMEMBERED_LIFETIME)

		me(token).andExpect { status { isUnauthorized() } }
		sessionCount() shouldBe 0
	}

	@Test
	fun `the expiry of a remembered session is written at most once per minute`() {
		val token = sessionToken(login(rememberDevice = true).andReturn())

		clock.advance(LoginService.TOUCH_INTERVAL.minusSeconds(1))
		val early = me(token).andExpect { status { isOk() } }.andReturn()
		sessionSetCookies(early).shouldBeEmpty()
		expiresAt() shouldBe START.plus(AuthSession.REMEMBERED_LIFETIME)

		clock.advance(Duration.ofSeconds(1))
		val renewed = me(token).andExpect { status { isOk() } }.andReturn()
		sessionSetCookies(renewed).single() shouldContain "Max-Age=${AuthSession.REMEMBERED_LIFETIME.seconds}"
		expiresAt() shouldBe START.plus(LoginService.TOUCH_INTERVAL).plus(AuthSession.REMEMBERED_LIFETIME)
	}

	@Test
	fun `a browser-session cookie slides on the server but is never re-issued as a persistent cookie`() {
		val token = sessionToken(login().andReturn())

		clock.advance(Duration.ofHours(1))
		val used = me(token).andExpect { status { isOk() } }.andReturn()

		sessionSetCookies(used).shouldBeEmpty()
		expiresAt() shouldBe START.plus(Duration.ofHours(1)).plus(AuthSession.BROWSER_SESSION_IDLE)
	}

	@Test
	fun `a login removes sessions that have already expired`() {
		login().andExpect { status { isOk() } }
		clock.advance(AuthSession.BROWSER_SESSION_IDLE.plusHours(1))

		val fresh = sessionToken(login().andReturn())

		sessionCount() shouldBe 1
		me(fresh).andExpect { status { isOk() } }
	}

	@Test
	fun `deleting the account ends its sessions`() {
		val token = sessionToken(login(rememberDevice = true).andReturn())

		jdbc.update("delete from app_user")

		sessionCount() shouldBe 0
		me(token).andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `logout with an expired or unknown session cookie answers 204 and clears the cookie`() {
		val token = sessionToken(login().andReturn())
		clock.advance(AuthSession.BROWSER_SESSION_IDLE)

		for (cookie in listOf(token, "not-a-session-token")) {
			val result =
				mockMvc
					.post("/api/v1/auth/logout") {
						cookie(Cookie(SessionCookies.NAME, cookie))
						with(csrf())
					}.andExpect { status { isNoContent() } }
					.andReturn()
			sessionSetCookies(result).forEach { it shouldContain "Max-Age=0" }
			sessionSetCookies(result).isEmpty() shouldBe false
		}
	}

	@Test
	fun `an authentication already present is not replaced or cleared by a stale session cookie`() {
		val result =
			mockMvc
				.get("/api/v1/auth/me") {
					cookie(Cookie(SessionCookies.NAME, "not-a-session-token"))
					with(cockpitUser())
				}.andExpect {
					status { isOk() }
					jsonPath("$.username") { value(TestCockpitUser.USERNAME) }
				}.andReturn()

		sessionSetCookies(result).shouldBeEmpty()
	}

	@Test
	fun `without any account a login is the same 401 and counts as a failure`() {
		jdbc.update("delete from app_user")

		login().isProblem(401).andExpect { jsonPath("$.detail") { value("Invalid credentials") } }
		repeat(LoginThrottle.FREE_ATTEMPTS - 1) { login().isProblem(401) }
		login().isProblem(429)
		sessionCount() shouldBe 0
	}

	@Test
	fun `me falls back to the authentication name for another kind of principal`() {
		mockMvc.get("/api/v1/auth/me") { with(user("john.roe")) }.andExpect {
			status { isOk() }
			jsonPath("$.username") { value("john.roe") }
		}
	}

	@Test
	fun `the login response carries the token only in the cookie`() {
		val result = login().andExpect { status { isOk() } }.andReturn()
		val token = sessionToken(result)

		result.response.contentAsString shouldNotContain token
		jsonMapper.readValue(result.response.contentAsString, Map::class.java) shouldBe
			mapOf("username" to USERNAME, "totpEnrollmentRequired" to false)
	}

	@Test
	fun `a recovery code is not consumed by an attempt with a wrong username or password`() {
		login(password = "wrong-fictitious-password", totp = null, recoveryCode = recoveryCodes[0]).isProblem(401)
		login(username = "mallory.example", totp = null, recoveryCode = recoveryCodes[0]).isProblem(401)
		usedRecoveryCodes() shouldBe 0

		login(totp = null, recoveryCode = recoveryCodes[0]).andExpect { status { isOk() } }
		usedRecoveryCodes() shouldBe 1
	}

	@Test
	fun `a recovery code is not consumed by an attempt blocked by the backoff`() {
		repeat(LoginThrottle.FREE_ATTEMPTS) { login(password = "wrong-fictitious-password").isProblem(401) }

		login(totp = null, recoveryCode = recoveryCodes[0]).isProblem(429)

		usedRecoveryCodes() shouldBe 0
		clock.advance(LoginThrottle.BASE_DELAY)
		login(totp = null, recoveryCode = recoveryCodes[0]).andExpect { status { isOk() } }
	}

	@Test
	fun `a blank totp next to a recovery code counts as absent`() {
		login(totp = "   ", recoveryCode = recoveryCodes[2]).andExpect { status { isOk() } }
		login(totp = currentCode(), recoveryCode = "   ").andExpect { status { isOk() } }
	}

	@Test
	fun `concurrent logins with the same recovery code create at most one session`() {
		val code = recoveryCodes[3]
		val credentials = LoginCredentials(USERNAME, PASSWORD, null, code, false)

		val outcomes = concurrently(THREADS) { loginService.login(credentials) }

		outcomes.mapNotNull { it.exceptionOrNull() }.forEach { error ->
			withClue(error.toString()) { (error is OptimisticLockingFailureException) shouldBe true }
		}
		outcomes.count { it.getOrNull() is LoginResult.Success } shouldBe 1
		usedRecoveryCodes() shouldBe 1
		sessionCount() shouldBe 1
	}

	@Test
	fun `parallel requests with the same expired session all end anonymous without an error`() {
		repeat(EXPIRED_ROUNDS) { round ->
			val token = sessionToken(login().andReturn())
			clock.advance(AuthSession.BROWSER_SESSION_IDLE)

			val outcomes = concurrently(PARALLEL_GUESSES) { loginService.authenticate(token) }

			withClue("round $round") {
				outcomes.mapNotNull { it.exceptionOrNull() }.shouldBeEmpty()
				outcomes.forEach { it.getOrNull() shouldBe null }
				sessionCount() shouldBe 0
			}
		}
		val token = sessionToken(login().andReturn())
		clock.advance(AuthSession.BROWSER_SESSION_IDLE)
		concurrently(THREADS) { me(token).andReturn().response.status }.forEach { it.getOrThrow() shouldBe 401 }
	}

	@Test
	fun `parallel logins with a wrong password cannot slip past the backoff`() {
		val credentials = LoginCredentials(USERNAME, "wrong-fictitious-password", currentCode(), null, false)

		val outcomes = concurrently(PARALLEL_GUESSES) { loginService.login(credentials) }

		outcomes.mapNotNull { it.exceptionOrNull() }.shouldBeEmpty()
		val results = outcomes.map { it.getOrThrow() }
		// Only the free attempts reach the credential check; all others are rejected without it.
		results.count { it == LoginResult.InvalidCredentials } shouldBe LoginThrottle.FREE_ATTEMPTS
		results.count { it is LoginResult.Throttled } shouldBe PARALLEL_GUESSES - LoginThrottle.FREE_ATTEMPTS
		throttle.retryAfter() shouldBe LoginThrottle.BASE_DELAY
		login().isProblem(429)
	}

	@Test
	fun `Retry-After is rounded up to whole seconds and the block ends exactly then`() {
		repeat(LoginThrottle.FREE_ATTEMPTS) { login(password = "wrong-fictitious-password").isProblem(401) }

		clock.advance(Duration.ofMillis(29_500))
		login().isProblem(429).andExpect { header { string(HttpHeaders.RETRY_AFTER, "1") } }
		clock.advance(Duration.ofMillis(500))
		login().andExpect { status { isOk() } }
	}

	@Test
	fun `malformed or oversized requests are 400 problems and do not count as failed logins`() {
		val bodies =
			listOf(
				loginBody(username = "j".repeat(65)),
				loginBody(password = "p".repeat(257)),
				loginBody(totp = "1".repeat(17)),
				loginBody(totp = null, recoveryCode = "r".repeat(65)),
				loginBody(username = "   "),
				"""{"password":"$PASSWORD","totp":"123456"}""",
				"""{"username":"$USERNAME","totp":"123456"}""",
				"""{"username":"$USERNAME",""",
				"",
			)
		bodies.forEach { body -> withClue(body) { post(body).isProblem(400) } }

		// More than five rejected requests, yet the next correct login is not blocked.
		login().andExpect { status { isOk() } }
	}

	@Test
	fun `login never logs the password, second factor or session token`(output: CapturedOutput) {
		val wrongCode = ((currentCode().toInt() + 1) % 1_000_000).toString().padStart(6, '0')
		login(totp = wrongCode).isProblem(401)
		login(password = "wrong-fictitious-password").isProblem(401)
		login(totp = null, recoveryCode = recoveryCodes[4]).andExpect { status { isOk() } }
		val code = currentCode()
		val token = sessionToken(login(totp = code, rememberDevice = true).andReturn())
		mockMvc
			.post("/api/v1/auth/logout") {
				cookie(Cookie(SessionCookies.NAME, token))
				with(csrf())
			}.andExpect { status { isNoContent() } }

		val logs = output.all
		logs shouldContain "Login failed"
		logs shouldContain "Login succeeded"
		logs shouldContain "Logged out"
		logs shouldNotContain PASSWORD
		logs shouldNotContain "wrong-fictitious-password"
		logs shouldNotContain recoveryCodes[4]
		logs shouldNotContain token
		logs shouldNotContain LoginService.hash(token)
		// Six-digit codes can occur in timestamps by chance; check the log messages themselves.
		val messages = MESSAGE.findAll(logs).map { it.groupValues[1] }.toList()
		messages.forEach { message ->
			message shouldNotContain code
			message shouldNotContain wrongCode
		}
	}

	private fun <T> concurrently(
		threads: Int,
		action: (Int) -> T,
	): List<Result<T>> {
		val barrier = CyclicBarrier(threads)
		val pool = Executors.newFixedThreadPool(threads)
		try {
			val futures =
				(0 until threads).map { index ->
					pool.submit<Result<T>> {
						barrier.await(10, TimeUnit.SECONDS)
						runCatching { action(index) }
					}
				}
			return futures.map { it.get(60, TimeUnit.SECONDS) }
		} finally {
			pool.shutdownNow()
		}
	}

	private fun currentCode() = totp.generate(secret, clock.instant())

	private fun sessionCount() = jdbc.queryForObject("select count(*) from auth_session", Int::class.java)

	private fun usedRecoveryCodes() = jdbc.queryForObject("select count(*) from recovery_code where used_at is not null", Int::class.java)

	private fun expiresAt(): Instant = jdbc.queryForObject("select expires_at from auth_session", Timestamp::class.java)!!.toInstant()

	private fun login(
		username: String = USERNAME,
		password: String = PASSWORD,
		totp: String? = currentCode(),
		recoveryCode: String? = null,
		rememberDevice: Boolean = false,
	) = post(loginBody(username, password, totp, recoveryCode, rememberDevice))

	private fun post(body: String) =
		mockMvc.post("/api/v1/auth/login") {
			contentType = MediaType.APPLICATION_JSON
			content = body
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

	private fun sessionSetCookies(result: MvcResult): List<String> =
		result.response
			.getHeaders(HttpHeaders.SET_COOKIE)
			.filter { it.startsWith("${SessionCookies.NAME}=") }

	private fun ResultActionsDsl.isProblem(status: Int) =
		andExpect {
			status { isEqualTo(status) }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		}

	private companion object {
		const val USERNAME = "jane.doe"
		const val PASSWORD = "correct-horse-battery-staple"
		const val THREADS = 4
		const val PARALLEL_GUESSES = 12
		const val EXPIRED_ROUNDS = 5
		val START: Instant = MutableClockTestConfiguration.START
		val MESSAGE = Regex("\"message\":\"((?:[^\"\\\\]|\\\\.)*)\"")
	}
}
