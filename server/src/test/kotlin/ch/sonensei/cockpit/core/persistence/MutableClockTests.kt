package ch.sonensei.cockpit.core.persistence

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class MutableClockTests {
	private val start = Instant.parse("2026-03-01T08:00:00Z")

	@Test
	fun `stands still until set or advanced`() {
		val clock = MutableClock(start)

		clock.instant() shouldBe start
		clock.advance(Duration.ofDays(2)) shouldBe start.plus(Duration.ofDays(2))
		clock.instant() shouldBe start.plus(Duration.ofDays(2))

		clock.set(start)
		clock.instant() shouldBe start
		clock.zone shouldBe ZoneOffset.UTC
	}

	@Test
	fun `a zoned view shares the time of the original clock`() {
		val clock = MutableClock(start)
		val zurich = clock.withZone(ZoneId.of("Europe/Zurich"))

		clock.advance(Duration.ofHours(1))

		zurich.instant() shouldBe start.plusSeconds(3600)
		zurich.zone shouldBe ZoneId.of("Europe/Zurich")
		clock.withZone(ZoneOffset.UTC) shouldBe clock
	}

	@Test
	fun `setting the time through a zoned view moves the original clock`() {
		val clock = MutableClock(start)
		val zurich = clock.withZone(ZoneId.of("Europe/Zurich")) as MutableClock

		zurich.set(start.plusSeconds(60))

		clock.instant() shouldBe start.plusSeconds(60)
		zurich.withZone(ZoneOffset.UTC).instant() shouldBe start.plusSeconds(60)
	}
}
