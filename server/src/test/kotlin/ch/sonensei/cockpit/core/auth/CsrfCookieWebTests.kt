package ch.sonensei.cockpit.core.auth

import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.SetupService
import ch.sonensei.cockpit.core.auth.domain.Totp
import ch.sonensei.cockpit.core.auth.infra.SessionCookies
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/**
 * The real double-submit flow, as the Angular client does it: the `XSRF-TOKEN` cookie from a first GET is echoed in
 * `X-XSRF-TOKEN`. Its own context (the property below): Spring Security's `csrf()` test helper, used by other tests,
 * replaces the CSRF token repository of the shared filter chain, and this test must see the real one. The context is
 * closed afterwards, so it does not hold database connections for the rest of the run.
 */
@SpringBootTest(properties = ["cockpit.test.context=real-csrf-repository"])
@DirtiesContext
@AutoConfigureMockMvc
class CsrfCookieWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val setup: SetupService,
	@Autowired private val clock: Clock,
) : PostgresTestBase() {
	private val totp = Totp()
	private lateinit var secret: ByteArray

	@BeforeEach
	fun activeAccount() {
		jdbc.update("delete from app_user")
		secret = Base32.decode(setup.start(USERNAME, PASSWORD).totpSecret)
		setup.confirmTotp(totp.generate(secret, clock.instant()))
	}

	@Test
	fun `the first GET issues a readable Lax XSRF-TOKEN cookie, also for the SPA and on a 401`() {
		for (path in listOf("/api/v1/setup/status", "/inbox", "/api/v1/auth/me")) {
			val result = mockMvc.get(path).andReturn()
			val setCookie = xsrfSetCookie(result)
			setCookie shouldNotContain "HttpOnly"
			setCookie shouldContain "Secure"
			setCookie shouldContain "Path=/"
			// MockMvc's Set-Cookie rendering omits generic cookie attributes; the servlet container writes them.
			result.response
				.getCookie(XSRF_COOKIE)
				.shouldNotBeNull()
				.getAttribute("SameSite") shouldBe "Lax"
		}
	}

	@Test
	fun `with the cookie echoed in the header the SPA logs in and out`() {
		val xsrf =
			mockMvc
				.get("/api/v1/setup/status")
				.andReturn()
				.response
				.getCookie(XSRF_COOKIE)
				.shouldNotBeNull()

		val loggedIn =
			mockMvc
				.post("/api/v1/auth/login") {
					contentType = MediaType.APPLICATION_JSON
					content = loginBody()
					cookie(xsrf)
					header(XSRF_HEADER, xsrf.value)
				}.andExpect { status { isOk() } }
				.andReturn()
		val session = loggedIn.response.getCookie(SessionCookies.NAME).shouldNotBeNull()

		mockMvc
			.post("/api/v1/auth/logout") {
				cookie(session, xsrf)
				header(XSRF_HEADER, xsrf.value)
			}.andExpect { status { isNoContent() } }
		mockMvc.get("/api/v1/auth/me") { cookie(session) }.andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `a missing or mismatching header is rejected with 403`() {
		val xsrf =
			mockMvc
				.get("/api/v1/setup/status")
				.andReturn()
				.response
				.getCookie(XSRF_COOKIE)
				.shouldNotBeNull()

		mockMvc
			.post("/api/v1/auth/login") {
				contentType = MediaType.APPLICATION_JSON
				content = loginBody()
				cookie(xsrf)
			}.andExpect { status { isForbidden() } }
		mockMvc
			.post("/api/v1/auth/login") {
				contentType = MediaType.APPLICATION_JSON
				content = loginBody()
				cookie(xsrf)
				header(XSRF_HEADER, "forged-${xsrf.value}")
			}.andExpect { status { isForbidden() } }
		// A header without the cookie is not enough either.
		mockMvc
			.post("/api/v1/auth/login") {
				contentType = MediaType.APPLICATION_JSON
				content = loginBody()
				header(XSRF_HEADER, xsrf.value)
			}.andExpect { status { isForbidden() } }
	}

	private fun xsrfSetCookie(result: MvcResult): String =
		result.response
			.getHeaders(HttpHeaders.SET_COOKIE)
			.single { it.startsWith("$XSRF_COOKIE=") }

	private fun loginBody(): String =
		jsonMapper.writeValueAsString(
			mapOf("username" to USERNAME, "password" to PASSWORD, "totp" to totp.generate(secret, clock.instant())),
		)

	private companion object {
		const val USERNAME = "jane.doe"
		const val PASSWORD = "correct-horse-battery-staple"
		const val XSRF_COOKIE = "XSRF-TOKEN"
		const val XSRF_HEADER = "X-XSRF-TOKEN"
	}
}
