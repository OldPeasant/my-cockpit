package ch.sonensei.cockpit.core.auth

import ch.sonensei.cockpit.core.auth.domain.LoginThrottle
import ch.sonensei.cockpit.core.persistence.MutableClock
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The login backoff on its own, with a fixed clock: thresholds, the exact end of a block, reset, the cap and atomic
 * admission. An admitted attempt that never succeeds is a failure, so [fail] is just an admission.
 */
class LoginThrottleTests {
	private val clock = MutableClock(Instant.parse("2026-10-20T09:00:00Z"))
	private val throttle = LoginThrottle(clock)

	@Test
	fun `a fresh throttle allows attempts`() {
		throttle.retryAfter().shouldBeNull()
		throttle.tryBeginAttempt().shouldBeNull()
	}

	@Test
	fun `four consecutive failures do not block, the fifth blocks for 30 seconds`() {
		repeat(4) {
			fail()
			throttle.retryAfter().shouldBeNull()
		}

		fail()

		throttle.retryAfter() shouldBe Duration.ofSeconds(30)
	}

	@Test
	fun `the block ends exactly at its end time, not a moment later or earlier`() {
		repeat(5) { fail() }

		clock.advance(Duration.ofSeconds(30).minusMillis(1))
		throttle.retryAfter() shouldBe Duration.ofMillis(1)
		clock.advance(Duration.ofMillis(1))
		throttle.retryAfter().shouldBeNull()
	}

	@Test
	fun `each further failure doubles the block from the time of that failure`() {
		repeat(5) { fail() }
		clock.advance(Duration.ofSeconds(30))

		fail()
		throttle.retryAfter() shouldBe Duration.ofSeconds(60)
		clock.advance(Duration.ofSeconds(60))
		fail()
		throttle.retryAfter() shouldBe Duration.ofSeconds(120)
	}

	@Test
	fun `an attempt while blocked is rejected with the remaining time and does not count`() {
		repeat(5) { fail() }
		clock.advance(Duration.ofSeconds(10))

		throttle.tryBeginAttempt() shouldBe Duration.ofSeconds(20)
		throttle.tryBeginAttempt() shouldBe Duration.ofSeconds(20)

		// The rejected attempts were not failures: the next one is the 6th, so 60 s, not 120 s.
		clock.advance(Duration.ofSeconds(20))
		fail()
		throttle.retryAfter() shouldBe Duration.ofSeconds(60)
	}

	@Test
	fun `a success resets the counter and lifts an active block`() {
		repeat(7) { failAfterBlock() }

		throttle.recordSuccess()

		throttle.retryAfter().shouldBeNull()
		// Counting starts over: four failures are free again.
		repeat(4) { fail() }
		throttle.retryAfter().shouldBeNull()
		fail()
		throttle.retryAfter() shouldBe Duration.ofSeconds(30)
	}

	@Test
	fun `the attempt that ends in a success may be the one that started the block`() {
		repeat(4) { fail() }

		throttle.tryBeginAttempt().shouldBeNull()
		throttle.retryAfter() shouldBe Duration.ofSeconds(30)
		throttle.recordSuccess()

		throttle.retryAfter().shouldBeNull()
	}

	@Test
	fun `many failures block for at most one hour`() {
		repeat(1_000) { failAfterBlock() }

		throttle.retryAfter() shouldBe LoginThrottle.MAX_DELAY
	}

	@Test
	fun `the delay table is 30 s doubled per failure from the fifth, capped at one hour`() {
		listOf(Int.MIN_VALUE, -1, 0, 1, 4).forEach { LoginThrottle.delayAfter(it) shouldBe Duration.ZERO }
		LoginThrottle.delayAfter(7) shouldBe Duration.ofSeconds(120)
		LoginThrottle.delayAfter(21) shouldBe Duration.ofHours(1)
		LoginThrottle.delayAfter(22) shouldBe Duration.ofHours(1)
		LoginThrottle.delayAfter(70) shouldBe Duration.ofHours(1)
	}

	@Test
	fun `concurrent attempts are admitted atomically, at most the free attempts before the block`() {
		val threads = 16
		val barrier = CyclicBarrier(threads)
		val pool = Executors.newFixedThreadPool(threads)
		val outcomes =
			try {
				(0 until threads)
					.map {
						pool.submit<Duration?> {
							barrier.await(10, TimeUnit.SECONDS)
							throttle.tryBeginAttempt()
						}
					}.map { it.get(10, TimeUnit.SECONDS) }
			} finally {
				pool.shutdownNow()
			}

		outcomes.count { it == null } shouldBe LoginThrottle.FREE_ATTEMPTS
		outcomes.filterNotNull().forEach { it shouldBe Duration.ofSeconds(30) }
		throttle.retryAfter() shouldBe Duration.ofSeconds(30)
	}

	/** One admitted attempt that does not succeed, i.e. a failure. */
	private fun fail() {
		throttle.tryBeginAttempt().shouldBeNull()
	}

	/** Waits out any active block, then fails once more. */
	private fun failAfterBlock() {
		throttle.retryAfter()?.let { clock.advance(it) }
		fail()
	}
}
