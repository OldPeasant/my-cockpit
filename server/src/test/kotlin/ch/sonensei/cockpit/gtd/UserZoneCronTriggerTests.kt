package ch.sonensei.cockpit.gtd

import ch.sonensei.cockpit.gtd.infra.UserZoneCronTrigger
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.scheduling.support.SimpleTriggerContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** The schedule of the tickler job ([UserZoneCronTrigger]): 00:05 in the user's zone, read for every run. */
class UserZoneCronTriggerTests {
	private fun contextAt(now: String) = SimpleTriggerContext(Clock.fixed(Instant.parse(now), ZoneOffset.UTC))

	@Test
	fun `the next run is at 00_05 in the user's zone`() {
		val trigger = UserZoneCronTrigger("0 5 0 * * *") { ZoneId.of("Europe/Zurich") }

		trigger.nextExecution(contextAt("2026-03-01T08:00:00Z")) shouldBe Instant.parse("2026-03-01T23:05:00Z")
	}

	@Test
	fun `a changed zone applies to the next planned run`() {
		var zone = ZoneId.of("Europe/Zurich")
		val trigger = UserZoneCronTrigger("0 5 0 * * *") { zone }
		zone = ZoneId.of("Asia/Kolkata")

		trigger.nextExecution(contextAt("2026-03-01T08:00:00Z")) shouldBe Instant.parse("2026-03-01T18:35:00Z")
	}

	@Test
	fun `the run stays at 00_05 local time across the switch to summer time`() {
		val trigger = UserZoneCronTrigger("0 5 0 * * *") { ZoneId.of("Europe/Zurich") }

		// Sunday 2026-03-29: clocks go from 02:00 CET to 03:00 CEST, after the 00:05 run.
		trigger.nextExecution(contextAt("2026-03-28T12:00:00Z")) shouldBe Instant.parse("2026-03-28T23:05:00Z")
		trigger.nextExecution(contextAt("2026-03-28T23:30:00Z")) shouldBe Instant.parse("2026-03-29T22:05:00Z")
	}

	@Test
	fun `the run stays at 00_05 local time across the switch back to winter time`() {
		val trigger = UserZoneCronTrigger("0 5 0 * * *") { ZoneId.of("Europe/Zurich") }

		// Sunday 2026-10-25: clocks go from 03:00 CEST back to 02:00 CET.
		trigger.nextExecution(contextAt("2026-10-24T12:00:00Z")) shouldBe Instant.parse("2026-10-24T22:05:00Z")
		trigger.nextExecution(contextAt("2026-10-25T12:00:00Z")) shouldBe Instant.parse("2026-10-25T23:05:00Z")
	}

	@Test
	fun `the run is exactly once per day, the next one planned after the last completed run`() {
		val trigger = UserZoneCronTrigger("0 5 0 * * *") { ZoneId.of("Europe/Zurich") }
		val ranAt = Instant.parse("2026-03-01T23:05:00Z")
		val context = SimpleTriggerContext(ranAt, ranAt, ranAt.plusSeconds(2))

		trigger.nextExecution(context) shouldBe Instant.parse("2026-03-02T23:05:00Z")
	}
}
