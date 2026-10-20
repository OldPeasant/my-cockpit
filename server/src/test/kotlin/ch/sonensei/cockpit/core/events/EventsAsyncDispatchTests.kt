package ch.sonensei.cockpit.core.events

import ch.sonensei.cockpit.core.auth.domain.AuthSession
import ch.sonensei.cockpit.core.auth.domain.LoginService
import ch.sonensei.cockpit.core.auth.domain.UserAccount
import ch.sonensei.cockpit.core.auth.infra.AuthSessionRepository
import ch.sonensei.cockpit.core.auth.infra.SessionCookies
import ch.sonensei.cockpit.core.auth.infra.UserAccountRepository
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.events.infra.SseEventHub
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import jakarta.servlet.AsyncEvent
import jakarta.servlet.DispatcherType
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.mock.web.MockAsyncContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.web.method.HandlerMethod
import java.time.Clock
import java.time.Duration

/**
 * The async dispatch that ends a stream passes the security filters. The session filter does not run again on that
 * dispatch, so it is anonymous; it must not be rejected after the original request was authorized by its cookie.
 * Uses a stored login (session cookie) instead of `@WithCockpitUser`, which would authenticate every dispatch.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EventsAsyncDispatchTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val accounts: UserAccountRepository,
	@Autowired private val sessions: AuthSessionRepository,
	@Autowired private val hub: SseEventHub,
	@Autowired private val clock: Clock,
) : PostgresTestBase() {
	private lateinit var account: UserAccount

	@BeforeEach
	fun login() {
		val now = clock.instant()
		account = accounts.save(UserAccount("sse.dispatcher", "fictitious-password-hash", EncryptedSecret("fictitious-totp-secret")))
		sessions.save(AuthSession(account, LoginService.hash(TOKEN), rememberDevice = false, now, now.plus(Duration.ofHours(1))))
	}

	@AfterEach
	fun cleanUp() {
		sessions.deleteAll(sessions.findAll().filter { it.tokenHash == LoginService.hash(TOKEN) })
		accounts.deleteById(account.id)
	}

	@Test
	fun `the async dispatch that ends a cookie-authenticated stream is not rejected`() {
		val clientsBefore = hub.clientCount
		val result =
			mockMvc
				.get(PATH) {
					accept = MediaType.TEXT_EVENT_STREAM
					cookie(Cookie(SessionCookies.NAME, TOKEN))
				}.andExpect {
					request { asyncStarted() }
					status { isOk() }
				}.andReturn()
		result.response.getContentAsString(Charsets.UTF_8) shouldStartWith ":connected"
		hub.clientCount shouldBe clientsBefore + 1

		// End the stream from the container side; Spring then dispatches the request again (DispatcherType.ASYNC).
		val context = result.request.asyncContext as MockAsyncContext
		context.listeners.forEach { it.onTimeout(AsyncEvent(context)) }
		hub.clientCount shouldBe clientsBefore

		val dispatched =
			mockMvc
				.perform(asyncDispatch(result))
				.andReturn()
		dispatched.request.dispatcherType shouldBe DispatcherType.ASYNC
		// Passed the authorization rules (a rejection answers 401 before the DispatcherServlet) and reached the controller.
		dispatched.response.status shouldNotBe 401
		(dispatched.handler as HandlerMethod).method.name shouldBe "stream"
	}

	private companion object {
		const val PATH = "/api/v1/events"
		const val TOKEN = "fictitious-sse-dispatch-token"
	}
}
