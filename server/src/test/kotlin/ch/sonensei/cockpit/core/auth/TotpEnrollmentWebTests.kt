package ch.sonensei.cockpit.core.auth

import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.DeviceTokenResult
import ch.sonensei.cockpit.core.auth.domain.DeviceTokenService
import ch.sonensei.cockpit.core.auth.domain.LoginCredentials
import ch.sonensei.cockpit.core.auth.domain.LoginResult
import ch.sonensei.cockpit.core.auth.domain.LoginService
import ch.sonensei.cockpit.core.auth.domain.LoginThrottle
import ch.sonensei.cockpit.core.auth.domain.SetupService
import ch.sonensei.cockpit.core.auth.domain.Totp
import ch.sonensei.cockpit.core.auth.domain.TotpEnrollment
import ch.sonensei.cockpit.core.auth.domain.TotpEnrollmentResult
import ch.sonensei.cockpit.core.auth.domain.TotpEnrollmentService
import ch.sonensei.cockpit.core.auth.infra.SessionCookies
import ch.sonensei.cockpit.core.auth.infra.UserAccountRepository
import ch.sonensei.cockpit.core.crypto.SecretCipher
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
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
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
	@Autowired private val deviceTokens: DeviceTokenService,
	@Autowired private val enrollment: TotpEnrollmentService,
	@Autowired private val loginService: LoginService,
	@Autowired private val transactions: TransactionTemplate,
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
		rememberDevice: Boolean = false,
	): ResultActionsDsl =
		mockMvc.post("/api/v1/auth/login") {
			with(csrf())
			contentType = MediaType.APPLICATION_JSON
			content =
				jsonMapper.writeValueAsString(
					mapOf(
						"username" to USERNAME,
						"password" to password,
						"totp" to totp,
						"recoveryCode" to recoveryCode,
						"rememberDevice" to rememberDevice,
					),
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
	fun `a confirm answered with 409 is no failed attempt and does not throttle the login`() {
		val session = session(login(totp = code(originalSecret)))

		repeat(LoginThrottle.FREE_ATTEMPTS + 1) { confirm(session, "000000").andExpect { status { isConflict() } } }

		val wrong = if (code(originalSecret) == "000000") "111111" else "000000"
		withClue("a wrong login is answered as wrong, not as throttled") {
			login(totp = wrong).andExpect { status { isUnauthorized() } }
		}
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
	fun `the password-only session of a re-enrolling account is restricted to the enrollment`() {
		restoredWithoutTotpSecret()
		jdbc.update("update recovery_code set used_at = now()")
		val session = session(login())

		mockMvc.get("/api/v1/auth/me") { cookie(session) }.andExpect {
			status { isOk() }
			jsonPath("$.totpEnrollmentRequired") { value(true) }
		}
		mockMvc.get("/api/v1/auth/devices") { cookie(session) }.andExpect { status { isForbidden() } }
		withClue("POST /api/v1/backup") {
			mockMvc
				.post("/api/v1/backup") {
					cookie(session)
					with(csrf())
				}.andExpect { status { isForbidden() } }
		}
		reenrollRequired() shouldBe true
	}

	@Test
	fun `a device token request without any second factor creates no device, also when no unused recovery code is left`() {
		restoredWithoutTotpSecret()
		jdbc.update("update recovery_code set used_at = now()")

		withClue("the token endpoint still needs exactly one factor") { token().andExpect { status { isBadRequest() } } }
		withClue("TOTP does not work while re-enrolling") { token(totp = code(originalSecret)).andExpect { status { isUnauthorized() } } }
		throttle.recordSuccess()
		withClue("the service refuses the password alone for a device, before any device is stored") {
			val result = deviceTokens.issue(LoginCredentials(USERNAME, PASSWORD, null, null, rememberDevice = false), "Pixel 9")
			result.shouldBeInstanceOf<DeviceTokenResult.Rejected>().result shouldBe LoginResult.TotpEnrollmentRequired
		}
		throttle.recordSuccess()
		withClue("with wrong password the service answers invalid credentials, not that the account re-enrolls") {
			val result = deviceTokens.issue(LoginCredentials(USERNAME, "fictitious-wrong-password", null, null, false), "Pixel 9")
			result.shouldBeInstanceOf<DeviceTokenResult.Rejected>().result shouldBe LoginResult.InvalidCredentials
		}
		jdbc.queryForObject("select count(*) from auth_device", Int::class.java) shouldBe 0
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
	fun `a confirm ends the other sessions of the account, also remembered and password-only ones, and keeps its own`() {
		restoredWithoutTotpSecret()
		val confirming = enrollmentSession()
		val remembered = session(login(recoveryCode = recoveryCodes[1], rememberDevice = true)).also { throttle.recordSuccess() }
		jdbc.update("update recovery_code set used_at = now() where used_at is null")
		val passwordOnly = session(login()).also { throttle.recordSuccess() }
		listOf(remembered, passwordOnly).forEach { other ->
			mockMvc.get("/api/v1/settings") { cookie(other) }.andExpect { status { isForbidden() } }
		}

		confirm(confirming, code(startedSecret(confirming))).andExpect { status { isNoContent() } }

		listOf("remembered" to remembered, "password-only" to passwordOnly).forEach { (name, other) ->
			withClue("the $name session is ended, not upgraded to full access") {
				mockMvc.get("/api/v1/settings") { cookie(other) }.andExpect { status { isUnauthorized() } }
				mockMvc
					.post("$ENROLLMENT/confirm") {
						cookie(other)
						with(csrf())
						contentType = MediaType.APPLICATION_JSON
						content = jsonMapper.writeValueAsString(mapOf("code" to "123456"))
					}.andExpect { status { isUnauthorized() } }
			}
		}
		withClue("the confirming session keeps full access") {
			mockMvc.get("/api/v1/settings") { cookie(confirming) }.andExpect { status { isOk() } }
		}
		jdbc.queryForObject("select count(*) from auth_session", Int::class.java) shouldBe 1
	}

	@Test
	fun `a start from another session replaces the secret, the first session's code fails and the other's confirm ends it`() {
		restoredWithoutTotpSecret()
		val first = enrollmentSession()
		val second = session(login(recoveryCode = recoveryCodes[1])).also { throttle.recordSuccess() }
		val firstSecret = startedSecret(first)
		val secondSecret = startedSecret(second)

		withClue("the first session cannot confirm the secret it received once another session replaced it") {
			confirm(first, code(firstSecret)).andExpect { status { isBadRequest() } }
			reenrollRequired() shouldBe true
		}
		throttle.recordSuccess()
		confirm(second, code(secondSecret)).andExpect { status { isNoContent() } }

		mockMvc.get("/api/v1/settings") { cookie(first) }.andExpect { status { isUnauthorized() } }
		mockMvc.get("/api/v1/settings") { cookie(second) }.andExpect { status { isOk() } }
	}

	@Test
	fun `a wrong or throttled confirm ends no session, only the successful one does`() {
		restoredWithoutTotpSecret()
		val confirming = enrollmentSession()
		val other = session(login(recoveryCode = recoveryCodes[1])).also { throttle.recordSuccess() }
		val secret = startedSecret(confirming)
		val wrong = if (code(secret) == "000000") "111111" else "000000"

		fun sessionCount() = jdbc.queryForObject("select count(*) from auth_session", Int::class.java)

		repeat(LoginThrottle.FREE_ATTEMPTS) { confirm(confirming, wrong).andExpect { status { isBadRequest() } } }
		withClue("invalid codes end no session") { sessionCount() shouldBe 2 }
		confirm(confirming, code(secret)).andExpect { status { isTooManyRequests() } }
		withClue("a throttled confirm, even with the right code, ends no session") {
			sessionCount() shouldBe 2
			mockMvc.get("/api/v1/auth/me") { cookie(other) }.andExpect { status { isOk() } }
		}

		clock.advance(LoginThrottle.BASE_DELAY.plusSeconds(1))
		confirm(confirming, code(secret)).andExpect { status { isNoContent() } }
		sessionCount() shouldBe 1
		mockMvc.get("/api/v1/auth/me") { cookie(other) }.andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `a login whose transaction runs while the confirm runs is ended by the confirm and never gets full access`() {
		restoredWithoutTotpSecret()
		val confirming = enrollmentSession()
		val secret = startedSecret(confirming)
		val loggedIn = CountDownLatch(1)
		val release = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		try {
			// The login has read the flag and written its session, but not committed yet.
			val login =
				executor.submit<LoginResult> {
					transactions.execute {
						loginService.login(LoginCredentials(USERNAME, PASSWORD, null, recoveryCodes[1], false)).also {
							loggedIn.countDown()
							release.await(10, TimeUnit.SECONDS)
						}
					}
				}
			loggedIn.await(10, TimeUnit.SECONDS) shouldBe true
			val confirmed = executor.submit<TotpEnrollmentResult> { enrollment.confirm(code(secret), confirming.value) }
			withClue("the confirm waits for the login's transaction") {
				shouldThrow<TimeoutException> { confirmed.get(500, TimeUnit.MILLISECONDS) }
			}
			release.countDown()
			val grant = login.get(10, TimeUnit.SECONDS).shouldBeInstanceOf<LoginResult.Success>().grant
			grant.principal.totpEnrollmentRequired shouldBe true
			confirmed.get(10, TimeUnit.SECONDS) shouldBe TotpEnrollmentResult.Confirmed

			mockMvc.get("/api/v1/auth/me") { cookie(Cookie(SessionCookies.NAME, grant.token)) }.andExpect { status { isUnauthorized() } }
			mockMvc.get("/api/v1/settings") { cookie(confirming) }.andExpect { status { isOk() } }
			jdbc.queryForObject("select count(*) from auth_session", Int::class.java) shouldBe 1
		} finally {
			release.countDown()
			executor.shutdownNow()
		}
	}

	/**
	 * Runs [block] in a transaction on [executor] and keeps that transaction (with the account row lock it took) open
	 * until [release] counts down. Returns once [block] has run, so the caller knows the lock is held.
	 */
	private fun <T : Any> heldTransaction(
		executor: ExecutorService,
		release: CountDownLatch,
		block: () -> T,
	): Future<T> {
		val ran = CountDownLatch(1)
		val future =
			executor.submit<T> {
				transactions.execute {
					block().also {
						ran.countDown()
						release.await(10, TimeUnit.SECONDS)
					}
				}
			}
		withClue("the held transaction ran its block") { ran.await(10, TimeUnit.SECONDS) shouldBe true }
		return future
	}

	@Test
	fun `a start that runs while a confirm is not committed yet waits, then sees the confirm and cannot undo it`() {
		restoredWithoutTotpSecret()
		val confirming = enrollmentSession()
		val secret = startedSecret(confirming)
		val release = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		try {
			val confirmed = heldTransaction(executor, release) { enrollment.confirm(code(secret), confirming.value) }
			val started = executor.submit<TotpEnrollment> { enrollment.start() }
			withClue("the start waits for the confirm's transaction") {
				shouldThrow<TimeoutException> { started.get(500, TimeUnit.MILLISECONDS) }
			}
			release.countDown()
			confirmed.get(10, TimeUnit.SECONDS) shouldBe TotpEnrollmentResult.Confirmed

			withClue("the start reads the committed confirm: re-enrollment is no longer required") {
				shouldThrow<ExecutionException> { started.get(10, TimeUnit.SECONDS) }.cause.shouldBeInstanceOf<ConflictException>()
			}
			reenrollRequired() shouldBe false
			users
				.findFirstBy()
				.shouldNotBeNull()
				.totpSecret
				.reveal() shouldBe secret
			mockMvc.get("/api/v1/settings") { cookie(confirming) }.andExpect { status { isOk() } }
			clock.advance(Duration.ofSeconds(Totp.STEP.seconds))
			withClue("the confirmed secret logs in") { login(totp = code(secret)).andExpect { status { isOk() } } }
		} finally {
			release.countDown()
			executor.shutdownNow()
		}
	}

	@Test
	fun `a confirm that runs while another session's start is not committed yet checks the code against the new secret`() {
		restoredWithoutTotpSecret()
		val first = enrollmentSession()
		val firstSecret = startedSecret(first)
		val release = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		try {
			val replaced = heldTransaction(executor, release) { enrollment.start() }
			val confirmed = executor.submit<TotpEnrollmentResult> { enrollment.confirm(code(firstSecret), first.value) }
			withClue("the confirm waits for the start's transaction") {
				shouldThrow<TimeoutException> { confirmed.get(500, TimeUnit.MILLISECONDS) }
			}
			release.countDown()
			val newSecret = replaced.get(10, TimeUnit.SECONDS).totpSecret

			withClue("the code of the replaced secret no longer confirms") {
				confirmed.get(10, TimeUnit.SECONDS) shouldBe TotpEnrollmentResult.InvalidCode
			}
			reenrollRequired() shouldBe true
			users
				.findFirstBy()
				.shouldNotBeNull()
				.totpSecret
				.reveal() shouldBe newSecret
			throttle.recordSuccess()
			enrollment.confirm(code(newSecret), first.value) shouldBe TotpEnrollmentResult.Confirmed
		} finally {
			release.countDown()
			executor.shutdownNow()
		}
	}

	@Test
	fun `a device token request that runs while a confirm is not committed yet waits, then gets a device with full access`() {
		restoredWithoutTotpSecret()
		val confirming = enrollmentSession()
		val secret = startedSecret(confirming)
		val release = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		try {
			val confirmed = heldTransaction(executor, release) { enrollment.confirm(code(secret), confirming.value) }
			val issued =
				executor.submit<DeviceTokenResult> {
					deviceTokens.issue(LoginCredentials(USERNAME, PASSWORD, null, recoveryCodes[1], rememberDevice = false), "Pixel 9")
				}
			withClue("the device login waits for the confirm's transaction") {
				shouldThrow<TimeoutException> { issued.get(500, TimeUnit.MILLISECONDS) }
			}
			release.countDown()
			confirmed.get(10, TimeUnit.SECONDS) shouldBe TotpEnrollmentResult.Confirmed

			withClue("the device login sees the completed re-enrollment instead of refusing with 'enrollment required'") {
				val tokens = issued.get(10, TimeUnit.SECONDS).shouldBeInstanceOf<DeviceTokenResult.Issued>().tokens
				deviceTokens.authenticate(tokens.accessToken).shouldNotBeNull().totpEnrollmentRequired shouldBe false
			}
			jdbc.queryForObject("select count(*) from auth_device", Int::class.java) shouldBe 1
		} finally {
			release.countDown()
			executor.shutdownNow()
		}
	}

	@Test
	fun `with the account row lock, device tokens and web logins still work for an account that does not re-enroll`() {
		val tokens =
			token(totp = code(originalSecret)).andExpect { status { isOk() } }.let(::json)
		tokens["accessToken"].asString().let { access ->
			deviceTokens.authenticate(access).shouldNotBeNull().totpEnrollmentRequired shouldBe false
		}
		clock.advance(Duration.ofSeconds(Totp.STEP.seconds))
		val session = session(login(totp = code(originalSecret)))
		mockMvc.get("/api/v1/settings") { cookie(session) }.andExpect { status { isOk() } }
		withClue("a device login with a recovery code still works and uses the code") {
			token(recoveryCode = recoveryCodes[2]).andExpect { status { isOk() } }
			token(recoveryCode = recoveryCodes[2]).andExpect { status { isUnauthorized() } }
		}
		jdbc.queryForObject("select count(*) from auth_device", Int::class.java) shouldBe 2
	}

	@Test
	fun `a confirm without a session token, as from a caller without a session cookie, ends every session of the account`() {
		restoredWithoutTotpSecret()
		val first = enrollmentSession()
		session(login(recoveryCode = recoveryCodes[1], rememberDevice = true)).also { throttle.recordSuccess() }
		val secret = enrollment.start().totpSecret

		enrollment.confirm(code(secret), null) shouldBe TotpEnrollmentResult.Confirmed

		jdbc.queryForObject("select count(*) from auth_session", Int::class.java) shouldBe 0
		mockMvc.get("/api/v1/auth/me") { cookie(first) }.andExpect { status { isUnauthorized() } }
		reenrollRequired() shouldBe false
	}

	@Test
	fun `an account whose setup was never completed cannot start or confirm a re-enrollment, even with the flag set`() {
		jdbc.update("update app_user set totp_confirmed_at = null, totp_reenroll_required = true")

		shouldThrow<ConflictException> { enrollment.start() }
		shouldThrow<ConflictException> { enrollment.confirm(code(originalSecret), null) }
		users
			.findFirstBy()
			.shouldNotBeNull()
			.totpSecret
			.reveal() shouldBe originalSecret
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
