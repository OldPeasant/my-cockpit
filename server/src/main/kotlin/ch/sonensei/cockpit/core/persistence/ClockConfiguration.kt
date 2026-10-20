package ch.sonensei.cockpit.core.persistence

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.auditing.DateTimeProvider
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Optional

/**
 * The one [Clock] of the application (UTC). Inject it wherever a timestamp is taken; never call
 * `Instant.now()` directly. It ticks in microseconds, the precision of PostgreSQL `timestamptz`, so an
 * instant kept in memory equals the one read back. Entity timestamps come from it through JPA auditing.
 * Tests substitute it with a `@Primary` [MutableClock] bean.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(dateTimeProviderRef = "clockDateTimeProvider")
class ClockConfiguration {
	@Bean
	fun clock(): Clock = Clock.tick(Clock.systemUTC(), Duration.of(1, ChronoUnit.MICROS))

	@Bean
	fun clockDateTimeProvider(clock: Clock): DateTimeProvider = DateTimeProvider { Optional.of(clock.storableInstant()) }
}

/** The clock's instant at `timestamptz` precision (microseconds), also for a substituted test clock. */
internal fun Clock.storableInstant(): Instant = instant().truncatedTo(ChronoUnit.MICROS)
