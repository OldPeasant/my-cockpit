package ch.sonensei.cockpit.core.auth

import ch.sonensei.cockpit.core.auth.domain.AuthDevice
import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.DeviceTokenService
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
import io.kotest.matchers.collections.shouldContainExactly
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
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Boundaries of the device tokens beyond [DeviceTokenWebTests] (task p0-auth-tokens): concurrent refreshes, recovery
 * codes on the token endpoint, `Authorization` header variants, unauthenticated device management, lazy cleanup of
 * expired devices and retired tokens, request validation and log hygiene. Same context as [DeviceTokenWebTests].
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class DeviceTokenEdgeCaseTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val clock: MutableClock,
	@Autowired private val setup: SetupService,
	@Autowired private val throttle: LoginThrottle,
	@Autowired private val deviceTokens: DeviceTokenService,
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

	// --- Hashes at rest ---

	@Test
	fun `the stored hashes are the SHA-256 of the issued and of the rotated tokens`() {
		val first = tokens(issue())
		storedHashes() shouldBe (LoginService.hash(first.access) to LoginService.hash(first.refresh))

		val second = tokens(refresh(first.refresh).andExpect { status { isOk() } })

		storedHashes() shouldBe (LoginService.hash(second.access) to LoginService.hash(second.refresh))
		jdbc.queryForList("select token_hash from auth_device_retired_token", String::class.java) shouldContainExactly
			listOf(LoginService.hash(first.refresh))
	}

	// --- Concurrency ---

	@Test
	fun `concurrent refreshes with the same token give at most one new pair and are treated as a reuse`() {
		val first = tokens(issue())

		val outcomes = concurrently(THREADS) { deviceTokens.refresh(first.refresh) }

		outcomes.mapNotNull { it.exceptionOrNull() }.shouldBeEmpty()
		val granted = outcomes.mapNotNull { it.getOrNull() }
		granted.size shouldBe 1
		// The losers present a token that was rotated meanwhile: a reuse, so the device is revoked.
		deviceCount() shouldBe 0
		me(granted.single().accessToken).andExpect { status { isUnauthorized() } }
	}

	// --- Credentials on the token endpoint ---

	@Test
	fun `a recovery code logs a device in once and is consumed`() {
		issue(totp = null, recoveryCode = recoveryCodes[0]).andExpect { status { isOk() } }
		usedRecoveryCodes() shouldBe 1

		issue(totp = null, recoveryCode = recoveryCodes[0]).isProblem(401)
		deviceCount() shouldBe 1

		// A blank totp next to a recovery code counts as absent.
		issue(totp = "  ", recoveryCode = recoveryCodes[1]).andExpect { status { isOk() } }
		usedRecoveryCodes() shouldBe 2
	}

	@Test
	fun `both or neither second factor is a 400 and consumes no recovery code`() {
		issue(totp = currentCode(), recoveryCode = recoveryCodes[0]).isProblem(400)
		issue(totp = null, recoveryCode = null).isProblem(400)
		issue(totp = " ", recoveryCode = " ").isProblem(400)

		usedRecoveryCodes() shouldBe 0
		deviceCount() shouldBe 0
	}

	@Test
	fun `a recovery code is not consumed by a token request with a wrong password`() {
		issue(password = "wrong-fictitious-password", totp = null, recoveryCode = recoveryCodes[2]).isProblem(401)

		usedRecoveryCodes() shouldBe 0
		issue(totp = null, recoveryCode = recoveryCodes[2]).andExpect { status { isOk() } }
	}

	@Test
	fun `a successful token request resets the shared throttle`() {
		repeat(LoginThrottle.FREE_ATTEMPTS - 1) { issue(password = "wrong-fictitious-password").isProblem(401) }
		issue().andExpect { status { isOk() } }

		repeat(LoginThrottle.FREE_ATTEMPTS - 1) { issue(password = "wrong-fictitious-password").isProblem(401) }
		issue().andExpect { status { isOk() } }
	}

	@Test
	fun `malformed or oversized token requests are 400 problems`() {
		val bodies =
			listOf(
				tokenBody(deviceName = "d".repeat(101)),
				tokenBody(deviceName = ""),
				tokenBody(username = "j".repeat(65)),
				tokenBody(password = "p".repeat(257)),
				tokenBody(totp = "1".repeat(17)),
				"""{"username":"$USERNAME","password":"$PASSWORD","totp":"123456"}""",
				"""{"username":"$USERNAME",""",
				"",
			)
		bodies.forEach { body -> withClue(body) { postToken(body).isProblem(400) } }
		listOf("""{}""", """{"refreshToken":""}""", """{"refreshToken":"${"r".repeat(257)}"}""").forEach { body ->
			withClue(body) { postRefresh(body).isProblem(400) }
		}

		deviceCount() shouldBe 0
		issue().andExpect { status { isOk() } }
	}

	@Test
	fun `the device name is trimmed and a name of exactly 100 characters is accepted`() {
		issue(deviceName = "  Pixel 9  ").andExpect { status { isOk() } }
		issue(deviceName = "d".repeat(100)).andExpect { status { isOk() } }

		jdbc.queryForList("select name from auth_device order by created_at, name", String::class.java).toSet() shouldBe
			setOf("Pixel 9", "d".repeat(100))
	}

	@Test
	fun `the token endpoints need no CSRF token even next to a session cookie`() {
		val session = webLogin()

		val first =
			tokens(
				mockMvc
					.post("/api/v1/auth/token") {
						cookie(Cookie(SessionCookies.NAME, session))
						contentType = MediaType.APPLICATION_JSON
						content = tokenBody()
					}.andExpect { status { isOk() } },
			)
		mockMvc
			.post("/api/v1/auth/token/refresh") {
				cookie(Cookie(SessionCookies.NAME, session))
				contentType = MediaType.APPLICATION_JSON
				content = jsonMapper.writeValueAsString(mapOf("refreshToken" to first.refresh))
			}.andExpect { status { isOk() } }
	}

	// --- Authorization header variants ---

	@Test
	fun `the bearer scheme is case-insensitive and surrounding blanks are ignored`() {
		val access = tokens(issue()).access

		mockMvc.get("/api/v1/auth/me") { header(HttpHeaders.AUTHORIZATION, "bearer $access") }.andExpect { status { isOk() } }
		mockMvc.get("/api/v1/auth/me") { header(HttpHeaders.AUTHORIZATION, "BEARER   $access  ") }.andExpect { status { isOk() } }
	}

	@Test
	fun `an empty or expired bearer token is not rescued by a valid session cookie`() {
		val access = tokens(issue()).access
		val session = webLogin()

		mockMvc
			.get("/api/v1/auth/me") {
				cookie(Cookie(SessionCookies.NAME, session))
				header(HttpHeaders.AUTHORIZATION, "Bearer ")
			}.andExpect { status { isUnauthorized() } }

		clock.advance(AuthDevice.ACCESS_LIFETIME)
		mockMvc
			.get("/api/v1/auth/me") {
				cookie(Cookie(SessionCookies.NAME, session))
				bearer(access)
			}.andExpect { status { isUnauthorized() } }
		mockMvc.get("/api/v1/auth/me") { cookie(Cookie(SessionCookies.NAME, session)) }.andExpect { status { isOk() } }
	}

	@Test
	fun `another authorization scheme leaves the session cookie in charge`() {
		val session = webLogin()

		mockMvc
			.get("/api/v1/auth/me") {
				cookie(Cookie(SessionCookies.NAME, session))
				header(HttpHeaders.AUTHORIZATION, "Basic amFuZS5kb2U6ZmljdGl0aW91cw==")
			}.andExpect { status { isOk() } }
		mockMvc
			.get("/api/v1/auth/me") { header(HttpHeaders.AUTHORIZATION, "Basic amFuZS5kb2U6ZmljdGl0aW91cw==") }
			.andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `the bearer principal is the account owner`() {
		val access = tokens(issue()).access

		me(access).andExpect {
			status { isOk() }
			jsonPath("$.username") { value(USERNAME) }
		}
	}

	// --- Revoked and expired tokens on state-changing requests (the app sends no cookies) ---

	@Test
	fun `a revoked access token answers 401 on state-changing requests too`() {
		val phone = tokens(issue(deviceName = "Pixel 9"))
		val tablet = tokens(issue(deviceName = "Tablet"))
		mockMvc.delete("/api/v1/auth/devices/${phone.deviceId}") { bearer(tablet.access) }.andExpect { status { isNoContent() } }

		patchSettings(phone.access).andExpect { status { isUnauthorized() } }
		mockMvc.delete("/api/v1/auth/devices/${tablet.deviceId}") { bearer(phone.access) }.andExpect { status { isUnauthorized() } }
		deviceCount() shouldBe 1
	}

	@Test
	fun `an expired access token answers 401 on state-changing requests, so the app knows to refresh`() {
		val phone = tokens(issue())
		clock.advance(AuthDevice.ACCESS_LIFETIME)

		patchSettings(phone.access).andExpect { status { isUnauthorized() } }
	}

	// --- Device management ---

	@Test
	fun `device management needs a login`() {
		val phone = tokens(issue())

		mockMvc.get("/api/v1/auth/devices").andExpect { status { isUnauthorized() } }
		mockMvc.delete("/api/v1/auth/devices/${phone.deviceId}") { with(csrf()) }.andExpect { status { isUnauthorized() } }
		mockMvc.get("/api/v1/auth/devices") { bearer("not-an-access-token") }.andExpect { status { isUnauthorized() } }
		deviceCount() shouldBe 1
	}

	@Test
	fun `revoking with a session cookie needs the CSRF token`() {
		val phone = tokens(issue())
		val session = webLogin()

		mockMvc
			.delete("/api/v1/auth/devices/${phone.deviceId}") { cookie(Cookie(SessionCookies.NAME, session)) }
			.andExpect { status { isForbidden() } }
		deviceCount() shouldBe 1
	}

	@Test
	fun `a device can revoke itself, and an id that is not a UUID is a 400`() {
		val phone = tokens(issue())

		mockMvc.delete("/api/v1/auth/devices/not-a-uuid") { bearer(phone.access) }.isProblem(400)
		mockMvc.delete("/api/v1/auth/devices/${phone.deviceId}") { bearer(phone.access) }.andExpect { status { isNoContent() } }

		me(phone.access).andExpect { status { isUnauthorized() } }
		refresh(phone.refresh).isProblem(401)
	}

	@Test
	fun `the device list shows when a device was created and last refreshed`() {
		val first = tokens(issue())
		val issuedAt = clock.instant()
		clock.advance(Duration.ofMinutes(10))
		val second = tokens(refresh(first.refresh).andExpect { status { isOk() } })

		listDevices(second.access).andExpect {
			jsonPath("$.length()") { value(1) }
			jsonPath("$[0].name") { value("Pixel 9") }
			jsonPath("$[0].createdAt") { value(issuedAt.toString()) }
			jsonPath("$[0].lastSeenAt") { value(clock.instant().toString()) }
		}
	}

	// --- Lifetimes and cleanup ---

	@Test
	fun `a refresh just before the 90 days succeeds and restarts the lifetime`() {
		val first = tokens(issue())
		clock.advance(AuthDevice.REFRESH_LIFETIME.minusSeconds(1))

		val second = tokens(refresh(first.refresh).andExpect { status { isOk() } })

		clock.advance(AuthDevice.REFRESH_LIFETIME.minusSeconds(1))
		refresh(second.refresh).andExpect { status { isOk() } }
	}

	@Test
	fun `an expired device is deleted by the next token request and its retired tokens go with it`() {
		val old = tokens(issue(deviceName = "Old phone"))
		val rotated = tokens(refresh(old.refresh).andExpect { status { isOk() } })
		clock.advance(AuthDevice.REFRESH_LIFETIME)

		issue(deviceName = "New phone").andExpect { status { isOk() } }

		jdbc.queryForList("select name from auth_device", String::class.java) shouldContainExactly listOf("New phone")
		jdbc.queryForObject("select count(*) from auth_device_retired_token", Int::class.java) shouldBe 0
		refresh(rotated.refresh).isProblem(401)
	}

	@Test
	fun `an expired refresh is answered 401 and deletes the device`() {
		val first = tokens(issue())
		clock.advance(AuthDevice.REFRESH_LIFETIME)

		refresh(first.refresh).isProblem(401)

		deviceCount() shouldBe 0
	}

	@Test
	fun `retired tokens older than the refresh lifetime are cleaned up, newer ones are kept`() {
		val first = tokens(issue(deviceName = "Pixel 9"))
		val second = tokens(refresh(first.refresh).andExpect { status { isOk() } })
		clock.advance(Duration.ofDays(1))
		refresh(second.refresh).andExpect { status { isOk() } }
		clock.advance(AuthDevice.REFRESH_LIFETIME.minusDays(1))

		issue(deviceName = "Tablet").andExpect { status { isOk() } }

		jdbc.queryForList("select token_hash from auth_device_retired_token", String::class.java) shouldContainExactly
			listOf(LoginService.hash(second.refresh))
		deviceCount() shouldBe 2
	}

	@Test
	fun `an expired device not yet cleaned up is not listed`() {
		issue(deviceName = "Old phone").andExpect { status { isOk() } }
		clock.advance(AuthDevice.REFRESH_LIFETIME.minusDays(1))
		issue(deviceName = "New phone").andExpect { status { isOk() } }
		clock.advance(Duration.ofDays(1))
		val session = webLogin()

		mockMvc
			.get("/api/v1/auth/devices") { cookie(Cookie(SessionCookies.NAME, session)) }
			.andExpect {
				status { isOk() }
				jsonPath("$.length()") { value(1) }
				jsonPath("$[0].name") { value("New phone") }
			}
		deviceCount() shouldBe 2
	}

	@Test
	fun `deleting the account deletes its devices`() {
		val phone = tokens(issue())

		jdbc.update("delete from app_user")

		deviceCount() shouldBe 0
		me(phone.access).andExpect { status { isUnauthorized() } }
	}

	// --- Logs ---

	@Test
	fun `issuing, refreshing, reusing and revoking never log a password, token or token hash`(output: CapturedOutput) {
		// Only this test's output: MockMvc prints the request of a failed test in this class, which is not a log.
		val start = output.all.length
		issue(password = "wrong-fictitious-password").isProblem(401)
		val first = tokens(issue(totp = null, recoveryCode = recoveryCodes[3]))
		val second = tokens(refresh(first.refresh).andExpect { status { isOk() } })
		refresh(first.refresh).isProblem(401)
		val third = tokens(issue(deviceName = "Tablet"))
		mockMvc.delete("/api/v1/auth/devices/${third.deviceId}") { bearer(third.access) }.andExpect { status { isNoContent() } }

		val logs = output.all.substring(start)
		logs shouldContain "Device ${first.deviceId} logged in"
		logs shouldContain "Rotated refresh token of device ${first.deviceId} was used again; device revoked"
		logs shouldContain "Device ${third.deviceId} revoked"
		logs shouldNotContain PASSWORD
		logs shouldNotContain "wrong-fictitious-password"
		logs shouldNotContain recoveryCodes[3]
		listOf(first, second, third).forEach { tokens ->
			logs shouldNotContain tokens.access
			logs shouldNotContain tokens.refresh
			logs shouldNotContain LoginService.hash(tokens.access)
			logs shouldNotContain LoginService.hash(tokens.refresh)
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

	private fun deviceCount() = jdbc.queryForObject("select count(*) from auth_device", Int::class.java)

	private fun usedRecoveryCodes() = jdbc.queryForObject("select count(*) from recovery_code where used_at is not null", Int::class.java)

	private fun storedHashes(): Pair<String, String> {
		val row = jdbc.queryForMap("select access_token_hash, refresh_token_hash from auth_device")
		return row["access_token_hash"] as String to row["refresh_token_hash"] as String
	}

	private fun tokenBody(
		username: String = USERNAME,
		password: String = PASSWORD,
		totp: String? = currentCode(),
		recoveryCode: String? = null,
		deviceName: String = "Pixel 9",
	): String =
		jsonMapper.writeValueAsString(
			mapOf(
				"username" to username,
				"password" to password,
				"totp" to totp,
				"recoveryCode" to recoveryCode,
				"deviceName" to deviceName,
			),
		)

	private fun issue(
		password: String = PASSWORD,
		totp: String? = currentCode(),
		recoveryCode: String? = null,
		deviceName: String = "Pixel 9",
	) = postToken(tokenBody(password = password, totp = totp, recoveryCode = recoveryCode, deviceName = deviceName))

	private fun postToken(body: String) =
		mockMvc.post("/api/v1/auth/token") {
			contentType = MediaType.APPLICATION_JSON
			content = body
		}

	private fun refresh(token: String) = postRefresh(jsonMapper.writeValueAsString(mapOf("refreshToken" to token)))

	private fun postRefresh(body: String) =
		mockMvc.post("/api/v1/auth/token/refresh") {
			contentType = MediaType.APPLICATION_JSON
			content = body
		}

	private fun webLogin(): String =
		mockMvc
			.post("/api/v1/auth/login") {
				contentType = MediaType.APPLICATION_JSON
				content = jsonMapper.writeValueAsString(mapOf("username" to USERNAME, "password" to PASSWORD, "totp" to currentCode()))
				with(csrf())
			}.andExpect { status { isOk() } }
			.andReturn()
			.response
			.getCookie(SessionCookies.NAME)!!
			.value

	private fun patchSettings(access: String) =
		mockMvc.patch("/api/v1/settings") {
			bearer(access)
			header(HttpHeaders.IF_MATCH, "*")
			contentType = MediaType.APPLICATION_JSON
			content = """{"theme": "dark"}"""
		}

	private fun me(access: String) = mockMvc.get("/api/v1/auth/me") { bearer(access) }

	private fun listDevices(access: String) = mockMvc.get("/api/v1/auth/devices") { bearer(access) }.andExpect { status { isOk() } }

	private fun MockHttpServletRequestDsl.bearer(token: String) = header(HttpHeaders.AUTHORIZATION, "Bearer $token")

	private fun tokens(result: ResultActionsDsl): Tokens {
		val body = jsonMapper.readValue(result.andReturn().response.contentAsString, Map::class.java)
		return Tokens(body["deviceId"] as String, body["accessToken"] as String, body["refreshToken"] as String)
	}

	private fun ResultActionsDsl.isProblem(status: Int) =
		andExpect {
			status { isEqualTo(status) }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		}

	private class Tokens(
		val deviceId: String,
		val access: String,
		val refresh: String,
	)

	private companion object {
		const val USERNAME = "jane.doe"
		const val PASSWORD = "correct-horse-battery-staple"
		const val THREADS = 4
	}
}
