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
import org.springframework.security.authorization.AuthenticatedAuthorizationManager
import org.springframework.security.authorization.AuthorityAuthorizationManager
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.authorization.AuthorizationManagers
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.filter.OncePerRequestFilter

/**
 * HTTP security (SPEC.md §4.1).
 *
 * - Everything under `/api/`, `/actuator/` and the OpenAPI docs needs a login, except the health endpoint, the
 *   setup flow (it runs before an account exists) and login/logout (password or passkey). The SPA (static files and the `index.html`
 *   fallback for client routes) is public for GET/HEAD; any other request outside the API needs a login too.
 * - Logins are sessions in the database ([SessionAuthenticationFilter], cookie [SessionCookies.NAME]); there is no
 *   servlet `HttpSession`. The Android app sends `Authorization: Bearer` access tokens of a device
 *   ([BearerAuthenticationFilter]); it gets them from the public token endpoints.
 * - CSRF: double-submit cookie in Angular's convention. The `XSRF-TOKEN` cookie is set on every response that has
 *   none yet and replaced with a fresh token on login and logout; state-changing requests must echo it in
 *   `X-XSRF-TOKEN`, login and logout included. Requests with an `Authorization: Bearer` header, valid or not (never
 *   authenticated by their cookie, and a browser can't add the header cross-site without a CORS preflight), the
 *   token endpoints (password or passkey) and the passkey authentication options (called by the app, which has no
 *   cookies; their response is unreadable cross-origin) are exempt.
 * - A session that must enroll TOTP again (restored without the TOTP secret, [CockpitAuthentication.ROLE_TOTP_ENROLLMENT])
 *   may only call `me`, logout and the TOTP enrollment; every other protected request gets 403.
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
					.requestMatchers(HttpMethod.POST, PASSKEY_AUTHENTICATION_OPTIONS_PATH, PASSKEY_LOGIN_PATH, PASSKEY_TOKEN_PATH)
					.permitAll()
					.requestMatchers(HttpMethod.GET, ME_PATH)
					.authenticated()
					.requestMatchers(HttpMethod.POST, TOTP_ENROLLMENT_PATH, "$TOTP_ENROLLMENT_PATH/**")
					.authenticated()
					.requestMatchers("/api/**", "/actuator/**", "/v3/api-docs/**", "/v3/api-docs*", "/swagger-ui/**", "/swagger-ui*")
					.access(fullAccess)
					.requestMatchers(HttpMethod.GET, "/**")
					.permitAll()
					.requestMatchers(HttpMethod.HEAD, "/**")
					.permitAll()
					.anyRequest()
					.access(fullAccess)
			}.csrf {
				// The setup flow runs before any session or CSRF cookie exists; it is only open while no account is active.
				it
					.spa()
					.csrfTokenRepository(cookies.csrfTokenRepository())
					.ignoringRequestMatchers("$SETUP_PATH/**", SETUP_PATH, TOKEN_PATH, TOKEN_REFRESH_PATH)
					.ignoringRequestMatchers(PASSKEY_AUTHENTICATION_OPTIONS_PATH, PASSKEY_TOKEN_PATH)
					.ignoringRequestMatchers(BearerAuthenticationFilter.withBearerHeader)
			}
			// Stateless (the context lives only in the request), but without `SessionCreationPolicy.STATELESS`: that
			// adds a SessionManagementFilter, which takes every authenticated request for a fresh login and rotates the
			// CSRF token, so concurrent SPA requests send a token the cookie no longer has (403). Login and logout
			// rotate it themselves (AuthController).
			.securityContext { it.securityContextRepository(RequestAttributeSecurityContextRepository()) }
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
		const val ME_PATH = "$AUTH_PATH/me"
		const val TOTP_ENROLLMENT_PATH = "$AUTH_PATH/totp-enrollment"
		const val LOGIN_PATH = "$AUTH_PATH/login"
		const val LOGOUT_PATH = "$AUTH_PATH/logout"
		const val TOKEN_PATH = "$AUTH_PATH/token"
		const val TOKEN_REFRESH_PATH = "$TOKEN_PATH/refresh"
		const val PASSKEYS_PATH = "$AUTH_PATH/passkeys"
		const val PASSKEY_AUTHENTICATION_OPTIONS_PATH = "$PASSKEYS_PATH/authentication/options"
		const val PASSKEY_LOGIN_PATH = "$PASSKEYS_PATH/login"
		const val PASSKEY_TOKEN_PATH = "$PASSKEYS_PATH/token"
	}
}

/** Logged in, and not a session that may only enroll TOTP again (anonymous requests still get the 401 entry point). */
private val fullAccess: AuthorizationManager<RequestAuthorizationContext> =
	AuthorizationManagers.allOf(
		AuthenticatedAuthorizationManager.authenticated(),
		AuthorizationManagers.not(
			AuthorityAuthorizationManager.hasAuthority<RequestAuthorizationContext>(CockpitAuthentication.ROLE_TOTP_ENROLLMENT),
		),
	)

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
