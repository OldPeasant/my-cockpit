package ch.sonensei.cockpit.core.auth.infra

import ch.sonensei.cockpit.core.auth.domain.AuthSession
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * The session cookie (SPEC.md §4.1): `HttpOnly`, `SameSite=Lax`, and `Secure` unless `cockpit.auth.secure-cookie`
 * is false (only the dev profile, which runs on plain HTTP). With "remember device" it is persistent for the sliding
 * 30 days, otherwise it is a browser-session cookie.
 */
@Component
class SessionCookies(
	@Value("\${cockpit.auth.secure-cookie:true}") private val secure: Boolean,
) {
	fun read(request: HttpServletRequest): String? =
		request.cookies
			?.firstOrNull { it.name == NAME }
			?.value
			?.takeIf { it.isNotBlank() }

	fun write(
		response: HttpServletResponse,
		token: String,
		rememberDevice: Boolean,
	) {
		val maxAge = if (rememberDevice) AuthSession.REMEMBERED_LIFETIME else null
		response.addHeader(HttpHeaders.SET_COOKIE, cookie(token, maxAge).toString())
	}

	/**
	 * Angular's `XSRF-TOKEN` cookie: readable by the SPA (not `HttpOnly`), with the same `Secure` and `SameSite`
	 * attributes as the session cookie.
	 */
	private val csrfTokens: CookieCsrfTokenRepository =
		CookieCsrfTokenRepository.withHttpOnlyFalse().apply {
			setCookieCustomizer { it.secure(secure).sameSite("Lax").path("/") }
		}

	fun csrfTokenRepository(): CookieCsrfTokenRepository = csrfTokens

	/**
	 * Replaces the `XSRF-TOKEN` cookie with a fresh token, as Spring Security does when a user logs in, so the token a
	 * client held before login (or logout) is not carried over. The SPA reads the new cookie for its next request.
	 */
	fun rotateCsrfToken(
		request: HttpServletRequest,
		response: HttpServletResponse,
	) {
		csrfTokens.saveToken(csrfTokens.generateToken(request), request, response)
	}

	fun clear(response: HttpServletResponse) {
		response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
	}

	private fun cookie(
		value: String,
		maxAge: Duration?,
	): ResponseCookie =
		ResponseCookie
			.from(NAME, value)
			.httpOnly(true)
			.secure(secure)
			.sameSite("Lax")
			.path("/")
			.apply { if (maxAge != null) maxAge(maxAge) }
			.build()

	companion object {
		const val NAME = "COCKPIT_SESSION"
	}
}
