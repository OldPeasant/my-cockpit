package ch.sonensei.cockpit.core.persistence

import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class ClockConfigurationTests {
	private val configuration = ClockConfiguration()

	@Test
	fun `the application clock is the system clock in UTC`() {
		val clock = configuration.clock()

		clock.zone shouldBe ZoneOffset.UTC
		val before = Instant.now()
		val now = clock.instant()
		val after = Instant.now()
		now shouldBeGreaterThanOrEqualTo before
		now shouldBeLessThanOrEqualTo after
	}

	@Test
	fun `auditing timestamps are read from the injected clock on every call`() {
		val start = Instant.parse("2026-05-04T03:02:01Z")
		val clock = MutableClock(start)
		val provider = configuration.clockDateTimeProvider(clock)

		provider.now.get() shouldBe start
		clock.advance(Duration.ofMinutes(1))
		provider.now.get() shouldBe start.plus(Duration.ofMinutes(1))
	}
}
