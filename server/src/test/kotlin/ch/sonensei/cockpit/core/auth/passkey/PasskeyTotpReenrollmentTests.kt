package ch.sonensei.cockpit.core.auth.passkey

import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.CockpitPrincipal
import ch.sonensei.cockpit.core.auth.domain.DeviceTokenResult
import ch.sonensei.cockpit.core.auth.domain.DeviceTokenService
import ch.sonensei.cockpit.core.auth.domain.LoginResult
import ch.sonensei.cockpit.core.auth.domain.LoginThrottle
import ch.sonensei.cockpit.core.auth.domain.SetupService
import ch.sonensei.cockpit.core.auth.domain.Totp
import ch.sonensei.cockpit.core.auth.domain.TotpEnrollmentResult
import ch.sonensei.cockpit.core.auth.domain.TotpEnrollmentService
import ch.sonensei.cockpit.core.auth.infra.SessionCookies
import ch.sonensei.cockpit.core.auth.infra.UserAccountRepository
import ch.sonensei.cockpit.core.auth.passkey.domain.PasskeyService
import ch.sonensei.cockpit.core.auth.passkey.domain.RegistrationStartResult
import ch.sonensei.cockpit.core.auth.passkey.domain.RelyingParty
import ch.sonensei.cockpit.core.crypto.SecretCipher
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import jakarta.servlet.http.Cookie
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
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Passkeys (task passkeys-server) for an account restored from a backup without its TOTP secret
 * (`totp_reenroll_required`, SPEC.md §10.2): registration is refused, a passkey web login gets a session that may only
 * enroll TOTP again, the passkey device token is refused with 403, and the passkey login takes the account row lock, so
 * it cannot interleave with a re-enrollment confirm. The restore is simulated as in `TotpEnrollmentWebTests`.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
class PasskeyTotpReenrollmentTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val clock: MutableClock,
	@Autowired private val setup: SetupService,
	@Autowired private val throttle: LoginThrottle,
	@Autowired private val cipher: SecretCipher,
	@Autowired private val users: UserAccountRepository,
	@Autowired private val passkeys: PasskeyService,
	@Autowired private val enrollment: TotpEnrollmentService,
	@Autowired private val deviceTokens: DeviceTokenService,
	@Autowired private val transactions: TransactionTemplate,
) : PostgresTestBase() {
	private val totp = Totp()
	private lateinit var secret: ByteArray
	private lateinit var sessionToken: String

	@BeforeEach
	fun activeAccount() {
		clock.set(MutableClockTestConfiguration.START)
		throttle.recordSuccess()
		jdbc.update("delete from app_user")
		val enrollment = setup.start(USERNAME, PASSWORD)
		secret = Base32.decode(enrollment.totpSecret)
		setup.confirmTotp(currentCode())
		sessionToken = webLogin()
	}

	@AfterEach
	fun noAccountLeft() {
		// Other test classes (e.g. the events tests) insert their own account and expect none to be left over.
		throttle.recordSuccess()
		jdbc.update("delete from app_user")
	}

	/** What a restore without the TOTP secret leaves: a secret nobody knows (or [newSecret]) and the flag. */
	private fun restoredWithoutTotpSecret(newSecret: String = Base32.encode(Totp.newSecret())) {
		jdbc.update("update app_user set totp_secret = ?, totp_reenroll_required = true", cipher.encrypt(newSecret))
	}

	@Test
	fun `a passkey web login of a normal account reports no TOTP enrollment and has full access`() {
		val authenticator = register()
		val session = passkeySession(authenticator, expectEnrollment = false)
		mockMvc.get("/api/v1/auth/me") { cookie(session) }.andExpect { jsonPath("$.totpEnrollmentRequired") { value(false) } }
		mockMvc.get("/api/v1/auth/passkeys") { cookie(session) }.andExpect { status { isOk() } }
		mockMvc.get("/api/v1/settings") { cookie(session) }.andExpect { status { isOk() } }
	}

	@Test
	fun `a passkey web login of a re-enrolling account gets a session that may only enroll TOTP again`() {
		val authenticator = register()
		restoredWithoutTotpSecret()

		val session = passkeySession(authenticator, expectEnrollment = true)
		mockMvc.get("/api/v1/auth/me") { cookie(session) }.andExpect {
			status { isOk() }
			jsonPath("$.username") { value(USERNAME) }
			jsonPath("$.totpEnrollmentRequired") { value(true) }
		}
		listOf("/api/v1/auth/passkeys", "/api/v1/settings", "/api/v1/auth/devices").forEach { path ->
			withClue("GET $path") { mockMvc.get(path) { cookie(session) }.andExpect { status { isForbidden() } } }
		}
		withClue("it cannot add a passkey either") {
			mockMvc
				.post("/api/v1/auth/passkeys/registration/options") {
					cookie(session)
					json(mapOf("totp" to currentCode()))
					with(csrf())
				}.andExpect { status { isForbidden() } }
		}
		withClue("the login reset the shared throttle like a successful login") { throttle.retryAfter() shouldBe null }

		val newSecret = enrollmentStart(session)
		mockMvc
			.post("$ENROLLMENT/confirm") {
				cookie(session)
				json(mapOf("code" to totp.generate(Base32.decode(newSecret), clock.instant())))
				with(csrf())
			}.andExpect { status { isNoContent() } }
		withClue("after the re-enrollment the passkey session has full access") {
			mockMvc.get("/api/v1/auth/passkeys") { cookie(session) }.andExpect { status { isOk() } }
		}
		withClue("and the next passkey login is a normal one") { passkeySession(authenticator, expectEnrollment = false) }
	}

	@Test
	fun `a session of a re-enrolling account is refused passkey registration before its TOTP code is checked`() {
		restoredWithoutTotpSecret()
		// More requests than the throttle allows: none reaches the code check, so none is answered with 429.
		repeat(LoginThrottle.FREE_ATTEMPTS + 1) {
			mockMvc
				.post("/api/v1/auth/passkeys/registration/options") {
					cookie(Cookie(SessionCookies.NAME, sessionToken))
					json(mapOf("totp" to currentCode()))
					with(csrf())
				}.andExpect { status { isForbidden() } }
		}
		throttle.retryAfter() shouldBe null
	}

	@Test
	fun `the service does not accept a code of the unconfirmed TOTP secret of a re-enrollment`() {
		val pending = Base32.encode(Totp.newSecret())
		restoredWithoutTotpSecret(pending)
		val account = users.findFirstBy().shouldNotBeNull()
		// A principal that still claims full access, e.g. one authenticated just before the restore committed.
		val principal = CockpitPrincipal(account.id, account.username, false)
		val pendingCode = totp.generate(Base32.decode(pending), clock.instant())

		passkeys.startRegistration(principal, pendingCode, RELYING_PARTY) shouldBe RegistrationStartResult.InvalidTotp
		withClue("the original secret no longer counts either") {
			passkeys.startRegistration(principal, currentCode(), RELYING_PARTY) shouldBe RegistrationStartResult.InvalidTotp
		}

		throttle.recordSuccess()
		jdbc.update("update app_user set totp_reenroll_required = false")
		withClue("once the secret is confirmed, its code starts a registration") {
			passkeys.startRegistration(principal, pendingCode, RELYING_PARTY).shouldBeInstanceOf<RegistrationStartResult.Started>()
		}
	}

	@Test
	fun `a passkey device token is refused with 403 while re-enrolling, wrong passkeys stay 401, and no device is created`() {
		val authenticator = register()
		restoredWithoutTotpSecret()

		authenticationOptions().let { (ceremony, publicKey) ->
			passkeyToken(ceremony, authenticator.authenticate(publicKey)).isProblem(403).andExpect {
				jsonPath("$.detail") { value("TOTP must be set up again in the web app first") }
			}
		}
		throttle.recordSuccess()
		withClue("an assertion that does not verify does not reveal that the account re-enrolls") {
			authenticationOptions().let { (ceremony, publicKey) ->
				passkeyToken(ceremony, authenticator.authenticate(publicKey, tamper = true)).isProblem(401)
			}
		}
		jdbc.queryForObject("select count(*) from auth_device", Int::class.java) shouldBe 0
	}

	@Test
	fun `after the re-enrollment the passkey device token works and has full access`() {
		val authenticator = register()
		restoredWithoutTotpSecret()
		val session = passkeySession(authenticator, expectEnrollment = true)
		val newSecret = enrollmentStart(session)
		enrollment.confirm(totp.generate(Base32.decode(newSecret), clock.instant()), session.value) shouldBe TotpEnrollmentResult.Confirmed

		val (ceremony, publicKey) = authenticationOptions()
		val response = passkeyToken(ceremony, authenticator.authenticate(publicKey)).andExpect { status { isOk() } }
		val tokens = json(response.andReturn().response.contentAsString)
		deviceTokens.authenticate(tokens["accessToken"] as String).shouldNotBeNull().totpEnrollmentRequired shouldBe false
	}

	@Test
	fun `a passkey token request that runs while a confirm is not committed yet waits, then gets a device with full access`() {
		val authenticator = register()
		restoredWithoutTotpSecret()
		val session = passkeySession(authenticator, expectEnrollment = true)
		val newSecret = enrollmentStart(session)
		val (ceremony, publicKey) = authenticationOptions()
		val assertion = jsonMapper.writeValueAsString(authenticator.authenticate(publicKey))
		val release = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		try {
			val confirmed =
				heldTransaction(executor, release) {
					enrollment.confirm(totp.generate(Base32.decode(newSecret), clock.instant()), session.value)
				}
			val issued = executor.submit<DeviceTokenResult> { passkeys.issueToken(ceremony, assertion, "Pixel 9") }
			withClue("the passkey device login waits for the confirm's transaction (account row lock)") {
				shouldThrow<TimeoutException> { issued.get(500, TimeUnit.MILLISECONDS) }
			}
			release.countDown()
			confirmed.get(10, TimeUnit.SECONDS) shouldBe TotpEnrollmentResult.Confirmed

			withClue("the device login sees the completed re-enrollment instead of refusing it") {
				val tokens = issued.get(10, TimeUnit.SECONDS).shouldBeInstanceOf<DeviceTokenResult.Issued>().tokens
				deviceTokens.authenticate(tokens.accessToken).shouldNotBeNull().totpEnrollmentRequired shouldBe false
			}
		} finally {
			release.countDown()
			executor.shutdownNow()
		}
	}

	@Test
	fun `a passkey web login that runs while a confirm is not committed yet waits, then gets a session with full access`() {
		val authenticator = register()
		restoredWithoutTotpSecret()
		val confirming = passkeySession(authenticator, expectEnrollment = true)
		val newSecret = enrollmentStart(confirming)
		val (ceremony, publicKey) = authenticationOptions()
		val assertion = jsonMapper.writeValueAsString(authenticator.authenticate(publicKey))
		val release = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		try {
			val confirmed =
				heldTransaction(executor, release) {
					enrollment.confirm(totp.generate(Base32.decode(newSecret), clock.instant()), confirming.value)
				}
			val login = executor.submit<LoginResult> { passkeys.login(ceremony, assertion, rememberDevice = false) }
			withClue("the passkey web login waits for the confirm's transaction (account row lock)") {
				shouldThrow<TimeoutException> { login.get(500, TimeUnit.MILLISECONDS) }
			}
			release.countDown()
			confirmed.get(10, TimeUnit.SECONDS) shouldBe TotpEnrollmentResult.Confirmed

			withClue("the web login sees the completed re-enrollment and gets full access") {
				val grant = login.get(10, TimeUnit.SECONDS).shouldBeInstanceOf<LoginResult.Success>().grant
				grant.principal.totpEnrollmentRequired shouldBe false
				mockMvc.get("/api/v1/auth/passkeys") { cookie(Cookie(SessionCookies.NAME, grant.token)) }.andExpect { status { isOk() } }
			}
		} finally {
			release.countDown()
			executor.shutdownNow()
		}
	}

	@Test
	fun `a passkey login whose transaction runs while the confirm runs is ended by the confirm and never gets full access`() {
		val authenticator = register()
		restoredWithoutTotpSecret()
		val confirming = passkeySession(authenticator, expectEnrollment = true)
		val newSecret = enrollmentStart(confirming)
		val (ceremony, publicKey) = authenticationOptions()
		val assertion = jsonMapper.writeValueAsString(authenticator.authenticate(publicKey))
		val release = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		try {
			// The passkey login has read the flag and written its session, but not committed yet.
			val login = heldTransaction(executor, release) { passkeys.login(ceremony, assertion, rememberDevice = false) }
			val confirmed =
				executor.submit<TotpEnrollmentResult> {
					enrollment.confirm(totp.generate(Base32.decode(newSecret), clock.instant()), confirming.value)
				}
			withClue("the confirm waits for the passkey login's transaction (account row lock)") {
				shouldThrow<TimeoutException> { confirmed.get(500, TimeUnit.MILLISECONDS) }
			}
			release.countDown()
			val grant = login.get(10, TimeUnit.SECONDS).shouldBeInstanceOf<LoginResult.Success>().grant
			grant.principal.totpEnrollmentRequired shouldBe true
			confirmed.get(10, TimeUnit.SECONDS) shouldBe TotpEnrollmentResult.Confirmed

			mockMvc.get("/api/v1/auth/me") { cookie(Cookie(SessionCookies.NAME, grant.token)) }.andExpect { status { isUnauthorized() } }
			mockMvc.get("/api/v1/auth/passkeys") { cookie(confirming) }.andExpect { status { isOk() } }
		} finally {
			release.countDown()
			executor.shutdownNow()
		}
	}

	// --- helpers ---

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

	private fun register(): TestAuthenticator {
		val authenticator = TestAuthenticator()
		val options =
			json(
				mockMvc
					.post("/api/v1/auth/passkeys/registration/options") {
						cookie(Cookie(SessionCookies.NAME, sessionToken))
						json(mapOf("totp" to currentCode()))
						with(csrf())
					}.andExpect { status { isOk() } }
					.andReturn()
					.response.contentAsString,
			)
		mockMvc
			.post("/api/v1/auth/passkeys/registration") {
				cookie(Cookie(SessionCookies.NAME, sessionToken))
				json(
					mapOf(
						"ceremonyId" to options["ceremonyId"],
						"name" to "Laptop",
						"credential" to authenticator.register(options["publicKey"] as Map<*, *>),
					),
				)
				with(csrf())
			}.andExpect { status { isCreated() } }
		return authenticator
	}

	/** Logs the browser in with [authenticator]'s passkey and checks the reported enrollment state. */
	private fun passkeySession(
		authenticator: TestAuthenticator,
		expectEnrollment: Boolean,
	): Cookie {
		val (ceremony, publicKey) = authenticationOptions()
		val response =
			mockMvc
				.post("/api/v1/auth/passkeys/login") {
					json(mapOf("ceremonyId" to ceremony, "credential" to authenticator.authenticate(publicKey)))
					with(csrf())
				}.andExpect {
					status { isOk() }
					jsonPath("$.username") { value(USERNAME) }
					jsonPath("$.totpEnrollmentRequired") { value(expectEnrollment) }
				}.andReturn()
				.response
		return Cookie(SessionCookies.NAME, response.getCookie(SessionCookies.NAME).shouldNotBeNull().value)
	}

	private fun enrollmentStart(session: Cookie): String =
		json(
			mockMvc
				.post(ENROLLMENT) {
					cookie(session)
					with(csrf())
				}.andExpect { status { isOk() } }
				.andReturn()
				.response.contentAsString,
		)["totpSecret"] as String

	private fun authenticationOptions(): Pair<String, Map<*, *>> =
		json(
			mockMvc
				.post("/api/v1/auth/passkeys/authentication/options")
				.andExpect { status { isOk() } }
				.andReturn()
				.response.contentAsString,
		).let { it["ceremonyId"] as String to it["publicKey"] as Map<*, *> }

	private fun passkeyToken(
		ceremonyId: String,
		credential: Map<String, Any?>,
	) = mockMvc.post("/api/v1/auth/passkeys/token") {
		json(mapOf("ceremonyId" to ceremonyId, "credential" to credential, "deviceName" to "Pixel 9"))
	}

	private fun webLogin(): String =
		mockMvc
			.post("/api/v1/auth/login") {
				json(mapOf("username" to USERNAME, "password" to PASSWORD, "totp" to currentCode()))
				with(csrf())
			}.andExpect { status { isOk() } }
			.andReturn()
			.response
			.getCookie(SessionCookies.NAME)!!
			.value

	private fun currentCode() = totp.generate(secret, clock.instant())

	private fun MockHttpServletRequestDsl.json(body: Any) {
		contentType = MediaType.APPLICATION_JSON
		content = jsonMapper.writeValueAsString(body)
	}

	private fun json(body: String): Map<*, *> = jsonMapper.readValue(body, Map::class.java)

	private fun ResultActionsDsl.isProblem(status: Int) =
		andExpect {
			status { isEqualTo(status) }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		}

	private companion object {
		const val USERNAME = "jane.doe"
		const val PASSWORD = "correct-horse-battery-staple"
		const val ENROLLMENT = "/api/v1/auth/totp-enrollment"
		val RELYING_PARTY = RelyingParty("localhost", "My Cockpit", setOf("http://localhost"))
	}
}
