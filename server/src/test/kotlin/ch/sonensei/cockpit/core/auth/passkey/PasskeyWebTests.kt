package ch.sonensei.cockpit.core.auth.passkey

import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.LoginThrottle
import ch.sonensei.cockpit.core.auth.domain.SetupService
import ch.sonensei.cockpit.core.auth.domain.Totp
import ch.sonensei.cockpit.core.auth.infra.SessionCookies
import ch.sonensei.cockpit.core.auth.passkey.domain.PasskeyCeremonies
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
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
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

/** Passkeys (task passkeys-server) against a real account, Postgres and a software authenticator. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
class PasskeyWebTests(
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
	fun `a logged-in owner registers a passkey after confirming with TOTP`() {
		val options = startRegistration().andExpect { status { isOk() } }.andReturn().response
		options.getHeader(HttpHeaders.CACHE_CONTROL) shouldBe "no-store"
		val body = json(options.contentAsString)
		val publicKey = body["publicKey"] as Map<*, *>
		publicKey["rp"] shouldBe mapOf("id" to "localhost", "name" to "My Cockpit")
		(publicKey["user"] as Map<*, *>)["name"] shouldBe USERNAME
		publicKey["attestation"] shouldBe "none"
		publicKey["timeout"] shouldBe PasskeyCeremonies.LIFETIME.toMillis().toInt()
		publicKey["authenticatorSelection"] shouldBe
			mapOf("residentKey" to "required", "requireResidentKey" to true, "userVerification" to "required")
		(publicKey["pubKeyCredParams"] as List<*>).map { (it as Map<*, *>)["alg"] } shouldContainExactly listOf(-7, -8, -257)
		(publicKey["excludeCredentials"] as List<*>).shouldBeEmpty()

		val authenticator = TestAuthenticator()
		val created =
			finishRegistration(body["ceremonyId"] as String, authenticator.register(publicKey), name = "  Laptop ")
				.andExpect { status { isCreated() } }
				.andReturn()
				.response
		val passkey = json(created.contentAsString)
		passkey["name"] shouldBe "Laptop"
		Instant.parse(passkey["createdAt"] as String) shouldBe clock.instant()
		passkey["lastUsedAt"] shouldBe null

		val row = jdbc.queryForMap("select credential_id, sign_count, backup_eligible, backup_state, transports from auth_passkey")
		row["credential_id"] shouldBe authenticator.credentialId
		row["sign_count"] shouldBe 0L
		row["backup_eligible"] shouldBe true
		row["backup_state"] shouldBe true
		row["transports"] shouldBe "internal,hybrid"

		list().andExpect {
			jsonPath("$.length()") { value(1) }
			jsonPath("$[0].id") { value(passkey["id"]) }
		}
		// A second registration excludes the existing credential.
		val excluded = (json(startRegistration().andReturn().response.contentAsString)["publicKey"] as Map<*, *>)["excludeCredentials"] as List<*>
		excluded shouldBe
			listOf(
				mapOf(
					"type" to "public-key",
					"id" to TestAuthenticator.encode(authenticator.credentialId),
					"transports" to listOf("hybrid", "internal"),
				),
			)
	}

	@Test
	fun `registration needs a login, the CSRF header and a correct TOTP code`() {
		mockMvc
			.post("/api/v1/auth/passkeys/registration/options") {
				json(mapOf("totp" to currentCode()))
				with(csrf())
			}.andExpect { status { isUnauthorized() } }
		mockMvc
			.post("/api/v1/auth/passkeys/registration/options") {
				cookie(Cookie(SessionCookies.NAME, sessionToken))
				json(mapOf("totp" to currentCode()))
			}.andExpect { status { isForbidden() } }
		startRegistration(totp = wrongCode()).isProblem(403)
		startRegistration(totp = " ").isProblem(400)
		mockMvc.get("/api/v1/auth/passkeys").andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `wrong TOTP codes for a registration count against the login throttle`() {
		repeat(LoginThrottle.FREE_ATTEMPTS) { startRegistration(totp = wrongCode()).isProblem(403) }
		startRegistration().isProblem(429).andExpect { header { string(HttpHeaders.RETRY_AFTER, "30") } }
	}

	@Test
	fun `a registration ceremony works once, only before it expires, and only with a valid response`() {
		val authenticator = TestAuthenticator()
		val (ceremony, publicKey) = registrationOptions()
		finishRegistration(ceremony, authenticator.register(publicKey, challenge = TestAuthenticator.encode(ByteArray(32))))
			.isProblem(400)
		// The failed attempt used up the ceremony.
		finishRegistration(ceremony, authenticator.register(publicKey)).isProblem(400)

		val (second, secondKey) = registrationOptions()
		finishRegistration(second, authenticator.register(secondKey, type = "webauthn.get")).isProblem(400)

		val (third, thirdKey) = registrationOptions()
		authenticator.origin = "https://evil.example"
		finishRegistration(third, authenticator.register(thirdKey)).isProblem(400)
		authenticator.origin = "http://localhost"

		val (fourth, fourthKey) = registrationOptions()
		finishRegistration(fourth, mapOf("id" to "x", "type" to "public-key", "response" to mapOf("clientDataJSON" to "!!"))).isProblem(400)

		val (expired, expiredKey) = registrationOptions()
		clock.advance(PasskeyCeremonies.LIFETIME)
		finishRegistration(expired, authenticator.register(expiredKey)).isProblem(400)
		finishRegistration("no-such-ceremony", authenticator.register(fourthKey)).isProblem(400)
		jdbc.queryForObject("select count(*) from auth_passkey", Int::class.java) shouldBe 0
	}

	@Test
	fun `the same authenticator cannot be registered twice`() {
		val authenticator = register()
		val (ceremony, publicKey) = registrationOptions()
		finishRegistration(ceremony, authenticator.register(publicKey)).isProblem(409)
	}

	@Test
	fun `an Android device registers a passkey with its bearer token`() {
		val access = passwordToken()
		val options =
			mockMvc
				.post("/api/v1/auth/passkeys/registration/options") {
					bearer(access)
					json(mapOf("totp" to currentCode()))
				}.andExpect { status { isOk() } }
				.andReturn()
		val body = json(options.response.contentAsString)
		val authenticator = TestAuthenticator(origin = ANDROID_ORIGIN)
		// The default relying party accepts only the request's own origin; an app origin needs configuration.
		mockMvc
			.post("/api/v1/auth/passkeys/registration") {
				bearer(access)
				json(
					mapOf(
						"ceremonyId" to body["ceremonyId"],
						"name" to "Pixel 9",
						"credential" to authenticator.register(body["publicKey"] as Map<*, *>),
					),
				)
			}.andExpect { status { isBadRequest() } }

		authenticator.origin = "http://localhost"
		val (ceremony, publicKey) =
			json(
				mockMvc
					.post("/api/v1/auth/passkeys/registration/options") {
						bearer(access)
						json(mapOf("totp" to currentCode()))
					}.andReturn()
					.response.contentAsString,
			).let { it["ceremonyId"] as String to it["publicKey"] as Map<*, *> }
		mockMvc
			.post("/api/v1/auth/passkeys/registration") {
				bearer(access)
				json(mapOf("ceremonyId" to ceremony, "name" to "Pixel 9", "credential" to authenticator.register(publicKey)))
			}.andExpect { status { isCreated() } }
		mockMvc.get("/api/v1/auth/passkeys") { bearer(access) }.andExpect { jsonPath("$[0].name") { value("Pixel 9") } }
	}

	@Test
	fun `a passkey logs the browser in without password or TOTP`() {
		val authenticator = register()
		val (ceremony, publicKey) = authenticationOptions()
		publicKey["rpId"] shouldBe "localhost"
		publicKey["userVerification"] shouldBe "required"
		(publicKey["allowCredentials"] as List<*>).shouldBeEmpty()

		val response =
			passkeyLogin(ceremony, authenticator.authenticate(publicKey), rememberDevice = true)
				.andExpect {
					status { isOk() }
					jsonPath("$.username") { value(USERNAME) }
				}.andReturn()
				.response
		val cookie = response.getCookie(SessionCookies.NAME)!!
		cookie.maxAge shouldBe Duration.ofDays(30).seconds.toInt()
		response.getCookie("XSRF-TOKEN") shouldNotBe null
		mockMvc.get("/api/v1/auth/me") { cookie(Cookie(SessionCookies.NAME, cookie.value)) }.andExpect { status { isOk() } }

		jdbc.queryForObject("select sign_count from auth_passkey", Long::class.java) shouldBe 1L
		list().andExpect { jsonPath("$[0].lastUsedAt") { value(clock.instant().toString()) } }
	}

	@Test
	fun `the web passkey login needs the CSRF header`() {
		val authenticator = register()
		val (ceremony, publicKey) = authenticationOptions()
		mockMvc
			.post("/api/v1/auth/passkeys/login") {
				json(mapOf("ceremonyId" to ceremony, "credential" to authenticator.authenticate(publicKey)))
			}.andExpect { status { isForbidden() } }
	}

	@Test
	fun `a passkey logs an Android device in and returns its tokens`() {
		val authenticator = register()
		val (ceremony, publicKey) = authenticationOptions(csrf = false)
		val response =
			mockMvc
				.post("/api/v1/auth/passkeys/token") {
					json(mapOf("ceremonyId" to ceremony, "credential" to authenticator.authenticate(publicKey), "deviceName" to " Pixel 9 "))
				}.andExpect { status { isOk() } }
				.andReturn()
				.response
		response.getHeader(HttpHeaders.CACHE_CONTROL) shouldBe "no-store"
		val tokens = json(response.contentAsString)
		tokens["tokenType"] shouldBe "Bearer"
		mockMvc.get("/api/v1/auth/me") { bearer(tokens["accessToken"] as String) }.andExpect { status { isOk() } }
		jdbc.queryForObject("select name from auth_device", String::class.java) shouldBe "Pixel 9"
	}

	@Test
	fun `invalid assertions are rejected like wrong credentials`() {
		val authenticator = register()

		authenticationOptions().let { (ceremony, publicKey) ->
			passkeyLogin(ceremony, authenticator.authenticate(publicKey, tamper = true)).isProblem(401)
			// The ceremony is used up, even by a failed attempt.
			passkeyLogin(ceremony, authenticator.authenticate(publicKey)).isProblem(401)
		}
		authenticationOptions().let { (ceremony, publicKey) ->
			passkeyLogin(ceremony, authenticator.authenticate(publicKey, challenge = TestAuthenticator.encode(ByteArray(32)))).isProblem(401)
		}
		throttle.recordSuccess()
		authenticationOptions().let { (ceremony, publicKey) ->
			passkeyLogin(ceremony, TestAuthenticator().apply { userHandle = authenticator.userHandle }.authenticate(publicKey)).isProblem(401)
		}
		authenticationOptions().let { (ceremony, publicKey) ->
			passkeyLogin(ceremony, authenticator.authenticate(publicKey, flags = 0x01)).isProblem(401)
		}
		throttle.recordSuccess()
		authenticationOptions().let { (ceremony, publicKey) ->
			authenticator.origin = "https://evil.example"
			passkeyLogin(ceremony, authenticator.authenticate(publicKey)).isProblem(401)
			authenticator.origin = "http://localhost"
		}
		authenticationOptions().let { (ceremony, publicKey) ->
			val handle = authenticator.userHandle
			authenticator.userHandle = ByteArray(16)
			passkeyLogin(ceremony, authenticator.authenticate(publicKey)).isProblem(401)
			authenticator.userHandle = handle
		}
		throttle.recordSuccess()
		authenticationOptions().let { (ceremony, publicKey) ->
			passkeyLogin(ceremony, authenticator.authenticate(publicKey)).andExpect { status { isOk() } }
		}
		authenticationOptions().let { (ceremony, publicKey) ->
			clock.advance(PasskeyCeremonies.LIFETIME)
			authenticator.userHandle = null
			passkeyLogin(ceremony, authenticator.authenticate(publicKey)).isProblem(401)
		}
		passkeyLogin("no-such-ceremony", mapOf("id" to "x")).isProblem(401)
	}

	@Test
	fun `a sign count that does not increase is rejected`() {
		val authenticator = register()
		authenticationOptions().let { (ceremony, publicKey) ->
			passkeyLogin(ceremony, authenticator.authenticate(publicKey)).andExpect { status { isOk() } }
		}
		authenticator.signCount = 0
		authenticationOptions().let { (ceremony, publicKey) ->
			passkeyLogin(ceremony, authenticator.authenticate(publicKey)).isProblem(401)
		}
	}

	@Test
	fun `failed passkey logins share the login throttle`() {
		val authenticator = register()
		repeat(LoginThrottle.FREE_ATTEMPTS) {
			val (ceremony, publicKey) = authenticationOptions()
			passkeyLogin(ceremony, authenticator.authenticate(publicKey, tamper = true)).isProblem(401)
		}
		val (ceremony, publicKey) = authenticationOptions()
		passkeyLogin(
			ceremony,
			authenticator.authenticate(publicKey),
		).isProblem(429).andExpect { header { string(HttpHeaders.RETRY_AFTER, "30") } }
		mockMvc
			.post("/api/v1/auth/passkeys/token") {
				json(mapOf("ceremonyId" to ceremony, "credential" to authenticator.authenticate(publicKey), "deviceName" to "Pixel 9"))
			}.isProblem(429)
	}

	@Test
	fun `a deleted passkey can no longer log in`() {
		val authenticator = register()
		val id = (jsonMapper.readValue(list().andReturn().response.contentAsString, List::class.java).single() as Map<*, *>)["id"]
		mockMvc
			.delete("/api/v1/auth/passkeys/$id") {
				cookie(Cookie(SessionCookies.NAME, sessionToken))
				with(csrf())
			}.andExpect { status { isNoContent() } }
		mockMvc
			.delete("/api/v1/auth/passkeys/$id") {
				cookie(Cookie(SessionCookies.NAME, sessionToken))
				with(csrf())
			}.isProblem(404)
		list().andExpect { jsonPath("$.length()") { value(0) } }

		val (ceremony, publicKey) = authenticationOptions()
		passkeyLogin(ceremony, authenticator.authenticate(publicKey)).isProblem(401)
	}

	@Test
	fun `request bodies are validated`() {
		finishRegistration("c", mapOf("id" to "x"), name = " ").isProblem(400)
		finishRegistration("c", emptyMap()).isProblem(400)
		mockMvc
			.post("/api/v1/auth/passkeys/token") {
				json(mapOf("ceremonyId" to "c", "credential" to mapOf("id" to "x"), "deviceName" to " "))
			}.isProblem(400)
		passkeyLogin(" ", mapOf("id" to "x")).isProblem(400)
	}

	// --- helpers ---

	private fun register(): TestAuthenticator {
		val authenticator = TestAuthenticator()
		val (ceremony, publicKey) = registrationOptions()
		finishRegistration(ceremony, authenticator.register(publicKey)).andExpect { status { isCreated() } }
		return authenticator
	}

	private fun registrationOptions(): Pair<String, Map<*, *>> =
		json(
			startRegistration()
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
		rememberDevice: Boolean = false,
	) = mockMvc.post("/api/v1/auth/passkeys/login") {
		json(mapOf("ceremonyId" to ceremonyId, "credential" to credential, "rememberDevice" to rememberDevice))
		with(csrf())
	}

	private fun list() =
		mockMvc
			.get("/api/v1/auth/passkeys") { cookie(Cookie(SessionCookies.NAME, sessionToken)) }
			.andExpect { status { isOk() } }

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

	private fun passwordToken(): String =
		json(
			mockMvc
				.post("/api/v1/auth/token") {
					json(mapOf("username" to USERNAME, "password" to PASSWORD, "totp" to currentCode(), "deviceName" to "Pixel 9"))
				}.andExpect { status { isOk() } }
				.andReturn()
				.response.contentAsString,
		)["accessToken"] as String

	private fun currentCode() = totp.generate(secret, clock.instant())

	private fun wrongCode() = if (currentCode() == "000000") "111111" else "000000"

	private fun MockHttpServletRequestDsl.json(body: Any) {
		contentType = MediaType.APPLICATION_JSON
		content = jsonMapper.writeValueAsString(body)
	}

	private fun MockHttpServletRequestDsl.bearer(token: String) = header(HttpHeaders.AUTHORIZATION, "Bearer $token")

	private fun json(body: String): Map<*, *> = jsonMapper.readValue(body, Map::class.java)

	private fun ResultActionsDsl.isProblem(status: Int) =
		andExpect {
			status { isEqualTo(status) }
			if (status >= 400) content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		}

	private companion object {
		const val USERNAME = "jane.doe"
		const val PASSWORD = "correct-horse-battery-staple"
		const val ANDROID_ORIGIN = "android:apk-key-hash:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
	}
}
