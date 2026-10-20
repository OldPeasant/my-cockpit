package ch.sonensei.cockpit.core.persistence

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.auditing.DateTimeProvider
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import java.time.Clock
import java.util.Optional

/**
 * The one [Clock] of the application (UTC). Inject it wherever a timestamp is taken; never call
 * `Instant.now()` directly. Entity timestamps come from it through JPA auditing. Tests substitute it with a
 * `@Primary` [MutableClock] bean.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(dateTimeProviderRef = "clockDateTimeProvider")
class ClockConfiguration {
	@Bean
	fun clock(): Clock = Clock.systemUTC()

	@Bean
	fun clockDateTimeProvider(clock: Clock): DateTimeProvider = DateTimeProvider { Optional.of(clock.instant()) }
}
