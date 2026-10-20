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

/** The login backoff on its own, with a fixed clock: thresholds, the exact end of a block, reset and the cap. */
class LoginThrottleTests {
	private val clock = MutableClock(Instant.parse("2026-10-20T09:00:00Z"))
	private val throttle = LoginThrottle(clock)

	@Test
	fun `a fresh throttle allows attempts`() {
		throttle.retryAfter().shouldBeNull()
	}

	@Test
	fun `four consecutive failures do not block, the fifth blocks for 30 seconds`() {
		repeat(4) {
			throttle.recordFailure()
			throttle.retryAfter().shouldBeNull()
		}

		throttle.recordFailure()

		throttle.retryAfter() shouldBe Duration.ofSeconds(30)
	}

	@Test
	fun `the block ends exactly at its end time, not a moment later or earlier`() {
		repeat(5) { throttle.recordFailure() }

		clock.advance(Duration.ofSeconds(30).minusMillis(1))
		throttle.retryAfter() shouldBe Duration.ofMillis(1)
		clock.advance(Duration.ofMillis(1))
		throttle.retryAfter().shouldBeNull()
	}

	@Test
	fun `each further failure doubles the block from the time of that failure`() {
		repeat(5) { throttle.recordFailure() }
		clock.advance(Duration.ofSeconds(30))

		throttle.recordFailure()
		throttle.retryAfter() shouldBe Duration.ofSeconds(60)
		clock.advance(Duration.ofSeconds(60))
		throttle.recordFailure()
		throttle.retryAfter() shouldBe Duration.ofSeconds(120)
	}

	@Test
	fun `a success resets the counter and lifts an active block`() {
		repeat(7) { throttle.recordFailure() }

		throttle.recordSuccess()

		throttle.retryAfter().shouldBeNull()
		// Counting starts over: four failures are free again.
		repeat(4) { throttle.recordFailure() }
		throttle.retryAfter().shouldBeNull()
		throttle.recordFailure()
		throttle.retryAfter() shouldBe Duration.ofSeconds(30)
	}

	@Test
	fun `many failures block for at most one hour`() {
		repeat(1_000) { throttle.recordFailure() }

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
	fun `concurrent failures are all counted`() {
		val threads = 8
		val barrier = CyclicBarrier(threads)
		val pool = Executors.newFixedThreadPool(threads)
		try {
			(0 until threads)
				.map {
					pool.submit {
						barrier.await(10, TimeUnit.SECONDS)
						throttle.recordFailure()
					}
				}.forEach { it.get(10, TimeUnit.SECONDS) }
		} finally {
			pool.shutdownNow()
		}

		// 8 failures: 30 s * 2^3.
		throttle.retryAfter() shouldBe LoginThrottle.delayAfter(threads)
		throttle.retryAfter() shouldBe Duration.ofSeconds(240)
	}
}
