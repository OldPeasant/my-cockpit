package ch.sonensei.cockpit.core.auth.infra

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint

/**
 * HTTP security (SPEC.md §4.1). Every request needs authentication except the health endpoint and the setup flow,
 * which must be reachable before an account exists. Unauthenticated requests get a plain 401 (no login form or
 * basic-auth challenge). The login mechanisms (session, CSRF cookie, bearer tokens) are added by later tasks.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
	@Bean
	fun securityFilterChain(http: HttpSecurity): SecurityFilterChain =
		http
			.authorizeHttpRequests {
				it
					.requestMatchers("/actuator/health", "/actuator/health/**", "$SETUP_PATH/**", SETUP_PATH)
					.permitAll()
					.anyRequest()
					.authenticated()
			}
			// The setup flow runs before any session or CSRF cookie exists; it is only open while no account is active.
			.csrf { it.ignoringRequestMatchers("$SETUP_PATH/**", SETUP_PATH) }
			.exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
			.build()

	/** Argon2id with Spring Security's recommended parameters (salted, encoded with its parameters). */
	@Bean
	fun passwordEncoder(): PasswordEncoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()

	companion object {
		const val SETUP_PATH = "/api/v1/setup"
	}
}
