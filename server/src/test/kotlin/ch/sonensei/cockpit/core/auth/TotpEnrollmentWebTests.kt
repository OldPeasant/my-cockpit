package ch.sonensei.cockpit.core.auth

import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.LoginThrottle
import ch.sonensei.cockpit.core.auth.domain.SetupService
import ch.sonensei.cockpit.core.auth.domain.Totp
import ch.sonensei.cockpit.core.auth.infra.SessionCookies
import ch.sonensei.cockpit.core.auth.infra.UserAccountRepository
import ch.sonensei.cockpit.core.crypto.SecretCipher
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
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
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * The forced TOTP re-enrollment after a restore without the TOTP secret (SPEC.md §10.2), against a real account and
 * session cookies. The restore is simulated as `UserBackupContributor` does it: a fresh random TOTP secret that nobody
 * knows and `totp_reenroll_required` set. The full restore flow is in `BackupSecretsTest`.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
class TotpEnrollmentWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val clock: MutableClock,
	@Autowired private val setup: SetupService,
	@Autowired private val throttle: LoginThrottle,
	@Autowired private val users: UserAccountRepository,
	@Autowired private val cipher: SecretCipher,
) : PostgresTestBase() {
	private val totp = Totp()
	private lateinit var originalSecret: String
	private lateinit var recoveryCodes: List<String>

	@BeforeEach
	fun activeAccount() {
		clock.set(MutableClockTestConfiguration.START)
		throttle.recordSuccess()
		jdbc.update("delete from app_user")
		val enrollment = setup.start(USERNAME, PASSWORD)
		originalSecret = enrollment.totpSecret
		recoveryCodes = enrollment.recoveryCodes
		setup.confirmTotp(code(originalSecret))
	}

	/** What a restore without the TOTP secret leaves: an unknown random secret and the re-enrollment flag. */
	private fun restoredWithoutTotpSecret() {
		jdbc.update(
			"update app_user set totp_secret = ?, totp_reenroll_required = true",
			cipher.encrypt(Base32.encode(Totp.newSecret())),
		)
	}

	private fun code(secret: String) = totp.generate(Base32.decode(secret), clock.instant())

	private fun login(
		totp: String? = null,
		recoveryCode: String? = null,
		password: String = PASSWORD,
	): ResultActionsDsl =
		mockMvc.post("/api/v1/auth/login") {
			with(csrf())
			contentType = MediaType.APPLICATION_JSON
			content =
				jsonMapper.writeValueAsString(
					mapOf("username" to USERNAME, "password" to password, "totp" to totp, "recoveryCode" to recoveryCode),
				)
		}

	private fun session(login: ResultActionsDsl): Cookie =
		Cookie(
			SessionCookies.NAME,
			login
				.andExpect { status { isOk() } }
				.andReturn()
				.response
				.getCookie(SessionCookies.NAME)
				.shouldNotBeNull()
				.value,
		)

	/** A session of the restored account, logged in with a recovery code. */
	private fun enrollmentSession(): Cookie = session(login(recoveryCode = recoveryCodes[0])).also { throttle.recordSuccess() }

	private fun start(session: Cookie): ResultActionsDsl =
		mockMvc.post(ENROLLMENT) {
			cookie(session)
			with(csrf())
		}

	private fun startedSecret(session: Cookie): String = json(start(session).andExpect { status { isOk() } })["totpSecret"].asString()

	private fun confirm(
		session: Cookie,
		code: String,
	): ResultActionsDsl =
		mockMvc.post("$ENROLLMENT/confirm") {
			cookie(session)
			with(csrf())
			contentType = MediaType.APPLICATION_JSON
			content = jsonMapper.writeValueAsString(mapOf("code" to code))
		}

	private fun token(
		recoveryCode: String? = null,
		totp: String? = null,
		password: String = PASSWORD,
	): ResultActionsDsl =
		mockMvc.post("/api/v1/auth/token") {
			contentType = MediaType.APPLICATION_JSON
			content =
				jsonMapper.writeValueAsString(
					mapOf(
						"username" to USERNAME,
						"password" to password,
						"totp" to totp,
						"recoveryCode" to recoveryCode,
						"deviceName" to "Pixel 9",
					),
				)
		}

	private fun json(result: ResultActionsDsl): JsonNode = jsonMapper.readTree(result.andReturn().response.contentAsString)

	private fun reenrollRequired() = users.findFirstBy().shouldNotBeNull().totpReenrollRequired

	@Test
	fun `start returns a new secret with its otpauth URI, not cached, and stores it while the flag stays set`() {
		restoredWithoutTotpSecret()
		val session = enrollmentSession()

		val result =
			start(session).andExpect {
				status { isOk() }
				header { string(HttpHeaders.CACHE_CONTROL, "no-store") }
			}
		val body = json(result)
		val secret = body["totpSecret"].asString()

		Base32.decode(secret).size shouldBe Totp.SECRET_BYTES
		secret shouldNotBe originalSecret
		body["otpauthUri"].asString() shouldStartWith "otpauth://totp/My%20Cockpit:$USERNAME?secret=$secret&issuer=My%20Cockpit"
		users
			.findFirstBy()
			.shouldNotBeNull()
			.totpSecret
			.reveal() shouldBe secret
		reenrollRequired() shouldBe true
	}

	@Test
	fun `a second start replaces the secret, only a code of the latest one confirms`() {
		restoredWithoutTotpSecret()
		val session = enrollmentSession()
		val first = startedSecret(session)
		val second = startedSecret(session)

		second shouldNotBe first
		confirm(session, code(first)).andExpect { status { isBadRequest() } }
		reenrollRequired() shouldBe true
		confirm(session, code(second)).andExpect { status { isNoContent() } }
		reenrollRequired() shouldBe false
	}

	@Test
	fun `confirm without start, with the old secret's code or with a wrong code is a 400 and keeps the flag`() {
		restoredWithoutTotpSecret()
		val session = enrollmentSession()

		confirm(session, code(originalSecret)).andExpect {
			status { isBadRequest() }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		}
		val secret = startedSecret(session)
		confirm(session, "000000".takeUnless { it == code(secret) } ?: "111111").andExpect { status { isBadRequest() } }
		confirm(session, " ").andExpect { status { isBadRequest() } }
		confirm(session, "1".repeat(17)).andExpect { status { isBadRequest() } }
		reenrollRequired() shouldBe true
		withClue("surrounding blanks of a valid code are ignored") {
			confirm(session, " ${code(secret)} ").andExpect { status { isNoContent() } }
		}
	}

	@Test
	fun `wrong codes share the login throttle and are blocked after the free attempts`() {
		restoredWithoutTotpSecret()
		val session = enrollmentSession()
		val secret = startedSecret(session)
		val wrong = if (code(secret) == "000000") "111111" else "000000"

		repeat(LoginThrottle.FREE_ATTEMPTS) { confirm(session, wrong).andExpect { status { isBadRequest() } } }

		confirm(session, code(secret)).andExpect {
			status { isTooManyRequests() }
			header { string(HttpHeaders.RETRY_AFTER, LoginThrottle.BASE_DELAY.seconds.toString()) }
		}
		withClue("the login is blocked too") { login(totp = code(originalSecret)).andExpect { status { isTooManyRequests() } } }
		reenrollRequired() shouldBe true

		clock.advance(LoginThrottle.BASE_DELAY.plusSeconds(1))
		confirm(session, code(secret)).andExpect { status { isNoContent() } }
		withClue("a successful confirmation resets the throttle") {
			repeat(LoginThrottle.FREE_ATTEMPTS - 1) { login(totp = wrong).andExpect { status { isUnauthorized() } } }
		}
	}

	@Test
	fun `an account that does not need re-enrollment cannot replace its TOTP secret this way`() {
		val session = session(login(totp = code(originalSecret)))

		start(session).andExpect {
			status { isConflict() }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		}
		confirm(session, code(originalSecret)).andExpect { status { isConflict() } }
		users
			.findFirstBy()
			.shouldNotBeNull()
			.totpSecret
			.reveal() shouldBe originalSecret
	}

	@Test
	fun `the enrollment endpoints need a session and the CSRF token`() {
		mockMvc.post(ENROLLMENT) { with(csrf()) }.andExpect { status { isUnauthorized() } }
		mockMvc
			.post("$ENROLLMENT/confirm") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = """{"code":"123456"}"""
			}.andExpect { status { isUnauthorized() } }

		restoredWithoutTotpSecret()
		val session = enrollmentSession()
		mockMvc.post(ENROLLMENT) { cookie(session) }.andExpect { status { isForbidden() } }
		reenrollRequired() shouldBe true
	}

	@Test
	fun `a session that must enroll TOTP may only call me, logout and the enrollment`() {
		restoredWithoutTotpSecret()
		val session = enrollmentSession()

		mockMvc.get("/api/v1/auth/me") { cookie(session) }.andExpect {
			status { isOk() }
			jsonPath("$.username") { value(USERNAME) }
			jsonPath("$.totpEnrollmentRequired") { value(true) }
		}
		listOf("/api/v1/settings", "/api/v1/auth/devices", "/api/v1/backup/00000000-0000-7000-8000-000000000000").forEach { path ->
			withClue("GET $path") { mockMvc.get(path) { cookie(session) }.andExpect { status { isForbidden() } } }
		}
		withClue("POST /api/v1/backup") {
			mockMvc
				.post("/api/v1/backup") {
					cookie(session)
					with(csrf())
				}.andExpect { status { isForbidden() } }
		}
		mockMvc
			.post("/api/v1/auth/logout") {
				cookie(session)
				with(csrf())
			}.andExpect { status { isNoContent() } }
		mockMvc.get("/api/v1/auth/me") { cookie(session) }.andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `TOTP does not log in while re-enrolling, not even with the started but unconfirmed secret`() {
		restoredWithoutTotpSecret()
		val session = enrollmentSession()
		val started = startedSecret(session)

		login(totp = code(started)).andExpect { status { isUnauthorized() } }
		login(totp = code(originalSecret)).andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `without unused recovery codes the password alone logs in to re-enroll, with unused ones it does not`() {
		restoredWithoutTotpSecret()

		login().andExpect { status { isUnauthorized() } }
		throttle.recordSuccess()

		jdbc.update("update recovery_code set used_at = now()")
		val session =
			session(
				login().andExpect {
					status { isOk() }
					jsonPath("$.totpEnrollmentRequired") { value(true) }
				},
			)
		withClue("a wrong password alone still fails") { login(password = "fictitious-wrong-password").andExpect { status { isUnauthorized() } } }
		throttle.recordSuccess()
		mockMvc.get("/api/v1/settings") { cookie(session) }.andExpect { status { isForbidden() } }
		val secret = startedSecret(session)
		confirm(session, code(secret)).andExpect { status { isNoContent() } }

		withClue("after the re-enrollment the password alone no longer logs in") {
			login().andExpect { status { isUnauthorized() } }
		}
	}

	@Test
	fun `the password alone never logs in an account that does not re-enroll, even without unused recovery codes`() {
		jdbc.update("update recovery_code set used_at = now()")

		login().andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `device tokens are refused with 403 while re-enrolling, the recovery code is not used up, and wrong credentials stay 401`() {
		restoredWithoutTotpSecret()

		token(recoveryCode = recoveryCodes[0]).andExpect {
			status { isForbidden() }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		}
		throttle.recordSuccess()
		withClue("a wrong password does not reveal that the account re-enrolls") {
			token(recoveryCode = recoveryCodes[0], password = "fictitious-wrong-password").andExpect { status { isUnauthorized() } }
		}
		throttle.recordSuccess()
		withClue("the refused token request did not use up the recovery code") {
			login(recoveryCode = recoveryCodes[0]).andExpect {
				status { isOk() }
				jsonPath("$.totpEnrollmentRequired") { value(true) }
			}
		}
		jdbc.queryForObject("select count(*) from auth_device", Int::class.java) shouldBe 0
	}

	@Test
	fun `after the re-enrollment the new secret logs in everywhere with full access`() {
		restoredWithoutTotpSecret()
		val session = enrollmentSession()
		val secret = startedSecret(session)
		confirm(session, code(secret)).andExpect { status { isNoContent() } }

		mockMvc.get("/api/v1/auth/me") { cookie(session) }.andExpect { jsonPath("$.totpEnrollmentRequired") { value(false) } }
		mockMvc.get("/api/v1/settings") { cookie(session) }.andExpect { status { isOk() } }
		clock.advance(Duration.ofSeconds(Totp.STEP.seconds))
		token(totp = code(secret)).andExpect { status { isOk() } }
		start(session).andExpect { status { isConflict() } }
		withClue("the recovery code used for the enrollment login is used up") {
			login(recoveryCode = recoveryCodes[0]).andExpect { status { isUnauthorized() } }
		}
	}

	companion object {
		private const val USERNAME = "jane.doe"
		private const val PASSWORD = "fictitious-password-1234"
		private const val ENROLLMENT = "/api/v1/auth/totp-enrollment"
	}
}
