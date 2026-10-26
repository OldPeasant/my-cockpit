package ch.sonensei.cockpit.core.push

import ch.sonensei.cockpit.core.auth.domain.AuthSession
import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.LoginService
import ch.sonensei.cockpit.core.auth.domain.LoginThrottle
import ch.sonensei.cockpit.core.auth.domain.SetupService
import ch.sonensei.cockpit.core.auth.domain.Totp
import ch.sonensei.cockpit.core.auth.infra.AuthSessionRepository
import ch.sonensei.cockpit.core.auth.infra.SessionCookies
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
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID

/**
 * A Web Push subscription belongs to the login session that registered it (SPEC.md §4.1, §4.6): pushes stop when that
 * session ends by logout, idle expiry or because it was ended otherwise. Real logins (setup, password and TOTP, session
 * cookie) against Postgres; the push service is WireMock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PushSessionBindingTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val clock: MutableClock,
	@Autowired private val setup: SetupService,
	@Autowired private val throttle: LoginThrottle,
	@Autowired private val subscriptions: PushSubscriptionRepository,
	@Autowired private val authSessions: AuthSessionRepository,
	@Autowired private val push: PushService,
	@Autowired private val transactions: TransactionTemplate,
) : PostgresTestBase() {
	private val server = WireMockServer(wireMockConfig().dynamicPort())
	private val browser = PushTestBrowser()
	private val totp = Totp()
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
	fun tearDown() = subscriptions.deleteAll()

	@Test
	fun `subscribing stores the session of the request`() {
		val token = login()

		subscribe(token).andExpect { status { isNoContent() } }

		subscriptions.findAll().single().sessionId shouldBe sessionId(token)
	}

	@Test
	fun `subscribing again from a new session moves the subscription to it`() {
		val first = login()
		subscribe(first).andExpect { status { isNoContent() } }
		val second = login()

		subscribe(second).andExpect { status { isNoContent() } }

		val stored = subscriptions.findAll().single()
		stored.sessionId shouldBe sessionId(second)
		stored.version shouldBe 1
		logout(first)
		subscriptions.findAll().single().sessionId shouldBe sessionId(second)
		logout(second)
		subscriptions.findAll().shouldBeEmpty()
	}

	@Test
	fun `logout deletes the subscriptions of that session only`() {
		val mine = login()
		val other = login()
		subscribe(mine).andExpect { status { isNoContent() } }
		subscribe(other, endpoint = "$ENDPOINT-other").andExpect { status { isNoContent() } }

		logout(mine)

		subscriptions.findAll().map { it.endpoint } shouldContainExactly listOf("$ENDPOINT-other")
	}

	@Test
	fun `no push goes to a session past its idle timeout, and its subscription is deleted`() {
		val idle = sessionId(login())
		val active = sessionId(login())
		save("idle", idle)
		save("active", active)

		clock.advance(AuthSession.BROWSER_SESSION_IDLE.minusMinutes(1))
		push.send("mail", mapOf("count" to 1)) shouldBe 2
		transactions.executeWithoutResult {
			authSessions.touch(active, clock.instant(), clock.instant().plus(AuthSession.BROWSER_SESSION_IDLE))
		}
		clock.advance(Duration.ofMinutes(1))
		server.resetRequests()

		push.send("mail", mapOf("count" to 1)) shouldBe 1

		server.allServeEvents.map { it.request.url } shouldContainExactly listOf("/push/active")
		subscriptions.findAll().map { it.sessionId } shouldContainExactly listOf(active)
		// The expired session itself is left to the auth module (next login or use of its cookie).
		authSessions.findById(idle).isPresent shouldBe true
	}

	@Test
	fun `a session expired exactly now gets no push`() {
		val session = sessionId(login())
		save("a", session)
		clock.advance(AuthSession.BROWSER_SESSION_IDLE)

		push.send("mail", mapOf("count" to 1)) shouldBe 0

		server.allServeEvents.shouldBeEmpty()
		subscriptions.findAll().shouldBeEmpty()
	}

	@Test
	fun `an expired session whose cookie is used again takes its subscriptions with it`() {
		val token = login()
		subscribe(token).andExpect { status { isNoContent() } }
		clock.advance(AuthSession.BROWSER_SESSION_IDLE)

		mockMvc.get("/api/v1/auth/me") { cookie(Cookie(SessionCookies.NAME, token)) }.andExpect { status { isUnauthorized() } }

		subscriptions.findAll().shouldBeEmpty()
	}

	@Test
	fun `ending the other sessions (revoking them) deletes their subscriptions`() {
		val kept = login()
		val revoked = login()
		subscribe(kept).andExpect { status { isNoContent() } }
		subscribe(revoked, endpoint = "$ENDPOINT-revoked").andExpect { status { isNoContent() } }
		val userId = jdbc.queryForObject("select id from app_user", UUID::class.java)!!

		// What a TOTP re-enrollment does with the other sessions of the account.
		transactions.executeWithoutResult { authSessions.deleteOtherSessions(userId, LoginService.hash(kept)) }

		subscriptions.findAll().map { it.endpoint } shouldContainExactly listOf(ENDPOINT)
		subscriptions.findAll().single().sessionId shouldBe sessionId(kept)
	}

	@Test
	fun `deleting the account deletes its sessions and their subscriptions`() {
		subscribe(login()).andExpect { status { isNoContent() } }

		jdbc.update("delete from app_user")

		subscriptions.findAll().shouldBeEmpty()
	}

	/** Logs in with a new TOTP code (a code is accepted once) and returns the session token of the cookie. */
	private fun login(): String {
		clock.advance(Duration.ofSeconds(31))
		val body =
			jsonMapper.writeValueAsString(
				mapOf("username" to USERNAME, "password" to PASSWORD, "totp" to totp.generate(secret, clock.instant()), "rememberDevice" to false),
			)
		return mockMvc
			.post("/api/v1/auth/login") {
				contentType = MediaType.APPLICATION_JSON
				content = body
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

	private fun subscribe(
		token: String,
		endpoint: String = ENDPOINT,
	) = mockMvc.put("/api/v1/push/subscriptions") {
		cookie(Cookie(SessionCookies.NAME, token))
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content =
			jsonMapper.writeValueAsString(
				mapOf("endpoint" to endpoint, "keys" to mapOf("p256dh" to browser.p256dh, "auth" to browser.auth)),
			)
	}

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
		const val ENDPOINT = "https://push.example.test/send/session-binding"
	}
}
