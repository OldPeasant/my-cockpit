package ch.sonensei.cockpit.mail.sync

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import java.time.Duration

/**
 * New-mail push and polling (`cockpit.mail.push.*`, SPEC.md §7.2), run by [MailPushService]. The defaults suit
 * production; the test configuration turns it off ([enabled]) so tests sync explicitly.
 */
@ConfigurationProperties("cockpit.mail.push")
class MailPushProperties(
	/** IDLE on every account's Inbox and poll the other folders. */
	@DefaultValue("true") val enabled: Boolean,
	/**
	 * How long one IDLE command runs before it is ended with a NOOP and started again. Servers may drop an IDLE after
	 * 30 minutes (RFC 2177), so it stays below that.
	 */
	@DefaultValue("29m") val reIdleInterval: Duration,
	/** How often every folder is synced, the Inbox too (a safety net for an IDLE connection that died silently). */
	@DefaultValue("5m") val pollInterval: Duration,
	/** Wait before the first reconnect after an IDLE connection failed; doubled on every further failure. */
	@DefaultValue("5s") val initialBackoff: Duration,
	/** Longest wait between reconnects. */
	@DefaultValue("5m") val maxBackoff: Duration,
) {
	init {
		require(reIdleInterval.isPositive) { "cockpit.mail.push.re-idle-interval must be positive" }
		require(pollInterval.isPositive) { "cockpit.mail.push.poll-interval must be positive" }
		require(initialBackoff.isPositive && maxBackoff >= initialBackoff) {
			"cockpit.mail.push.initial-backoff must be positive and not above max-backoff"
		}
	}

	/** The wait before reconnect attempt [failures] (1 = after the first failure): exponential, capped at [maxBackoff]. */
	fun backoff(failures: Int): Duration {
		require(failures > 0) { "failures must be positive" }
		var wait = initialBackoff
		repeat(failures - 1) {
			if (wait >= maxBackoff) return maxBackoff
			wait = wait.multipliedBy(2)
		}
		return minOf(wait, maxBackoff)
	}
}
