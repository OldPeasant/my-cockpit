package ch.sonensei.cockpit.core.auth

import ch.sonensei.cockpit.core.auth.domain.AuthDevice
import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.LoginThrottle
import ch.sonensei.cockpit.core.auth.domain.SetupService
import ch.sonensei.cockpit.core.auth.domain.Totp
import ch.sonensei.cockpit.core.auth.infra.SessionCookies
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

/** Device tokens for the Android app (task p0-auth-tokens) against a real account and a test clock. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
class DeviceTokenWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val clock: MutableClock,
	@Autowired private val setup: SetupService,
	@Autowired private val throttle: LoginThrottle,
) : PostgresTestBase() {
	private val totp = Totp()
	private lateinit var secret: ByteArray

	@BeforeEach
	fun activeAccount() {
		clock.set(MutableClockTestConfiguration.START)
		throttle.recordSuccess()
		jdbc.update("delete from app_user")
		val enrollment = setup.start(USERNAME, PASSWORD)
		secret = Base32.decode(enrollment.totpSecret)
		setup.confirmTotp(currentCode())
	}

	@Test
	fun `token issues a 15-minute access token and a refresh token, stored only as hashes`() {
		val response = issue().andExpect { status { isOk() } }.andReturn().response
		response.getHeader(HttpHeaders.CACHE_CONTROL) shouldBe "no-store"
		val tokens = json(response.contentAsString)

		tokens["tokenType"] shouldBe "Bearer"
		Instant.parse(tokens["accessTokenExpiresAt"] as String) shouldBe clock.instant().plus(Duration.ofMinutes(15))
		Instant.parse(tokens["refreshTokenExpiresAt"] as String) shouldBe clock.instant().plus(AuthDevice.REFRESH_LIFETIME)
		val access = tokens["accessToken"] as String
		val refresh = tokens["refreshToken"] as String
		access shouldNotBe refresh
		val row = jdbc.queryForMap("select name, access_token_hash, refresh_token_hash from auth_device")
		row["name"] shouldBe "Pixel 9"
		row["access_token_hash"] shouldNotBe access
		row["refresh_token_hash"] shouldNotBe refresh
		jdbc.queryForObject(
			"select count(*) from auth_device where access_token_hash like ? or refresh_token_hash like ?",
			Int::class.java,
			"%$access%",
			"%$refresh%",
		) shouldBe 0

		me(access).andExpect {
			status { isOk() }
			jsonPath("$.username") { value(USERNAME) }
		}
	}

	@Test
	fun `the access token expires after 15 minutes and a refresh gives a new pair`() {
		val first = tokens(issue())

		clock.advance(Duration.ofMinutes(14))
		me(first.access).andExpect { status { isOk() } }
		clock.advance(Duration.ofMinutes(1))
		me(first.access).andExpect { status { isUnauthorized() } }

		val second = tokens(refresh(first.refresh).andExpect { status { isOk() } })
		second.access shouldNotBe first.access
		second.refresh shouldNotBe first.refresh
		me(second.access).andExpect { status { isOk() } }
		jdbc.queryForObject("select count(*) from auth_device", Int::class.java) shouldBe 1
	}

	@Test
	fun `a refresh rotates the token, and reusing the old one revokes the device`() {
		val first = tokens(issue())
		val second = tokens(refresh(first.refresh).andExpect { status { isOk() } })
		me(first.access).andExpect { status { isUnauthorized() } }

		refresh(first.refresh).isProblem(401)

		// The device is gone: the current pair stops working too.
		me(second.access).andExpect { status { isUnauthorized() } }
		refresh(second.refresh).isProblem(401)
		jdbc.queryForObject("select count(*) from auth_device", Int::class.java) shouldBe 0
	}

	@Test
	fun `a refresh token not used for 90 days expires`() {
		val first = tokens(issue())

		clock.advance(AuthDevice.REFRESH_LIFETIME)
		refresh(first.refresh).isProblem(401)
		refresh("not-a-refresh-token").isProblem(401)
	}

	@Test
	fun `bearer access works on API endpoints without a CSRF token`() {
		val access = tokens(issue()).access

		mockMvc.get("/api/v1/settings") { bearer(access) }.andExpect { status { isOk() } }
		mockMvc
			.patch("/api/v1/settings") {
				bearer(access)
				header(HttpHeaders.IF_MATCH, "*")
				contentType = MediaType.APPLICATION_JSON
				content = """{"theme": "dark"}"""
			}.andExpect {
				status { isOk() }
				jsonPath("$.theme") { value("dark") }
			}
	}

	@Test
	fun `a session cookie still needs the CSRF token, and an invalid bearer token is not rescued by a cookie`() {
		val session = webLogin()

		mockMvc
			.patch("/api/v1/settings") {
				cookie(Cookie(SessionCookies.NAME, session))
				header(HttpHeaders.IF_MATCH, "*")
				contentType = MediaType.APPLICATION_JSON
				content = """{"theme": "dark"}"""
			}.andExpect { status { isForbidden() } }
		mockMvc.get("/api/v1/auth/me") { cookie(Cookie(SessionCookies.NAME, session)) }.andExpect { status { isOk() } }
		mockMvc
			.get("/api/v1/auth/me") {
				cookie(Cookie(SessionCookies.NAME, session))
				bearer("not-an-access-token")
			}.andExpect { status { isUnauthorized() } }
		// With a bearer header the cookie is ignored, so an invalid token is a 401 (the app refreshes), not a CSRF 403.
		mockMvc
			.patch("/api/v1/settings") {
				cookie(Cookie(SessionCookies.NAME, session))
				bearer("not-an-access-token")
				header(HttpHeaders.IF_MATCH, "*")
				contentType = MediaType.APPLICATION_JSON
				content = """{"theme": "dark"}"""
			}.andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `devices are listed and a revoked device's tokens answer 401`() {
		val phone = tokens(issue(deviceName = "Pixel 9"))
		clock.advance(Duration.ofMinutes(1))
		val tablet = tokens(issue(deviceName = "Tablet"))

		val list = json("{\"d\":" + listDevices(tablet.access).andReturn().response.contentAsString + "}")["d"] as List<*>
		list.map { (it as Map<*, *>)["name"] } shouldContainExactly listOf("Pixel 9", "Tablet")
		(list[0] as Map<*, *>)["id"] shouldBe phone.deviceId

		mockMvc.delete("/api/v1/auth/devices/${phone.deviceId}") { bearer(tablet.access) }.andExpect { status { isNoContent() } }

		me(phone.access).andExpect { status { isUnauthorized() } }
		refresh(phone.refresh).isProblem(401)
		me(tablet.access).andExpect { status { isOk() } }
		mockMvc.delete("/api/v1/auth/devices/${phone.deviceId}") { bearer(tablet.access) }.isProblem(404)
	}

	@Test
	fun `the web app lists and revokes devices with its session`() {
		val phone = tokens(issue())
		val session = webLogin()

		mockMvc
			.get("/api/v1/auth/devices") { cookie(Cookie(SessionCookies.NAME, session)) }
			.andExpect { jsonPath("$[0].id") { value(phone.deviceId) } }
		mockMvc
			.delete("/api/v1/auth/devices/${phone.deviceId}") {
				cookie(Cookie(SessionCookies.NAME, session))
				with(csrf())
			}.andExpect { status { isNoContent() } }
		me(phone.access).andExpect { status { isUnauthorized() } }
		mockMvc.get("/api/v1/auth/devices").andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `token rejects wrong credentials like the login and shares its throttle`() {
		issue(password = "wrong-fictitious-password").isProblem(401)
		issue(deviceName = " ").isProblem(400)
		issue(totp = null).isProblem(400)
		jdbc.queryForList("select id from auth_device", String::class.java).shouldBeEmpty()

		repeat(LoginThrottle.FREE_ATTEMPTS - 1) { issue(password = "wrong-fictitious-password").isProblem(401) }
		issue().isProblem(429).andExpect { header { string(HttpHeaders.RETRY_AFTER, "30") } }
	}

	private fun currentCode() = totp.generate(secret, clock.instant())

	private fun issue(
		password: String = PASSWORD,
		totp: String? = currentCode(),
		deviceName: String = "Pixel 9",
	) = mockMvc.post("/api/v1/auth/token") {
		contentType = MediaType.APPLICATION_JSON
		content =
			jsonMapper.writeValueAsString(
				mapOf("username" to USERNAME, "password" to password, "totp" to totp, "deviceName" to deviceName),
			)
	}

	private fun refresh(token: String) =
		mockMvc.post("/api/v1/auth/token/refresh") {
			contentType = MediaType.APPLICATION_JSON
			content = jsonMapper.writeValueAsString(mapOf("refreshToken" to token))
		}

	private fun webLogin(): String =
		mockMvc
			.post("/api/v1/auth/login") {
				contentType = MediaType.APPLICATION_JSON
				content = jsonMapper.writeValueAsString(mapOf("username" to USERNAME, "password" to PASSWORD, "totp" to currentCode()))
				with(csrf())
			}.andExpect { status { isOk() } }
			.andReturn()
			.response
			.getCookie(SessionCookies.NAME)!!
			.value

	private fun me(access: String) = mockMvc.get("/api/v1/auth/me") { bearer(access) }

	private fun listDevices(access: String) = mockMvc.get("/api/v1/auth/devices") { bearer(access) }.andExpect { status { isOk() } }

	private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.bearer(token: String) =
		header(HttpHeaders.AUTHORIZATION, "Bearer $token")

	private fun tokens(result: ResultActionsDsl): Tokens {
		val body = json(result.andReturn().response.contentAsString)
		return Tokens(body["deviceId"] as String, body["accessToken"] as String, body["refreshToken"] as String)
	}

	private fun json(body: String): Map<*, *> = jsonMapper.readValue(body, Map::class.java)

	private fun ResultActionsDsl.isProblem(status: Int) =
		andExpect {
			status { isEqualTo(status) }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		}

	private class Tokens(
		val deviceId: String,
		val access: String,
		val refresh: String,
	)

	private companion object {
		const val USERNAME = "jane.doe"
		const val PASSWORD = "correct-horse-battery-staple"
	}
}
