package ch.sonensei.cockpit.core.auth.infra

import ch.sonensei.cockpit.core.auth.domain.DeviceTokenService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Authenticates a request from an `Authorization: Bearer <access token>` header (Android app, SPEC.md §4.1). A request
 * with a bearer header is never authenticated by its session cookie as well: an unknown, expired or revoked token
 * leaves it anonymous, so the authorization rules answer 401.
 *
 * CSRF protection skips every request with a bearer header, valid or not ([withBearerHeader]): such a request is never
 * authenticated by its session cookie, and a browser cannot add an `Authorization` header to a cross-site request
 * without a CORS preflight, so there is nothing to forge. An invalid token thus answers 401 (not 403), so the app knows
 * to refresh.
 */
class BearerAuthenticationFilter(
	private val devices: DeviceTokenService,
) : OncePerRequestFilter() {
	override fun doFilterInternal(
		request: HttpServletRequest,
		response: HttpServletResponse,
		chain: FilterChain,
	) {
		val token = bearerToken(request)
		if (token != null && SecurityContextHolder.getContext().authentication == null) {
			devices.authenticate(token)?.let { principal ->
				val context = SecurityContextHolder.createEmptyContext()
				context.authentication = CockpitAuthentication(principal)
				SecurityContextHolder.setContext(context)
			}
		}
		chain.doFilter(request, response)
	}

	companion object {
		private const val PREFIX = "Bearer "

		/** True if the request carries an `Authorization: Bearer` header (valid or not). */
		fun hasBearerHeader(request: HttpServletRequest): Boolean =
			request.getHeader(HttpHeaders.AUTHORIZATION)?.regionMatches(0, PREFIX, 0, PREFIX.length, ignoreCase = true) == true

		fun bearerToken(request: HttpServletRequest): String? =
			request
				.getHeader(HttpHeaders.AUTHORIZATION)
				?.takeIf { hasBearerHeader(request) }
				?.substring(PREFIX.length)
				?.trim()
				?.takeIf { it.isNotEmpty() }

		/** Matches requests with an `Authorization: Bearer` header, valid or not ([hasBearerHeader]). */
		val withBearerHeader = RequestMatcher { hasBearerHeader(it) }
	}
}
