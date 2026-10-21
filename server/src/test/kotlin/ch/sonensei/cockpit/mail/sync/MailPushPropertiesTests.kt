package ch.sonensei.cockpit.mail.sync

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

class MailPushPropertiesTests {
	private fun properties(
		reIdleInterval: Duration = Duration.ofMinutes(29),
		pollInterval: Duration = Duration.ofMinutes(5),
		initialBackoff: Duration = Duration.ofSeconds(5),
		maxBackoff: Duration = Duration.ofMinutes(5),
	) = MailPushProperties(true, reIdleInterval, pollInterval, initialBackoff, maxBackoff)

	@Test
	fun `the backoff starts at the initial wait and doubles on every further failure`() {
		val p = properties()

		p.backoff(1) shouldBe Duration.ofSeconds(5)
		p.backoff(2) shouldBe Duration.ofSeconds(10)
		p.backoff(3) shouldBe Duration.ofSeconds(20)
		p.backoff(6) shouldBe Duration.ofSeconds(160)
	}

	@Test
	fun `the backoff is capped at the maximum wait`() {
		val p = properties()

		// 5 s * 2^6 = 320 s is above the 5 min cap.
		p.backoff(7) shouldBe Duration.ofMinutes(5)
		p.backoff(8) shouldBe Duration.ofMinutes(5)
	}

	@Test
	fun `a very long failure streak neither overflows nor exceeds the cap`() {
		properties().backoff(Int.MAX_VALUE) shouldBe Duration.ofMinutes(5)
	}

	@Test
	fun `a cap that is not a power-of-two multiple of the initial wait is reached exactly`() {
		val p = properties(initialBackoff = Duration.ofSeconds(3), maxBackoff = Duration.ofSeconds(10))

		p.backoff(2) shouldBe Duration.ofSeconds(6)
		p.backoff(3) shouldBe Duration.ofSeconds(10)
	}

	@Test
	fun `an initial wait equal to the maximum stays constant`() {
		val p = properties(initialBackoff = Duration.ofSeconds(30), maxBackoff = Duration.ofSeconds(30))

		p.backoff(1) shouldBe Duration.ofSeconds(30)
		p.backoff(4) shouldBe Duration.ofSeconds(30)
	}

	@Test
	fun `the backoff needs a positive failure count`() {
		shouldThrow<IllegalArgumentException> { properties().backoff(0) }
		shouldThrow<IllegalArgumentException> { properties().backoff(-1) }
	}

	@Test
	fun `the defaults keep the re-IDLE below the 30 minutes servers may drop an IDLE after`() {
		val p = properties()

		(p.reIdleInterval < Duration.ofMinutes(30)) shouldBe true
		p.pollInterval shouldBe Duration.ofMinutes(5)
	}

	@Test
	fun `intervals must be positive`() {
		shouldThrow<IllegalArgumentException> { properties(reIdleInterval = Duration.ZERO) }
		shouldThrow<IllegalArgumentException> { properties(reIdleInterval = Duration.ofSeconds(-1)) }
		shouldThrow<IllegalArgumentException> { properties(pollInterval = Duration.ZERO) }
		shouldThrow<IllegalArgumentException> { properties(pollInterval = Duration.ofMinutes(-5)) }
	}

	@Test
	fun `the initial backoff must be positive and not above the maximum`() {
		shouldThrow<IllegalArgumentException> { properties(initialBackoff = Duration.ZERO) }
		shouldThrow<IllegalArgumentException> { properties(initialBackoff = Duration.ofSeconds(-5)) }
		shouldThrow<IllegalArgumentException> {
			properties(initialBackoff = Duration.ofMinutes(6), maxBackoff = Duration.ofMinutes(5))
		}
	}
}
