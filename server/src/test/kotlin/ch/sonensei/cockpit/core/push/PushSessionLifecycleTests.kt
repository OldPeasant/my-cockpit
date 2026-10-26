package ch.sonensei.cockpit.core.push

import ch.sonensei.cockpit.core.auth.domain.AuthSession
import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.DeviceTokenService
import ch.sonensei.cockpit.core.auth.domain.LoginCredentials
import ch.sonensei.cockpit.core.auth.domain.LoginResult
import ch.sonensei.cockpit.core.auth.domain.LoginService
import ch.sonensei.cockpit.core.auth.domain.LoginThrottle
import ch.sonensei.cockpit.core.auth.domain.SetupService
import ch.sonensei.cockpit.core.auth.domain.Totp
import ch.sonensei.cockpit.core.auth.infra.SessionCookies
import ch.sonensei.cockpit.core.auth.passkey.TestAuthenticator
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.push.domain.PushService
import ch.sonensei.cockpit.core.push.domain.PushSubscription
import ch.sonensei.cockpit.core.push.infra.PushSubscriptionRepository
import ch.sonensei.cockpit.support.PostgresTestBase
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID

/**
 * Which logins carry a session id into [ch.sonensei.cockpit.core.auth.domain.CockpitPrincipal] (password login, passkey
 * login, the session cookie; not a device token), and the session lifecycle of a Web Push subscription beyond
 * [PushSessionBindingTests]: remembered sessions, the expired-session cleanup of a new login, and the version of a
 * subscription that is registered again. Real logins against Postgres; the push service is WireMock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PushSessionLifecycleTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val clock: MutableClock,
	@Autowired private val setup: SetupService,
	@Autowired private val throttle: LoginThrottle,
	@Autowired private val loginService: LoginService,
	@Autowired private val deviceTokens: DeviceTokenService,
	@Autowired private val subscriptions: PushSubscriptionRepository,
	@Autowired private val push: PushService,
) : PostgresTestBase() {
	private val server = WireMockServer(wireMockConfig().dynamicPort())
	private val browser = PushTestBrowser()
	private val totp = Totp()
	private val sessions = PushTestSessions(jdbc)
	private lateinit var secret: ByteArray

	@BeforeAll
	fun start() = server.start()

	@AfterAll
	fun stop() = server.stop()

	@BeforeEach
	fun setUp() {
		server.resetAll()
		server.stubFor(post(urlPathMatching("/push/.*")).willReturn(aResponse().withStatus(201)))
		subscriptions.deleteAll()
		clock.set(MutableClockTestConfiguration.START)
		throttle.recordSuccess()
		jdbc.update("delete from app_user")
		secret = Base32.decode(setup.start(USERNAME, PASSWORD).totpSecret)
		setup.confirmTotp(totp.generate(secret, clock.instant()))
	}

	@AfterEach
	fun tearDown() {
		subscriptions.deleteAll()
		// Other test classes insert their own account and expect none to be left over.
		throttle.recordSuccess()
		jdbc.update("delete from app_user")
	}

	@Test
	fun `a password login and its session cookie give the principal the id of that session`() {
		val result = loginService.login(LoginCredentials(USERNAME, PASSWORD, nextCode(), null, rememberDevice = false))

		val grant = result.shouldBeInstanceOf<LoginResult.Success>().grant
		val sessionId = sessionId(grant.token)
		grant.principal.sessionId shouldBe sessionId
		loginService
			.authenticate(grant.token)
			.shouldNotBeNull()
			.principal.sessionId shouldBe sessionId
	}

	@Test
	fun `two logins get principals with different session ids`() {
		val first = loginService.login(LoginCredentials(USERNAME, PASSWORD, nextCode(), null, rememberDevice = false))
		val second = loginService.login(LoginCredentials(USERNAME, PASSWORD, nextCode(), null, rememberDevice = true))

		val firstSession =
			first
				.shouldBeInstanceOf<LoginResult.Success>()
				.grant.principal.sessionId
		val secondSession =
			second
				.shouldBeInstanceOf<LoginResult.Success>()
				.grant.principal.sessionId
		firstSession.shouldNotBeNull()
		secondSession.shouldNotBeNull()
		firstSession shouldNotBe secondSession
	}

	@Test
	fun `a device token's principal has no session, so it cannot subscribe to Web Push`() {
		val access = deviceAccessToken()

		deviceTokens
			.authenticate(access)
			.shouldNotBeNull()
			.sessionId
			.shouldBeNull()
		mockMvc
			.put(SUBSCRIPTIONS) {
				header(HttpHeaders.AUTHORIZATION, "Bearer $access")
				subscriptionBody()
			}.andExpect {
				status { isForbidden() }
				content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
				jsonPath("$.status") { value(403) }
				jsonPath("$.detail") { value("Web Push needs a browser login session") }
			}

		subscriptions.findAll().shouldBeEmpty()
	}

	@Test
	fun `a browser logged in with a passkey subscribes for its passkey session, and its logout ends the subscription`() {
		val passwordSession = login()
		val authenticator = registerPasskey(passwordSession)
		val passkeySession = passkeyLogin(authenticator)

		subscribe(passkeySession).andExpect { status { isNoContent() } }

		subscriptions.findAll().single().sessionId shouldBe sessionId(passkeySession)
		logout(passwordSession)
		subscriptions.findAll().single().sessionId shouldBe sessionId(passkeySession)
		logout(passkeySession)
		subscriptions.findAll().shouldBeEmpty()
	}

	@Test
	fun `a remembered session keeps getting pushes after 12 hours and loses them 30 days after its last use`() {
		val session = sessionId(login(rememberDevice = true))
		save("remembered", session)

		clock.advance(AuthSession.BROWSER_SESSION_IDLE.plusHours(1))
		push.send("mail", mapOf("count" to 1)) shouldBe 1

		// The login helper advanced the clock 31 s before the session was written.
		clock.set(
			MutableClockTestConfiguration.START
				.plus(LOGIN_STEP)
				.plus(AuthSession.REMEMBERED_LIFETIME)
				.minusSeconds(1),
		)
		push.send("mail", mapOf("count" to 2)) shouldBe 1
		clock.advance(Duration.ofSeconds(1))
		server.resetRequests()

		push.send("mail", mapOf("count" to 3)) shouldBe 0

		server.allServeEvents.shouldBeEmpty()
		subscriptions.findAll().shouldBeEmpty()
	}

	@Test
	fun `a new login cleans up expired sessions and takes their subscriptions with them, without any push`() {
		val expired = login()
		subscribe(expired).andExpect { status { isNoContent() } }
		clock.advance(AuthSession.BROWSER_SESSION_IDLE)

		val current = login()

		subscriptions.findAll().shouldBeEmpty()
		jdbc.queryForList("select id from auth_session", UUID::class.java) shouldContainExactly listOf(sessionId(current))
	}

	@Test
	fun `an expired session cookie cannot subscribe`() {
		val token = login()
		clock.advance(AuthSession.BROWSER_SESSION_IDLE)

		subscribe(token).andExpect { status { isUnauthorized() } }

		subscriptions.findAll().shouldBeEmpty()
	}

	@Test
	fun `registering again from the same session keeps the version, a new session bumps it once`() {
		val first = sessions.create()
		val second = sessions.create()
		val created = push.subscribe(first, ENDPOINT, browser.p256dh, browser.auth)
		created.version shouldBe 0
		created.sessionId shouldBe first

		clock.advance(Duration.ofMinutes(1))
		val same = push.subscribe(first, ENDPOINT, browser.p256dh, browser.auth)
		same.id shouldBe created.id
		same.version shouldBe 0
		same.updatedAt shouldBe created.updatedAt

		clock.advance(Duration.ofMinutes(1))
		val moved = push.subscribe(second, ENDPOINT, browser.p256dh, browser.auth)
		moved.id shouldBe created.id
		moved.sessionId shouldBe second
		moved.version shouldBe 1
		moved.createdAt shouldBe created.createdAt
		moved.updatedAt shouldBe clock.instant()

		clock.advance(Duration.ofMinutes(1))
		val again = push.subscribe(second, ENDPOINT, browser.p256dh, browser.auth)
		again.version shouldBe 1
		again.updatedAt shouldBe moved.updatedAt
		subscriptions.count() shouldBe 1
	}

	@Test
	fun `the subscriptions of one session all end with it, those of other sessions stay`() {
		val ending = sessions.create()
		val staying = sessions.create()
		push.subscribe(ending, "$ENDPOINT-1", browser.p256dh, browser.auth)
		push.subscribe(ending, "$ENDPOINT-2", browser.p256dh, browser.auth)
		push.subscribe(staying, "$ENDPOINT-3", browser.p256dh, browser.auth)

		sessions.delete(ending)

		subscriptions.findAll().map { it.endpoint } shouldContainExactly listOf("$ENDPOINT-3")
	}

	@Test
	fun `a session whose expiry slid forward keeps getting pushes past its first expiry`() {
		val token = login()
		val session = sessionId(token)
		save("sliding", session)

		// Using the cookie after more than a minute writes a new expiry (LoginService.TOUCH_INTERVAL).
		clock.advance(AuthSession.BROWSER_SESSION_IDLE.minusHours(1))
		mockMvc.get("/api/v1/auth/me") { cookie(Cookie(SessionCookies.NAME, token)) }.andExpect { status { isOk() } }
		clock.advance(Duration.ofHours(2))

		push.send("mail", mapOf("count" to 1)) shouldBe 1

		server.allServeEvents.map { it.request.url } shouldContainExactly listOf("/push/sliding")
	}

	// --- helpers ---

	/** A TOTP code of the next 30-second step (a code is accepted once). */
	private fun nextCode(): String {
		clock.advance(LOGIN_STEP)
		return totp.generate(secret, clock.instant())
	}

	/** Logs in with password and TOTP and returns the session token of the cookie. */
	private fun login(rememberDevice: Boolean = false): String {
		val code = nextCode()
		return mockMvc
			.post("/api/v1/auth/login") {
				json(mapOf("username" to USERNAME, "password" to PASSWORD, "totp" to code, "rememberDevice" to rememberDevice))
				with(csrf())
			}.andExpect { status { isOk() } }
			.andReturn()
			.response
			.getCookie(SessionCookies.NAME)
			.shouldNotBeNull()
			.value
	}

	private fun logout(token: String) =
		mockMvc
			.post("/api/v1/auth/logout") {
				cookie(Cookie(SessionCookies.NAME, token))
				with(csrf())
			}.andExpect { status { isNoContent() } }

	private fun deviceAccessToken(): String {
		val code = nextCode()
		return json(
			mockMvc
				.post("/api/v1/auth/token") {
					json(mapOf("username" to USERNAME, "password" to PASSWORD, "totp" to code, "deviceName" to "Fictitious Phone"))
				}.andExpect { status { isOk() } }
				.andReturn()
				.response.contentAsString,
		)["accessToken"] as String
	}

	private fun registerPasskey(sessionToken: String): TestAuthenticator {
		val code = nextCode()
		val options =
			json(
				mockMvc
					.post("/api/v1/auth/passkeys/registration/options") {
						cookie(Cookie(SessionCookies.NAME, sessionToken))
						json(mapOf("totp" to code))
						with(csrf())
					}.andExpect { status { isOk() } }
					.andReturn()
					.response.contentAsString,
			)
		val authenticator = TestAuthenticator()
		val credential = authenticator.register(options["publicKey"] as Map<*, *>)
		mockMvc
			.post("/api/v1/auth/passkeys/registration") {
				cookie(Cookie(SessionCookies.NAME, sessionToken))
				json(mapOf("ceremonyId" to options["ceremonyId"], "name" to "Fictitious Laptop", "credential" to credential))
				with(csrf())
			}.andExpect { status { isCreated() } }
		return authenticator
	}

	/** Logs the browser in with [authenticator] and returns the session token of the cookie. */
	private fun passkeyLogin(authenticator: TestAuthenticator): String {
		val options =
			json(
				mockMvc
					.post("/api/v1/auth/passkeys/authentication/options") { with(csrf()) }
					.andExpect { status { isOk() } }
					.andReturn()
					.response.contentAsString,
			)
		val credential = authenticator.authenticate(options["publicKey"] as Map<*, *>)
		return mockMvc
			.post("/api/v1/auth/passkeys/login") {
				json(mapOf("ceremonyId" to options["ceremonyId"], "credential" to credential, "rememberDevice" to false))
				with(csrf())
			}.andExpect { status { isOk() } }
			.andReturn()
			.response
			.getCookie(SessionCookies.NAME)
			.shouldNotBeNull()
			.value
	}

	private fun subscribe(
		token: String,
		endpoint: String = ENDPOINT,
	) = mockMvc.put(SUBSCRIPTIONS) {
		cookie(Cookie(SessionCookies.NAME, token))
		with(csrf())
		subscriptionBody(endpoint)
	}

	private fun MockHttpServletRequestDsl.subscriptionBody(endpoint: String = ENDPOINT) =
		json(mapOf("endpoint" to endpoint, "keys" to mapOf("p256dh" to browser.p256dh, "auth" to browser.auth)))

	private fun MockHttpServletRequestDsl.json(body: Any) {
		contentType = MediaType.APPLICATION_JSON
		content = jsonMapper.writeValueAsString(body)
	}

	private fun json(body: String): Map<*, *> = jsonMapper.readValue(body, Map::class.java)

	/** A subscription pointing at WireMock (plain http, which the API would refuse) for [sessionId]. */
	private fun save(
		path: String,
		sessionId: UUID,
	) = subscriptions.save(PushSubscription("http://127.0.0.1:${server.port()}/push/$path", browser.p256dh, browser.auth, sessionId))

	private fun sessionId(token: String): UUID =
		jdbc.queryForObject("select id from auth_session where token_hash = ?", UUID::class.java, LoginService.hash(token))!!

	private companion object {
		const val USERNAME = "jane.doe"
		const val PASSWORD = "correct-horse-battery-staple"
		const val SUBSCRIPTIONS = "/api/v1/push/subscriptions"
		const val ENDPOINT = "https://push.example.test/send/session-lifecycle"
		val LOGIN_STEP: Duration = Duration.ofSeconds(31)
	}
}
