package ch.sonensei.cockpit.core.testsupport

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

/**
 * The e2e test clock: the [base] clock shifted by an offset. Unlike [ch.sonensei.cockpit.core.persistence.MutableClock]
 * it keeps ticking after [set], so sessions, throttling and timestamps behave as in production, only at another time.
 * Thread-safe.
 */
class ShiftableClock(
	private val base: Clock,
	private val offset: AtomicReference<Duration> = AtomicReference(Duration.ZERO),
) : Clock() {
	/** From now on the clock reads [now], and runs on from there. */
	fun set(now: Instant) = offset.set(Duration.between(base.instant(), now))

	/** Back to the [base] clock's time. */
	fun reset() = offset.set(Duration.ZERO)

	override fun instant(): Instant = base.instant().plus(offset.get())

	override fun getZone(): ZoneId = base.zone

	/** A view in another zone that shares this clock's offset. */
	override fun withZone(zone: ZoneId): Clock = if (zone == base.zone) this else ShiftableClock(base.withZone(zone), offset)
}
