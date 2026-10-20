package ch.sonensei.cockpit.core.auth.infra

import ch.sonensei.cockpit.core.auth.domain.CockpitPrincipal
import ch.sonensei.cockpit.core.auth.domain.LoginService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Authenticates a request from its session cookie. An unknown or expired session leaves the request anonymous (the
 * authorization rules then answer 401) and clears the stale cookie. A remembered session that slid forward gets its
 * persistent cookie re-issued, so the browser keeps it as long as the server does.
 *
 * An authentication that is already present (e.g. from a test's security context) is left alone, and a request with a
 * bearer header is left to [BearerAuthenticationFilter].
 */
class SessionAuthenticationFilter(
	private val login: LoginService,
	private val cookies: SessionCookies,
) : OncePerRequestFilter() {
	override fun doFilterInternal(
		request: HttpServletRequest,
		response: HttpServletResponse,
		chain: FilterChain,
	) {
		val token = cookies.read(request)
		val bearer = BearerAuthenticationFilter.hasBearerHeader(request)
		if (token != null && !bearer && SecurityContextHolder.getContext().authentication == null) {
			val session = login.authenticate(token)
			if (session == null) {
				cookies.clear(response)
			} else {
				val context = SecurityContextHolder.createEmptyContext()
				context.authentication = CockpitAuthentication(session.principal)
				SecurityContextHolder.setContext(context)
				if (session.renewed && session.rememberDevice) cookies.write(response, token, rememberDevice = true)
			}
		}
		chain.doFilter(request, response)
	}
}

/** The authentication of a logged-in owner. */
class CockpitAuthentication(
	private val principal: CockpitPrincipal,
) : AbstractAuthenticationToken(AuthorityUtils.createAuthorityList(ROLE_OWNER)) {
	init {
		isAuthenticated = true
	}

	override fun getPrincipal(): CockpitPrincipal = principal

	override fun getCredentials(): Any? = null

	override fun getName(): String = principal.username

	companion object {
		const val ROLE_OWNER = "ROLE_OWNER"
	}
}
