package ch.sonensei.cockpit.core.auth

import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.LoginThrottle
import ch.sonensei.cockpit.core.auth.domain.RecoveryCodes
import ch.sonensei.cockpit.core.auth.domain.SetupService
import ch.sonensei.cockpit.core.auth.domain.Totp
import ch.sonensei.cockpit.core.auth.infra.SessionCookies
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.nulls.shouldBeNull
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/** Regenerating recovery codes (task p0-web-devices) against a real account, Postgres and a test clock. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class RecoveryCodeWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val clock: MutableClock,
	@Autowired private val setup: SetupService,
	@Autowired private val throttle: LoginThrottle,
) : PostgresTestBase() {
	private val totp = Totp()
	private lateinit var secret: ByteArray
	private lateinit var oldCodes: List<String>
	private lateinit var sessionToken: String

	@BeforeEach
	fun activeAccount() {
		clock.set(MutableClockTestConfiguration.START)
		throttle.recordSuccess()
		jdbc.update("delete from app_user")
		val enrollment = setup.start(USERNAME, PASSWORD)
		secret = Base32.decode(enrollment.totpSecret)
		oldCodes = enrollment.recoveryCodes
		setup.confirmTotp(currentCode())
		sessionToken = login(mapOf("totp" to currentCode())).andExpect { status { isOk() } }.sessionCookie()
	}

	@Test
	fun `a current TOTP code replaces all recovery codes, returned once and stored only as hashes`() {
		val response =
			regenerate(currentCode())
				.andExpect {
					status { isOk() }
					header { string(HttpHeaders.CACHE_CONTROL, "no-store") }
				}.andReturn()
				.response

		@Suppress("UNCHECKED_CAST")
		val codes = json(response.contentAsString)["recoveryCodes"] as List<String>

		codes shouldHaveSize RecoveryCodes.COUNT
		codes.toSet() shouldHaveSize RecoveryCodes.COUNT
		codes shouldNotContainAnyOf oldCodes
		jdbc.queryForObject("select count(*) from recovery_code", Int::class.java) shouldBe RecoveryCodes.COUNT
		jdbc.queryForObject("select count(*) from recovery_code where used_at is not null", Int::class.java) shouldBe 0
		val hashes = jdbc.queryForList("select code_hash from recovery_code", String::class.java)
		hashes shouldNotContainAnyOf codes

		// Old codes stop working, new ones log in.
		login(mapOf("recoveryCode" to oldCodes[0])).andExpect { status { isUnauthorized() } }
		login(mapOf("recoveryCode" to codes[0])).andExpect { status { isOk() } }
	}

	@Test
	fun `a used code is replaced too`() {
		login(mapOf("recoveryCode" to oldCodes[1])).andExpect { status { isOk() } }

		regenerate(currentCode()).andExpect { status { isOk() } }

		jdbc.queryForObject("select count(*) from recovery_code where used_at is not null", Int::class.java) shouldBe 0
	}

	@Test
	fun `a wrong or missing TOTP code is a 400 and keeps the old codes`() {
		val wrong = totp.generate(secret, clock.instant().plus(Duration.ofMinutes(10)))
		regenerate(wrong).isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("totp") } }
		regenerate(" ").isProblem(400)

		login(mapOf("recoveryCode" to oldCodes[0])).andExpect { status { isOk() } }
	}

	@Test
	fun `wrong TOTP codes are throttled like logins`() {
		val wrong = totp.generate(secret, clock.instant().plus(Duration.ofMinutes(10)))
		repeat(LoginThrottle.FREE_ATTEMPTS - 1) { regenerate(wrong).isProblem(400) }
		regenerate(wrong).isProblem(400)

		regenerate(currentCode()).isProblem(429).andExpect { header { string(HttpHeaders.RETRY_AFTER, "30") } }
	}

	@Test
	fun `it needs a session and a CSRF token`() {
		mockMvc
			.post("/api/v1/auth/recovery-codes") {
				contentType = MediaType.APPLICATION_JSON
				content = jsonMapper.writeValueAsString(mapOf("totp" to currentCode()))
				with(csrf())
			}.andExpect { status { isUnauthorized() } }
		mockMvc
			.post("/api/v1/auth/recovery-codes") {
				cookie(Cookie(SessionCookies.NAME, sessionToken))
				contentType = MediaType.APPLICATION_JSON
				content = jsonMapper.writeValueAsString(mapOf("totp" to currentCode()))
			}.andExpect { status { isForbidden() } }
		jdbc.queryForObject("select count(*) from recovery_code where used_at is null", Int::class.java) shouldBe RecoveryCodes.COUNT
	}

	@Test
	fun `a success resets the throttle so earlier wrong codes no longer count`() {
		val wrong = wrongCode()
		repeat(LoginThrottle.FREE_ATTEMPTS - 1) { regenerate(wrong).isProblem(400) }

		regenerate(currentCode()).andExpect { status { isOk() } }

		throttle.retryAfter().shouldBeNull()
		repeat(LoginThrottle.FREE_ATTEMPTS - 1) { regenerate(wrong).isProblem(400) }
		throttle.retryAfter().shouldBeNull()
	}

	@Test
	fun `a throttled request with a correct code does not touch the codes`() {
		val before = codeHashes()
		repeat(LoginThrottle.FREE_ATTEMPTS) { regenerate(wrongCode()).isProblem(400) }

		regenerate(currentCode()).isProblem(429)

		codeHashes() shouldContainExactlyInAnyOrder before
		// Once the block has passed, a correct code works again.
		clock.advance(LoginThrottle.BASE_DELAY)
		regenerate(currentCode()).andExpect { status { isOk() } }
	}

	@Test
	fun `a TOTP code with surrounding whitespace is accepted`() {
		regenerate(" ${currentCode()} ").andExpect { status { isOk() } }
	}

	@Test
	fun `a missing, over-long or non-JSON TOTP field is a 400 and keeps the codes`() {
		val before = codeHashes()
		post("{}").isProblem(400)
		post(jsonMapper.writeValueAsString(mapOf("totp" to "1".repeat(17)))).isProblem(400)
		post(jsonMapper.writeValueAsString(mapOf("totp" to null))).isProblem(400)
		post("not json").andExpect { status { isBadRequest() } }

		codeHashes() shouldContainExactlyInAnyOrder before
	}

	@Test
	fun `an authenticated request without an active account is a 409 and creates no codes`() {
		jdbc.update("delete from app_user")
		regenerateAsCockpitUser(currentCode()).isProblem(409)

		// An account whose TOTP was never confirmed is not active either.
		val enrollment = setup.start(USERNAME, PASSWORD)
		val before = codeHashes()
		val pendingCode = totp.generate(Base32.decode(enrollment.totpSecret), clock.instant())
		regenerateAsCockpitUser(pendingCode).isProblem(409)

		codeHashes() shouldContainExactlyInAnyOrder before
	}

	@Test
	fun `neither the TOTP code nor the new recovery codes are logged`(output: CapturedOutput) {
		val wrong = wrongCode()
		regenerate(wrong).isProblem(400)
		val code = currentCode()
		val response = regenerate(code).andExpect { status { isOk() } }.andReturn().response

		@Suppress("UNCHECKED_CAST")
		val codes = json(response.contentAsString)["recoveryCodes"] as List<String>

		val logs = output.all
		logs shouldContain "Recovery code regeneration rejected"
		logs shouldContain "Recovery codes regenerated"
		codes.forEach { logs shouldNotContain it }
		// Six-digit codes can occur in timestamps by chance; check the log messages themselves.
		MESSAGE.findAll(logs).map { it.groupValues[1] }.forEach { message ->
			message shouldNotContain code
			message shouldNotContain wrong
		}
	}

	private fun wrongCode() = totp.generate(secret, clock.instant().plus(Duration.ofMinutes(10)))

	private fun codeHashes() = jdbc.queryForList("select code_hash from recovery_code", String::class.java)

	private fun post(body: String) =
		mockMvc.post("/api/v1/auth/recovery-codes") {
			cookie(Cookie(SessionCookies.NAME, sessionToken))
			contentType = MediaType.APPLICATION_JSON
			content = body
			with(csrf())
		}

	private fun regenerateAsCockpitUser(code: String) =
		mockMvc.post("/api/v1/auth/recovery-codes") {
			contentType = MediaType.APPLICATION_JSON
			content = jsonMapper.writeValueAsString(mapOf("totp" to code))
			with(cockpitUser())
			with(csrf())
		}

	private fun currentCode() = totp.generate(secret, clock.instant())

	private fun regenerate(code: String) =
		mockMvc.post("/api/v1/auth/recovery-codes") {
			cookie(Cookie(SessionCookies.NAME, sessionToken))
			contentType = MediaType.APPLICATION_JSON
			content = jsonMapper.writeValueAsString(mapOf("totp" to code))
			with(csrf())
		}

	private fun login(secondFactor: Map<String, String>) =
		mockMvc.post("/api/v1/auth/login") {
			contentType = MediaType.APPLICATION_JSON
			content = jsonMapper.writeValueAsString(mapOf("username" to USERNAME, "password" to PASSWORD) + secondFactor)
			with(csrf())
		}

	private fun ResultActionsDsl.sessionCookie(): String =
		andReturn()
			.response
			.getCookie(SessionCookies.NAME)!!
			.value

	private fun json(body: String): Map<*, *> = jsonMapper.readValue(body, Map::class.java)

	private fun ResultActionsDsl.isProblem(status: Int) =
		andExpect {
			status { isEqualTo(status) }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		}

	private companion object {
		const val USERNAME = "jane.doe"
		const val PASSWORD = "correct-horse-battery-staple"
		val MESSAGE = Regex("\"message\":\"((?:[^\"\\\\]|\\\\.)*)\"")
	}
}
