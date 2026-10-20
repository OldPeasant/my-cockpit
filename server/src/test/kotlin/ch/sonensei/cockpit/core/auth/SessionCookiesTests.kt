package ch.sonensei.cockpit.core.auth

import ch.sonensei.cockpit.core.auth.domain.AuthSession
import ch.sonensei.cockpit.core.auth.infra.SessionCookies
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.support.PropertiesLoaderUtils
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/** Session and XSRF cookie attributes for both values of `cockpit.auth.secure-cookie`, and reading the cookie back. */
class SessionCookiesTests {
	private val production = SessionCookies(secure = true)
	private val dev = SessionCookies(secure = false)

	@Test
	fun `the dev profile, and only it, turns the Secure flag off`() {
		PropertiesLoaderUtils.loadProperties(ClassPathResource("application-dev.properties"))["cockpit.auth.secure-cookie"] shouldBe "false"
		PropertiesLoaderUtils.loadProperties(ClassPathResource("application.properties"))["cockpit.auth.secure-cookie"].shouldBeNull()
		PropertiesLoaderUtils.loadProperties(ClassPathResource("application-e2e.properties"))["cockpit.auth.secure-cookie"].shouldBeNull()
	}

	@Test
	fun `with secure-cookie=false the session cookie is HttpOnly and Lax but not Secure`() {
		val browserSession = setCookie { dev.write(it, TOKEN, rememberDevice = false) }
		val remembered = setCookie { dev.write(it, TOKEN, rememberDevice = true) }

		for (header in listOf(browserSession, remembered)) {
			header shouldStartWith "${SessionCookies.NAME}=$TOKEN"
			header shouldContain "HttpOnly"
			header shouldContain "SameSite=Lax"
			header shouldContain "Path=/"
			header shouldNotContain "Secure"
		}
		browserSession shouldNotContain "Max-Age"
		browserSession shouldNotContain "Expires"
		remembered shouldContain "Max-Age=${AuthSession.REMEMBERED_LIFETIME.seconds}"
	}

	@Test
	fun `by default the session cookie is Secure as well`() {
		val header = setCookie { production.write(it, TOKEN, rememberDevice = true) }

		header shouldContain "Secure"
		header shouldContain "HttpOnly"
		header shouldContain "SameSite=Lax"
	}

	@Test
	fun `clearing the cookie expires it with the same attributes`() {
		for ((cookies, secure) in listOf(production to true, dev to false)) {
			val header = setCookie { cookies.clear(it) }

			header shouldStartWith "${SessionCookies.NAME}=;"
			header shouldContain "Max-Age=0"
			header shouldContain "Path=/"
			header shouldContain "HttpOnly"
			(header.contains("Secure")) shouldBe secure
		}
	}

	@Test
	fun `the XSRF-TOKEN cookie follows the Secure setting and stays readable by the SPA`() {
		for ((cookies, secure) in listOf(production to true, dev to false)) {
			val response = MockHttpServletResponse()
			val repository = cookies.csrfTokenRepository()
			val request = MockHttpServletRequest()

			repository.saveToken(repository.generateToken(request), request, response)

			val cookie = response.getCookie("XSRF-TOKEN").shouldNotBeNull()
			cookie.secure shouldBe secure
			cookie.isHttpOnly shouldBe false
			cookie.path shouldBe "/"
			cookie.getAttribute("SameSite") shouldBe "Lax"
		}
	}

	@Test
	fun `reading takes the session cookie and ignores other, blank or missing cookies`() {
		dev.read(MockHttpServletRequest()).shouldBeNull()
		dev.read(request(Cookie("XSRF-TOKEN", "abc"))).shouldBeNull()
		dev.read(request(Cookie(SessionCookies.NAME, ""))).shouldBeNull()
		dev.read(request(Cookie(SessionCookies.NAME, "   "))).shouldBeNull()
		dev.read(request(Cookie("XSRF-TOKEN", "abc"), Cookie(SessionCookies.NAME, TOKEN))) shouldBe TOKEN
	}

	private fun request(vararg cookies: Cookie) = MockHttpServletRequest().apply { setCookies(*cookies) }

	private fun setCookie(action: (MockHttpServletResponse) -> Unit): String =
		MockHttpServletResponse()
			.also(action)
			.getHeaders(HttpHeaders.SET_COOKIE)
			.single()

	private companion object {
		const val TOKEN = "fictitious-session-token"
	}
}
