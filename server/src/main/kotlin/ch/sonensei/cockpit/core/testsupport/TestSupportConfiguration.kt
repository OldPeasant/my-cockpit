package ch.sonensei.cockpit.core.testsupport

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit

/**
 * Test hooks of the end-to-end stack (profile `e2e` only, see `web/e2e/README.md`): a settable clock and the
 * public, CSRF-free `/api/test/` endpoints of [TestSupportController]. None of these beans exist in other profiles.
 */
@Configuration(proxyBeanMethods = false)
@Profile("e2e")
class TestSupportConfiguration {
	init {
		LoggerFactory.getLogger(javaClass).warn("Profile e2e: unauthenticated test endpoints under {} are enabled", TEST_PATH)
	}

	/** Replaces the application clock; ticks in microseconds like the production one. */
	@Bean
	@Primary
	fun shiftableClock(): ShiftableClock = ShiftableClock(Clock.tick(Clock.systemUTC(), Duration.of(1, ChronoUnit.MICROS)))

	/** Ahead of the application's filter chain: the hooks run before any account (and so any login) exists. */
	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	fun testSupportFilterChain(http: HttpSecurity): SecurityFilterChain =
		http
			.securityMatcher("$TEST_PATH/**")
			.authorizeHttpRequests { it.anyRequest().permitAll() }
			.csrf { it.disable() }
			.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
			.requestCache { it.disable() }
			.build()

	companion object {
		const val TEST_PATH = "/api/test"
	}
}
