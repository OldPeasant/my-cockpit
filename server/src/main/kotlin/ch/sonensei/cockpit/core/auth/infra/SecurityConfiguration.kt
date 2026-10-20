package ch.sonensei.cockpit.core.auth.infra

import ch.sonensei.cockpit.core.auth.domain.DeviceTokenService
import ch.sonensei.cockpit.core.auth.domain.LoginService
import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.filter.OncePerRequestFilter

/**
 * HTTP security (SPEC.md §4.1).
 *
 * - Everything under `/api/`, `/actuator/` and the OpenAPI docs needs a login, except the health endpoint, the
 *   setup flow (it runs before an account exists) and login/logout. The SPA (static files and the `index.html`
 *   fallback for client routes) is public for GET/HEAD; any other request outside the API needs a login too.
 * - Logins are sessions in the database ([SessionAuthenticationFilter], cookie [SessionCookies.NAME]); there is no
 *   servlet `HttpSession`. The Android app sends `Authorization: Bearer` access tokens of a device
 *   ([BearerAuthenticationFilter]); it gets them from the public token endpoints.
 * - CSRF: double-submit cookie in Angular's convention. The `XSRF-TOKEN` cookie is set on every response that has
 *   none yet and replaced with a fresh token on login and logout; state-changing requests must echo it in
 *   `X-XSRF-TOKEN`, login and logout included. Bearer-authenticated requests and the token endpoints (called by the
 *   app, which has no cookies; their response is unreadable cross-origin) are exempt.
 * - Unauthenticated requests get a plain 401 (no login form or basic-auth challenge).
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
	@Bean
	fun securityFilterChain(
		http: HttpSecurity,
		login: LoginService,
		devices: DeviceTokenService,
		cookies: SessionCookies,
	): SecurityFilterChain =
		http
			.authorizeHttpRequests {
				it
					// The forward of `/` to `/index.html`, error dispatches and the async dispatch that ends a stream (SSE)
					// carry no new request of their own; the original request was authorized.
					.dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR, DispatcherType.ASYNC)
					.permitAll()
					.requestMatchers("/actuator/health", "/actuator/health/**", "$SETUP_PATH/**", SETUP_PATH)
					.permitAll()
					.requestMatchers(HttpMethod.POST, LOGIN_PATH, LOGOUT_PATH, TOKEN_PATH, TOKEN_REFRESH_PATH)
					.permitAll()
					.requestMatchers("/api/**", "/actuator/**", "/v3/api-docs/**", "/v3/api-docs*", "/swagger-ui/**", "/swagger-ui*")
					.authenticated()
					.requestMatchers(HttpMethod.GET, "/**")
					.permitAll()
					.requestMatchers(HttpMethod.HEAD, "/**")
					.permitAll()
					.anyRequest()
					.authenticated()
			}.csrf {
				// The setup flow runs before any session or CSRF cookie exists; it is only open while no account is active.
				it
					.spa()
					.csrfTokenRepository(cookies.csrfTokenRepository())
					.ignoringRequestMatchers("$SETUP_PATH/**", SETUP_PATH, TOKEN_PATH, TOKEN_REFRESH_PATH)
					.ignoringRequestMatchers(BearerAuthenticationFilter.isBearerAuthenticated)
			}.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
			.requestCache { it.disable() }
			.addFilterBefore(BearerAuthenticationFilter(devices), CsrfFilter::class.java)
			.addFilterBefore(SessionAuthenticationFilter(login, cookies), CsrfFilter::class.java)
			.addFilterAfter(CsrfCookieFilter(), CsrfFilter::class.java)
			.exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
			.build()

	/** Argon2id with Spring Security's recommended parameters (salted, encoded with its parameters). */
	@Bean
	fun passwordEncoder(): PasswordEncoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()

	companion object {
		const val SETUP_PATH = "/api/v1/setup"
		const val AUTH_PATH = "/api/v1/auth"
		const val LOGIN_PATH = "$AUTH_PATH/login"
		const val LOGOUT_PATH = "$AUTH_PATH/logout"
		const val TOKEN_PATH = "$AUTH_PATH/token"
		const val TOKEN_REFRESH_PATH = "$TOKEN_PATH/refresh"
	}
}

/** Loads the deferred CSRF token, so the `XSRF-TOKEN` cookie is issued already on the SPA's first GET. */
private class CsrfCookieFilter : OncePerRequestFilter() {
	override fun doFilterInternal(
		request: HttpServletRequest,
		response: HttpServletResponse,
		chain: FilterChain,
	) {
		(request.getAttribute(CsrfToken::class.java.name) as? CsrfToken)?.token
		chain.doFilter(request, response)
	}
}
