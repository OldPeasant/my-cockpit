package ch.sonensei.cockpit.core.persistence

import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class ClockConfigurationTests {
	private val configuration = ClockConfiguration()

	@Test
	fun `the application clock is the system clock in UTC`() {
		val clock = configuration.clock()

		clock.zone shouldBe ZoneOffset.UTC
		// The clock ticks in microseconds (see below).
		val before = Instant.now().truncatedTo(ChronoUnit.MICROS)
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

	@Test
	fun `the application clock ticks in microseconds, the precision of timestamptz`() {
		val clock = configuration.clock()

		repeat(100) { (clock.instant().nano % 1_000) shouldBe 0 }
	}

	@Test
	fun `auditing timestamps are truncated to microseconds, also from a substituted clock`() {
		val clock = MutableClock(Instant.parse("2026-05-04T03:02:01.123456789Z"))

		configuration.clockDateTimeProvider(clock).now.get() shouldBe Instant.parse("2026-05-04T03:02:01.123456Z")
	}
}
