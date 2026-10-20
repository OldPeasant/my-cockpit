package ch.sonensei.cockpit.core.persistence

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

/** A [Clock] that stands still until it is set or advanced: for tests and the e2e test clock. Thread-safe. */
class MutableClock(
	initial: Instant,
	private val zone: ZoneId = ZoneOffset.UTC,
	private val now: AtomicReference<Instant> = AtomicReference(initial),
) : Clock() {
	fun set(instant: Instant) = now.set(instant)

	fun advance(duration: Duration): Instant = now.updateAndGet { it.plus(duration) }

	override fun instant(): Instant = now.get()

	override fun getZone(): ZoneId = zone

	/** A view in another zone that shares this clock's time. */
	override fun withZone(zone: ZoneId): Clock = if (zone == this.zone) this else MutableClock(instant(), zone, now)
}
