package ch.sonensei.cockpit.core.auth.domain

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Lockout after repeated login failures (SPEC.md §4.1). The first [FREE_ATTEMPTS] consecutive failures cost
 * nothing; from then on every failure blocks further attempts for [BASE_DELAY] doubled per extra failure, at most
 * [MAX_DELAY]. A successful login resets it. Attempts while blocked are rejected without checking credentials and do
 * not count as failures.
 *
 * The counter is global, not per client address: there is only one account, and an attacker could rotate
 * addresses. It lives in memory, so a server restart resets it.
 */
@Component
class LoginThrottle(
	private val clock: Clock,
) {
	private var failures = 0
	private var blockedUntil: Instant? = null

	/** How long the caller must still wait before the next attempt, or null if an attempt is allowed now. */
	@Synchronized
	fun retryAfter(): Duration? {
		val until = blockedUntil ?: return null
		val remaining = Duration.between(clock.instant(), until)
		return if (remaining.isPositive) remaining else null
	}

	@Synchronized
	fun recordFailure() {
		failures++
		if (failures >= FREE_ATTEMPTS) {
			blockedUntil = clock.instant().plus(delayAfter(failures))
		}
	}

	@Synchronized
	fun recordSuccess() {
		failures = 0
		blockedUntil = null
	}

	companion object {
		const val FREE_ATTEMPTS = 5
		val BASE_DELAY: Duration = Duration.ofSeconds(30)
		val MAX_DELAY: Duration = Duration.ofHours(1)

		/** The block after the [failures]th consecutive failure: 30 s after the 5th, 60 s after the 6th, ... */
		fun delayAfter(failures: Int): Duration {
			if (failures < FREE_ATTEMPTS) return Duration.ZERO
			// Doubling beyond 2^7 already exceeds MAX_DELAY; cap the exponent so the shift cannot overflow.
			val exponent = (failures - FREE_ATTEMPTS).coerceAtMost(MAX_EXPONENT)
			val delay = BASE_DELAY.multipliedBy(1L shl exponent)
			return if (delay > MAX_DELAY) MAX_DELAY else delay
		}

		private const val MAX_EXPONENT = 16
	}
}
