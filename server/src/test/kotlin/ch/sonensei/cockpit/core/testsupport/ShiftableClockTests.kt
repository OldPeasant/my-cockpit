package ch.sonensei.cockpit.core.testsupport

import ch.sonensei.cockpit.core.persistence.MutableClock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class ShiftableClockTests {
	private val base = MutableClock(Instant.parse("2026-10-20T10:00:00Z"))
	private val clock = ShiftableClock(base)

	@Test
	fun `reads the base clock until it is set`() {
		clock.instant() shouldBe base.instant()
		clock.zone shouldBe ZoneOffset.UTC
	}

	@Test
	fun `runs on from the set time`() {
		clock.set(Instant.parse("2027-01-01T00:00:00Z"))
		clock.instant() shouldBe Instant.parse("2027-01-01T00:00:00Z")

		base.advance(Duration.ofSeconds(90))
		clock.instant() shouldBe Instant.parse("2027-01-01T00:01:30Z")
	}

	@Test
	fun `can be set into the past`() {
		clock.set(Instant.parse("2020-02-29T12:00:00Z"))
		base.advance(Duration.ofMinutes(1))
		clock.instant() shouldBe Instant.parse("2020-02-29T12:01:00Z")
	}

	@Test
	fun `reset goes back to the base time`() {
		clock.set(Instant.parse("2027-01-01T00:00:00Z"))
		clock.reset()
		clock.instant() shouldBe base.instant()
	}

	@Test
	fun `a view in another zone shares the offset`() {
		val zurich = ZoneId.of("Europe/Zurich")
		val view = clock.withZone(zurich)
		clock.set(Instant.parse("2027-01-01T00:00:00Z"))

		view.zone shouldBe zurich
		view.instant() shouldBe Instant.parse("2027-01-01T00:00:00Z")
		clock.withZone(ZoneOffset.UTC) shouldBeSameInstanceAs clock
	}
}
