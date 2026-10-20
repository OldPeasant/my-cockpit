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
 * A request authenticated by a bearer token is marked ([isBearerAuthenticated]) so CSRF protection skips it: a
 * browser never attaches such a header on its own, so there is nothing to forge.
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
				request.setAttribute(AUTHENTICATED_ATTRIBUTE, true)
			}
		}
		chain.doFilter(request, response)
	}

	companion object {
		private const val PREFIX = "Bearer "
		private val AUTHENTICATED_ATTRIBUTE = "${BearerAuthenticationFilter::class.java.name}.authenticated"

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

		/** Matches requests this filter authenticated with a valid bearer token. */
		val isBearerAuthenticated = RequestMatcher { it.getAttribute(AUTHENTICATED_ATTRIBUTE) == true }
	}
}
