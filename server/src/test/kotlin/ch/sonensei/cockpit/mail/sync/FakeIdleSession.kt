package ch.sonensei.cockpit.mail.sync

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * An [IdleSession] without a network: [await] blocks until the test reports a [change], [fail]s the connection,
 * [refresh] is called or the session is closed (then [await] throws, like a closed IMAP connection).
 */
internal class FakeIdleSession : IdleSession {
	private val events = LinkedBlockingQueue<() -> Unit>()
	private val closedLatch = CountDownLatch(1)
	val refreshes = AtomicInteger()
	var refreshFailure: Exception? = null

	val closed: Boolean get() = closedLatch.count == 0L

	override fun await() = events.take().invoke()

	/** The server reports something about the folder. */
	fun change() = events.put {}

	/** The connection fails during the IDLE. */
	fun fail(e: Exception) = events.put { throw e }

	override fun refresh() {
		refreshes.incrementAndGet()
		refreshFailure?.let { throw it }
		events.put {}
	}

	@Synchronized
	override fun close() {
		if (closed) return
		closedLatch.countDown()
		events.put { throw IllegalStateException("The fake IDLE connection is closed") }
	}
}

/** Polls [condition] until it holds; fails after [timeout]. */
internal fun waitUntil(
	timeout: Duration = Duration.ofSeconds(5),
	condition: () -> Boolean,
) {
	val end = System.nanoTime() + timeout.toNanos()
	while (!condition()) {
		check(System.nanoTime() < end) { "Condition not met within $timeout" }
		Thread.sleep(5)
	}
}
