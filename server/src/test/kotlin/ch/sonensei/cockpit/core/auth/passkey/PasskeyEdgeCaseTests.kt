package ch.sonensei.cockpit.core.auth.passkey

import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.LoginThrottle
import ch.sonensei.cockpit.core.auth.domain.SetupService
import ch.sonensei.cockpit.core.auth.domain.Totp
import ch.sonensei.cockpit.core.auth.infra.SessionCookies
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper

/**
 * Edge cases of passkeys (task passkeys-server) that [PasskeyWebTests] does not cover: synced passkeys without a
 * counter, ceremony mix-ups, relying-party binding, user verification at registration, the shared throttle being
 * reset by a passkey login, the browser-session cookie, management authorization, inactive accounts and logging.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class PasskeyEdgeCaseTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val clock: MutableClock,
	@Autowired private val setup: SetupService,
	@Autowired private val throttle: LoginThrottle,
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

	@Test
	fun `a synced passkey that always reports a sign count of zero can log in again and again`() {
		val authenticator = register()
		repeat(3) {
			// Synced passkeys (iCloud Keychain, Google Password Manager) never increase the counter.
			authenticator.signCount = -1
			authenticationOptions().let { (ceremony, publicKey) ->
				passkeyLogin(ceremony, authenticator.authenticate(publicKey)).andExpect { status { isOk() } }
			}
		}
		jdbc.queryForObject("select sign_count from auth_passkey", Long::class.java) shouldBe 0L
	}

	@Test
	fun `a login without remember device gets a browser-session cookie and a short session`() {
		val authenticator = register()
		val (ceremony, publicKey) = authenticationOptions()
		val response =
			// rememberDevice left out: it defaults to false.
			passkeyLogin(ceremony, authenticator.authenticate(publicKey), rememberDevice = null)
				.andExpect { status { isOk() } }
				.andReturn()
				.response
		val cookie = response.getCookie(SessionCookies.NAME)!!
		cookie.maxAge shouldBe -1
		cookie.isHttpOnly shouldBe true
		jdbc.queryForObject("select count(*) from auth_session where remember_device = false", Int::class.java) shouldBe 2
	}

	@Test
	fun `the login records the authenticator's latest backup state and user verification`() {
		val authenticator = register()
		jdbc.queryForObject("select backup_state from auth_passkey", Boolean::class.java) shouldBe true
		val notBackedUp = (0x01 or 0x04 or 0x08).toByte()
		authenticationOptions().let { (ceremony, publicKey) ->
			passkeyLogin(ceremony, authenticator.authenticate(publicKey, flags = notBackedUp)).andExpect { status { isOk() } }
		}
		val row = jdbc.queryForMap("select backup_eligible, backup_state, uv_initialized, sign_count from auth_passkey")
		row["backup_eligible"] shouldBe true
		row["backup_state"] shouldBe false
		row["uv_initialized"] shouldBe true
		row["sign_count"] shouldBe 1L
	}

	@Test
	fun `a passkey registered without transports is stored without them and still excluded and usable`() {
		val withoutField = TestAuthenticator()
		registrationOptions().let { (ceremony, publicKey) ->
			finishRegistration(ceremony, withoutField.register(publicKey, transports = null)).andExpect { status { isCreated() } }
		}
		clock.advance(java.time.Duration.ofSeconds(1))
		val emptyTransports = TestAuthenticator()
		registrationOptions().let { (ceremony, publicKey) ->
			finishRegistration(ceremony, emptyTransports.register(publicKey, transports = emptyList()), name = "Key")
				.andExpect { status { isCreated() } }
		}
		jdbc.queryForList("select transports from auth_passkey", String::class.java) shouldBe listOf(null, null)

		val excluded = (registrationOptions().second["excludeCredentials"] as List<*>).map { it as Map<*, *> }
		excluded shouldBe
			listOf(
				mapOf("type" to "public-key", "id" to TestAuthenticator.encode(withoutField.credentialId)),
				mapOf("type" to "public-key", "id" to TestAuthenticator.encode(emptyTransports.credentialId)),
			)
		val (ceremony, publicKey) = authenticationOptions()
		passkeyLogin(ceremony, withoutField.authenticate(publicKey)).andExpect { status { isOk() } }
	}

	@Test
	fun `an assertion without a user handle is accepted when it verifies`() {
		val authenticator = register()
		authenticator.userHandle = null
		val (ceremony, publicKey) = authenticationOptions()
		passkeyLogin(ceremony, authenticator.authenticate(publicKey)).andExpect { status { isOk() } }
	}

	@Test
	fun `a failed passkey token login is a 401 problem and registers no device`() {
		val authenticator = register()
		val devicesBefore = deviceCount()
		authenticationOptions(csrf = false).let { (ceremony, publicKey) ->
			passkeyToken(ceremony, authenticator.authenticate(publicKey, tamper = true)).isProblem(401)
		}
		passkeyToken("no-such-ceremony", mapOf("id" to "x")).isProblem(401)
		deviceCount() shouldBe devicesBefore
	}

	@Test
	fun `a malformed assertion for a valid ceremony is rejected like wrong credentials`() {
		authenticationOptions().let { (ceremony, _) ->
			passkeyLogin(ceremony, mapOf("id" to "x", "type" to "public-key", "response" to mapOf("clientDataJSON" to "!!"))).isProblem(401)
		}
		authenticationOptions(csrf = false).let { (ceremony, _) ->
			passkeyToken(ceremony, mapOf("id" to 42, "rawId" to listOf(1, 2))).isProblem(401)
		}
	}

	@Test
	fun `registration and authentication ceremonies cannot be swapped`() {
		val authenticator = TestAuthenticator()
		val (loginCeremony, loginKey) = authenticationOptions()
		// An authentication ceremony carries no user, so it cannot finish a registration.
		val forgedCreation = loginKey + mapOf("user" to mapOf("id" to TestAuthenticator.encode(ByteArray(16))))
		finishRegistration(loginCeremony, authenticator.register(forgedCreation)).isProblem(400)

		val registered = register()
		val (registrationCeremony, registrationKey) = registrationOptions()
		passkeyLogin(registrationCeremony, registered.authenticate(registrationKey)).isProblem(401)
		jdbc.queryForObject("select count(*) from auth_passkey", Int::class.java) shouldBe 1
	}

	@Test
	fun `a passkey is bound to the relying party ID it was registered for`() {
		val other = TestAuthenticator(rpId = "evil.example")
		val (ceremony, publicKey) = registrationOptions()
		finishRegistration(ceremony, other.register(publicKey)).isProblem(400)

		val authenticator = register()
		authenticator.rpId = "evil.example"
		val (loginCeremony, loginKey) = authenticationOptions()
		passkeyLogin(loginCeremony, authenticator.authenticate(loginKey)).isProblem(401)
	}

	@Test
	fun `registration requires user verification`() {
		val (ceremony, publicKey) = registrationOptions()
		val userPresentOnly = 0x01.toByte()
		finishRegistration(ceremony, TestAuthenticator().register(publicKey, flags = userPresentOnly)).isProblem(400)
		jdbc.queryForObject("select count(*) from auth_passkey", Int::class.java) shouldBe 0
	}

	@Test
	fun `a passkey name of 100 characters is accepted, 101 is a 400`() {
		val (ceremony, publicKey) = registrationOptions()
		finishRegistration(ceremony, TestAuthenticator().register(publicKey), name = "n".repeat(101)).isProblem(400)
		val (second, secondKey) = registrationOptions()
		finishRegistration(second, TestAuthenticator().register(secondKey), name = "n".repeat(100)).andExpect { status { isCreated() } }
	}

	@Test
	fun `a successful passkey login resets the throttle shared with the password login`() {
		val authenticator = register()
		repeat(LoginThrottle.FREE_ATTEMPTS - 1) { wrongPasswordLogin().isProblem(401) }
		authenticationOptions().let { (ceremony, publicKey) ->
			passkeyLogin(ceremony, authenticator.authenticate(publicKey)).andExpect { status { isOk() } }
		}
		repeat(LoginThrottle.FREE_ATTEMPTS - 1) { wrongPasswordLogin().isProblem(401) }
		authenticationOptions(csrf = false).let { (ceremony, publicKey) ->
			passkeyToken(ceremony, authenticator.authenticate(publicKey)).andExpect { status { isOk() } }
		}
		repeat(LoginThrottle.FREE_ATTEMPTS - 1) { wrongPasswordLogin().isProblem(401) }
		throttle.retryAfter() shouldBe null
	}

	@Test
	fun `a correct TOTP code for a registration resets the throttle, wrong ones use it up`() {
		repeat(LoginThrottle.FREE_ATTEMPTS - 1) { wrongPasswordLogin().isProblem(401) }
		registrationOptions()
		throttle.retryAfter() shouldBe null
		repeat(LoginThrottle.FREE_ATTEMPTS - 1) { startRegistration(totp = wrongCode()).isProblem(403) }
		wrongPasswordLogin().isProblem(401)
		wrongPasswordLogin().isProblem(429)
	}

	@Test
	fun `managing passkeys needs a login, the CSRF header for a session, and a UUID`() {
		register()
		val id = jdbc.queryForObject("select id from auth_passkey", java.util.UUID::class.java)
		mockMvc.delete("/api/v1/auth/passkeys/$id") { with(csrf()) }.andExpect { status { isUnauthorized() } }
		mockMvc
			.delete("/api/v1/auth/passkeys/$id") { cookie(Cookie(SessionCookies.NAME, sessionToken)) }
			.andExpect { status { isForbidden() } }
		mockMvc
			.delete("/api/v1/auth/passkeys/not-a-uuid") {
				cookie(Cookie(SessionCookies.NAME, sessionToken))
				with(csrf())
			}.andExpect { status { isBadRequest() } }
		mockMvc
			.post("/api/v1/auth/passkeys/registration") {
				json(mapOf("ceremonyId" to "c", "name" to "Laptop", "credential" to mapOf("id" to "x")))
				with(csrf())
			}.andExpect { status { isUnauthorized() } }
		jdbc.queryForObject("select count(*) from auth_passkey", Int::class.java) shouldBe 1
	}

	@Test
	fun `a passkey of an account whose setup is not complete cannot log in`() {
		val authenticator = register()
		jdbc.update("update app_user set totp_confirmed_at = null")
		val (ceremony, publicKey) = authenticationOptions()
		passkeyLogin(ceremony, authenticator.authenticate(publicKey)).isProblem(401)
	}

	@Test
	fun `deleting the account deletes its passkeys`() {
		register()
		jdbc.update("delete from app_user")
		jdbc.queryForObject("select count(*) from auth_passkey", Int::class.java) shouldBe 0
	}

	@Test
	fun `passkey registration and login never log tokens or the TOTP code`(output: CapturedOutput) {
		val code = currentCode()
		val (ceremony, publicKey) = registrationOptions(code)
		val authenticator = TestAuthenticator()
		finishRegistration(ceremony, authenticator.register(publicKey)).andExpect { status { isCreated() } }
		val (loginCeremony, loginKey) = authenticationOptions(csrf = false)
		val assertion = authenticator.authenticate(loginKey)
		val tokens =
			json(
				passkeyToken(loginCeremony, assertion)
					.andExpect { status { isOk() } }
					.andReturn()
					.response.contentAsString,
			)
		val (webCeremony, webKey) = authenticationOptions()
		val session =
			passkeyLogin(webCeremony, authenticator.authenticate(webKey))
				.andReturn()
				.response
				.getCookie(SessionCookies.NAME)!!
				.value

		val logged = output.all
		logged shouldNotContain code.let { "\"$it\"" }
		logged shouldNotContain tokens["accessToken"] as String
		logged shouldNotContain tokens["refreshToken"] as String
		logged shouldNotContain session
		logged shouldNotContain ((assertion["response"] as Map<*, *>)["signature"] as String)
	}

	// --- helpers ---

	private fun register(): TestAuthenticator {
		val authenticator = TestAuthenticator()
		val (ceremony, publicKey) = registrationOptions()
		finishRegistration(ceremony, authenticator.register(publicKey)).andExpect { status { isCreated() } }
		return authenticator
	}

	private fun registrationOptions(code: String = currentCode()): Pair<String, Map<*, *>> =
		json(
			startRegistration(code)
				.andExpect { status { isOk() } }
				.andReturn()
				.response.contentAsString,
		).let { it["ceremonyId"] as String to it["publicKey"] as Map<*, *> }

	private fun authenticationOptions(csrf: Boolean = true): Pair<String, Map<*, *>> =
		json(
			mockMvc
				.post("/api/v1/auth/passkeys/authentication/options") { if (csrf) with(csrf()) }
				.andExpect { status { isOk() } }
				.andReturn()
				.response.contentAsString,
		).let { it["ceremonyId"] as String to it["publicKey"] as Map<*, *> }

	private fun startRegistration(totp: String = currentCode()) =
		mockMvc.post("/api/v1/auth/passkeys/registration/options") {
			cookie(Cookie(SessionCookies.NAME, sessionToken))
			json(mapOf("totp" to totp))
			with(csrf())
		}

	private fun finishRegistration(
		ceremonyId: String,
		credential: Map<String, Any?>,
		name: String = "Laptop",
	) = mockMvc.post("/api/v1/auth/passkeys/registration") {
		cookie(Cookie(SessionCookies.NAME, sessionToken))
		json(mapOf("ceremonyId" to ceremonyId, "name" to name, "credential" to credential))
		with(csrf())
	}

	private fun passkeyLogin(
		ceremonyId: String,
		credential: Map<String, Any?>,
		rememberDevice: Boolean? = false,
	) = mockMvc.post("/api/v1/auth/passkeys/login") {
		val body = mapOf("ceremonyId" to ceremonyId, "credential" to credential)
		json(if (rememberDevice == null) body else body + ("rememberDevice" to rememberDevice))
		with(csrf())
	}

	private fun passkeyToken(
		ceremonyId: String,
		credential: Map<String, Any?>,
	) = mockMvc.post("/api/v1/auth/passkeys/token") {
		json(mapOf("ceremonyId" to ceremonyId, "credential" to credential, "deviceName" to "Pixel 9"))
	}

	private fun wrongPasswordLogin() =
		mockMvc.post("/api/v1/auth/login") {
			json(mapOf("username" to USERNAME, "password" to "wrong-fictitious-password", "totp" to currentCode()))
			with(csrf())
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

	private fun deviceCount() = jdbc.queryForObject("select count(*) from auth_device", Int::class.java)

	private fun currentCode() = totp.generate(secret, clock.instant())

	private fun wrongCode() = if (currentCode() == "000000") "111111" else "000000"

	private fun MockHttpServletRequestDsl.json(body: Any) {
		contentType = MediaType.APPLICATION_JSON
		content = jsonMapper.writeValueAsString(body)
	}

	private fun json(body: String): Map<*, *> = jsonMapper.readValue(body, Map::class.java)

	private fun ResultActionsDsl.isProblem(status: Int) =
		andExpect {
			status { isEqualTo(status) }
			if (status >= 400) content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		}

	private companion object {
		const val USERNAME = "jane.doe"
		const val PASSWORD = "correct-horse-battery-staple"
	}
}
